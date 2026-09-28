package cl.erz.sailer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cl.erz.sailer.R
import cl.erz.sailer.databinding.ActivityAttendanceScannerBinding
import cl.erz.sailer.databinding.ItemScannedStudentBinding
import cl.erz.sailer.site.SiteIcons
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Native replacement for the site's attendance QR scanner
 * (`…/asistencias2/attendance_take?id=…`, opened by "Comenzar!"): a
 * full-screen camera decoding QR codes on-device, with the students checked
 * in so far listed at the bottom.
 *
 * The attendance page itself still runs, in a hidden WebView ([pageView]),
 * and does all the talking to the server: each code goes to the page's own
 * `attendance.check(id)`, which queues it in localStorage and sends the queue
 * in batches (see the site's `asistencias2/static/attendance.js`), so codes
 * scanned offline are kept and retried exactly as on the site. The list is
 * read back from the page's `ul.integrantes`, where a present student's row is
 * shown and gets the class `enviado` once the server has it.
 *
 * The official app's `UcursosMobile.attendance_take` bridge only loads the
 * same page in its WebView, so there's no native original to copy.
 */
class AttendanceScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttendanceScannerBinding
    private val adapter = StudentAdapter()

    private var pageReady = false
    private var pageFailed = false

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameras: List<CameraInfo> = emptyList()
    private var cameraIndex = 0
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    // A code in front of the camera is decoded many times a second: it's
    // handled once, then ignored until it has been out of sight for a while.
    private var checking = false
    private var lastCode: String? = null
    private var lastCodeSeenAt = 0L

    private var tones: ToneGenerator? = null
    private var isLeaving = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionPanel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || !isScannerUrl(url)) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAttendanceScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.backButton.setOnClickListener { leave() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.grantButton.setOnClickListener { requestCameraAgain() }
        onBackPressedDispatcher.addCallback(this) { leave() }

        analysisExecutor = Executors.newSingleThreadExecutor()
        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull()

        loadPage(url)
        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshList()
                    delay(LIST_REFRESH_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's system settings page with the camera allowed.
        if (binding.permissionPanel.visibility == View.VISIBLE && hasCameraPermission()) startCamera()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity isn't recreated on rotation (that would reload the page
        // and lose nothing, but restart the camera); just turn the frames.
        val rotation = binding.preview.display?.rotation ?: return
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.pageView.destroy()
            barcodeScanner.close()
            analysisExecutor.shutdown()
        }
        tones?.release()
        super.onDestroy()
    }

    private fun applyInsets() {
        val margin = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.backButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + margin
                marginStart = bars.left + margin
            }
            binding.sheet.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    // --- The attendance page ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadPage(url: String) {
        val page = binding.pageView
        page.settings.javaScriptEnabled = true
        page.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        // No WebChromeClient: the page's own web scanner asks for the camera
        // too, and without one that request is simply denied.
        page.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (pageReady || pageFailed) return
                lifecycleScope.launch {
                    // The page registers its API endpoint on its own ready event.
                    repeat(PAGE_READY_ATTEMPTS) {
                        if (refreshList()) {
                            pageReady = true
                            return@launch
                        }
                        delay(PAGE_READY_POLL_MS)
                    }
                    onPageFailed()
                }
            }

            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) onPageFailed()
            }
        }
        page.loadUrl(url)
    }

    private fun onPageFailed() {
        if (pageReady || pageFailed) return
        pageFailed = true
        binding.emptyText.setText(R.string.scanner_load_failed)
        binding.emptyText.visibility = View.VISIBLE
        binding.list.visibility = View.GONE
    }

    /** Reads the page's list into the sheet; false while the page isn't usable yet. */
    private suspend fun refreshList(): Boolean {
        if (pageFailed) return false
        val json = binding.pageView.eval(LIST_JS).let(::unquote) ?: return false
        val state = runCatching { JSONObject(json) }.getOrNull() ?: return false
        val rows = state.getJSONArray("students")
        val students = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Student(row.getString("id"), row.getString("name"), row.optString("photo").takeIf { it.startsWith("https://") }, row.getBoolean("sent"))
        }
        showStudents(students)
        return true
    }

    private fun showStudents(students: List<Student>) {
        binding.count.text = students.size.toString()
        binding.emptyText.setText(R.string.scanner_hint)
        binding.emptyText.visibility = if (students.isEmpty()) View.VISIBLE else View.GONE
        binding.list.visibility = if (students.isEmpty()) View.GONE else View.VISIBLE
        // Up to about a third of the screen; the camera needs the rest.
        val maxHeight = (binding.root.height * MAX_LIST_FRACTION).toInt()
        val wanted = students.size * dp(ROW_HEIGHT_DP) + binding.list.paddingBottom
        binding.list.updateLayoutParams {
            height = if (maxHeight in 1 until wanted) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val grewAtTop = adapter.currentList.firstOrNull()?.id != students.firstOrNull()?.id
        adapter.submitList(students) { if (grewAtTop) binding.list.scrollToPosition(0) }
    }

    /** Waits (briefly) for queued codes to reach the server, then closes. */
    private fun leave() {
        if (isLeaving) return
        isLeaving = true
        if (!pageReady) {
            finish()
            return
        }
        lifecycleScope.launch {
            binding.pageView.eval("attendance.send();")
            for (attempt in 1..FLUSH_ATTEMPTS) {
                val pending = binding.pageView.eval(PENDING_JS).toIntOrNull() ?: 0
                if (pending == 0) break
                delay(FLUSH_POLL_MS)
            }
            // Anything still queued stays in the page's localStorage and is
            // sent the next time the scanner is opened for this event.
            finish()
        }
    }

    // --- Camera ---

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        binding.permissionMessage.setText(R.string.scanner_no_permission)
        binding.grantButton.visibility = View.VISIBLE
        binding.permissionPanel.visibility = View.VISIBLE
        binding.switchCameraButton.visibility = View.GONE
    }

    private fun requestCameraAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            // "Don't ask again" (or denied twice): only the system settings can allow it now.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun startCamera() {
        binding.permissionPanel.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            cameras = scanningCameras(provider.availableCameraInfos)
            if (cameras.isEmpty()) {
                binding.permissionMessage.setText(R.string.scanner_no_camera)
                binding.grantButton.visibility = View.GONE
                binding.permissionPanel.visibility = View.VISIBLE
                return@addListener
            }
            val saved = getPreferences(Context.MODE_PRIVATE).getInt(PREF_CAMERA_FACING, CameraSelector.LENS_FACING_BACK)
            cameraIndex = cameras.indexOfFirst { it.lensFacing == saved }.coerceAtLeast(0)
            binding.switchCameraButton.visibility = if (cameras.size > 1) View.VISIBLE else View.GONE
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * One color camera per side, so the switch button is a plain back/front
     * toggle. Skips the black-and-white sensors some phones also list, like
     * the Pixel 4's infrared face-unlock camera, plus any extra lenses on the
     * same side (the first one listed is the system's default).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun scanningCameras(all: List<CameraInfo>): List<CameraInfo> {
        val color = all.filter { info ->
            val camera2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull() ?: return@filter true
            val capabilities = camera2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isMonochrome = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME) == true
            val filter = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            val isMonoSensor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (
                filter == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
                    filter == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
            !isMonochrome && !isMonoSensor
        }
        return color.distinctBy { it.lensFacing }.sortedBy { if (it.lensFacing == CameraSelector.LENS_FACING_BACK) 0 else 1 }
    }

    private fun switchCamera() {
        if (cameras.size < 2) return
        cameraIndex = (cameraIndex + 1) % cameras.size
        getPreferences(Context.MODE_PRIVATE).edit().putInt(PREF_CAMERA_FACING, cameras[cameraIndex].lensFacing).apply()
        binding.switchCameraButton.animate().rotationBy(180f).setDuration(300).start()
        bindCamera()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val target = cameras.getOrNull(cameraIndex) ?: return
        val selector = CameraSelector.Builder().addCameraFilter { infos -> infos.filter { it == target } }.build()
        val rotation = binding.preview.display?.rotation ?: android.view.Surface.ROTATION_0

        val preview = Preview.Builder().setTargetRotation(rotation).build()
        preview.setSurfaceProvider(binding.preview.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)

        provider.unbindAll()
        runCatching { provider.bindToLifecycle(this, selector, preview, analysis) }
        this.preview = preview
        this.analysis = analysis
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(frame: ImageProxy) {
        val image = frame.image
        if (image == null) {
            frame.close()
            return
        }
        barcodeScanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { codes ->
                codes.firstNotNullOfOrNull { it.rawValue }?.let(::onCode)
            }
            .addOnCompleteListener { frame.close() }
    }

    // --- Scans ---

    private fun onCode(raw: String) {
        // The students' QR holds their user hash (or RUT), sometimes prefixed
        // with "ucampus", as the site's own scanner strips too.
        val code = raw.trim().removePrefix("ucampus")
        val now = SystemClock.elapsedRealtime()
        val seenRecently = code == lastCode && now - lastCodeSeenAt < SAME_CODE_COOLDOWN_MS
        if (code == lastCode) lastCodeSeenAt = now
        if (seenRecently || checking || !pageReady || isLeaving) return
        lastCode = code
        lastCodeSeenAt = now

        // The page builds a CSS selector out of it.
        if (!CODE_PATTERN.matches(code)) {
            showResult(Result.UNKNOWN, null)
            return
        }
        checking = true
        lifecycleScope.launch {
            val json = binding.pageView.eval(checkJs(code)).let(::unquote)
            checking = false
            val result = runCatching { JSONObject(json ?: return@launch) }.getOrNull() ?: return@launch
            val name = result.optString("name").takeIf { it.isNotBlank() }
            when (result.optString("status")) {
                "new" -> showResult(Result.NEW, name)
                "again" -> showResult(Result.AGAIN, name)
                "unknown" -> showResult(Result.UNKNOWN, null)
                else -> return@launch
            }
            refreshList()
        }
    }

    private enum class Result { NEW, AGAIN, UNKNOWN }

    private fun showResult(result: Result, name: String?) {
        val (color, icon, text) = when (result) {
            Result.NEW -> Triple(R.color.scannerOk, R.drawable.ic_check_circle, name ?: getString(R.string.scanner_saved))
            Result.AGAIN -> Triple(R.color.scannerPending, R.drawable.ic_check_circle, getString(R.string.scanner_again, name ?: ""))
            Result.UNKNOWN -> Triple(R.color.scannerWrong, R.drawable.ic_error, getString(R.string.scanner_unknown))
        }
        val tint = ContextCompat.getColor(this, color)

        binding.flash.setBackgroundColor(tint)
        binding.flash.animate().cancel()
        binding.flash.alpha = FLASH_ALPHA
        binding.flash.animate().alpha(0f).setDuration(FLASH_MS).start()

        val pill = binding.resultPill
        pill.text = text
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        pill.compoundDrawablesRelative[0]?.setTint(tint)
        pill.animate().cancel()
        pill.visibility = View.VISIBLE
        pill.alpha = 0f
        pill.translationY = dp(8).toFloat()
        pill.animate().alpha(1f).translationY(0f).setDuration(150).withEndAction {
            pill.animate().alpha(0f).setStartDelay(PILL_VISIBLE_MS).setDuration(250).withEndAction {
                pill.visibility = View.INVISIBLE
            }.start()
        }.start()

        val haptic = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> HapticFeedbackConstants.LONG_PRESS
            result == Result.UNKNOWN -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.CONFIRM
        }
        binding.root.performHapticFeedback(haptic)
        when (result) {
            Result.NEW -> tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Result.AGAIN -> tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            Result.UNKNOWN -> tones?.startTone(ToneGenerator.TONE_SUP_ERROR, 300)
        }
    }

    // --- List ---

    private data class Student(val id: String, val name: String, val photo: String?, val sent: Boolean)

    private inner class StudentAdapter : ListAdapter<Student, StudentAdapter.Holder>(object : DiffUtil.ItemCallback<Student>() {
        override fun areItemsTheSame(a: Student, b: Student) = a.id == b.id
        override fun areContentsTheSame(a: Student, b: Student) = a == b
    }) {
        inner class Holder(val row: ItemScannedStudentBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemScannedStudentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val student = getItem(position)
            val row = holder.row
            row.name.text = student.name
            row.status.setText(if (student.sent) R.string.scanner_saved else R.string.scanner_sending)
            row.statusIcon.setImageResource(if (student.sent) R.drawable.ic_check_circle else R.drawable.ic_schedule)
            row.statusIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@AttendanceScannerActivity, if (student.sent) R.color.scannerOk else R.color.scannerPending)
            )

            if (row.photo.tag == student.photo) return
            row.photo.tag = student.photo
            row.photo.setImageDrawable(null)
            val photo = student.photo ?: return
            lifecycleScope.launch {
                val bitmap = SiteIcons.load(this@AttendanceScannerActivity, photo, dp(PHOTO_SIZE_DP)) ?: return@launch
                if (row.photo.tag != photo) return@launch
                row.photo.setImageDrawable(RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true })
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val PREF_CAMERA_FACING = "camera_facing"

        private val SCANNER_PATH = Regex("/asistencias2/attendance_take/?$")
        private val CODE_PATTERN = Regex("^[A-Za-z0-9]{1,64}$")

        private const val LIST_REFRESH_MS = 1_000L
        private const val PAGE_READY_ATTEMPTS = 10
        private const val PAGE_READY_POLL_MS = 500L
        private const val FLUSH_ATTEMPTS = 10
        private const val FLUSH_POLL_MS = 400L
        private const val SAME_CODE_COOLDOWN_MS = 3_000L
        private const val MAX_LIST_FRACTION = 0.34f
        private const val ROW_HEIGHT_DP = 64
        private const val PHOTO_SIZE_DP = 44
        private const val FLASH_ALPHA = 0.35f
        private const val FLASH_MS = 450L
        private const val PILL_VISIBLE_MS = 1_600L
        private const val TONE_VOLUME = 80

        /** The site's attendance scanner page, which this activity replaces. */
        fun isScannerUrl(url: String): Boolean {
            val uri = Uri.parse(url)
            return uri.scheme == "https" && uri.host?.endsWith("u-cursos.cl") == true &&
                SCANNER_PATH.containsMatchIn(uri.path.orEmpty())
        }

        fun intent(context: Context, url: String): Intent =
            Intent(context, AttendanceScannerActivity::class.java).putExtra(EXTRA_URL, url)

        // The students present so far, in the page's order (it moves each new
        // one to the top), and whether the server already has each of them.
        // null until the page has registered its API endpoint (attendance.api).
        private const val LIST_JS = """
            (function() {
                if (!window.attendance || !attendance.api) return null;
                var students = [];
                document.querySelectorAll('ul.integrantes > li').forEach(function(li) {
                    if (getComputedStyle(li).display == 'none') return;
                    var id = (li.className.match(/username-(\w+)/) || [])[1];
                    if (!id) return;
                    var img = li.querySelector('img');
                    var h1 = li.querySelector('h1');
                    students.push({ id: id, name: h1 ? h1.textContent.trim() : '', photo: img ? img.src : '',
                        sent: li.classList.contains('enviado') });
                });
                return JSON.stringify({ students: students });
            })();
        """

        private const val PENDING_JS = """
            (function() {
                try { return (JSON.parse(localStorage.getItem(attendance.store)) || []).length; } catch (e) { return 0; }
            })();
        """

        // Hands a code to the page like its own scanner does, minus the page's
        // own feedback (a flash and message nobody sees here).
        private fun checkJs(code: String) = """
            (function(id) {
                if (!window.attendance || !attendance.api) return null;
                var u = $('.username-' + id + ', .rut-' + id);
                if (!u.length) return JSON.stringify({ status: 'unknown' });
                var status = u.is(':visible') ? 'again' : 'new';
                var name = u.find('h1').text().trim();
                var ok = attendance.ok, wrong = attendance.wrong;
                attendance.ok = attendance.wrong = function() {};
                try { attendance.check(id); } finally { attendance.ok = ok; attendance.wrong = wrong; }
                return JSON.stringify({ status: status, name: name });
            })(${JSONObject.quote(code)});
        """

        private fun unquote(value: String): String? =
            if (value == "null" || value.isEmpty()) null
            else runCatching { org.json.JSONArray("[$value]").getString(0) }.getOrNull()

        private suspend fun WebView.eval(js: String): String = suspendCancellableCoroutine { cont ->
            evaluateJavascript(js) { cont.resume(it ?: "null") }
        }
    }
}
