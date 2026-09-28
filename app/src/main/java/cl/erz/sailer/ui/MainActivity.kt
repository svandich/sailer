package cl.erz.sailer.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cl.erz.sailer.R
import cl.erz.sailer.auth.LoginResult
import cl.erz.sailer.auth.SecureCredentialStore
import cl.erz.sailer.auth.UCursosAuthenticator
import cl.erz.sailer.calendar.CalendarRepository
import cl.erz.sailer.databinding.ActivityMainBinding
import cl.erz.sailer.settings.AppSettings
import cl.erz.sailer.site.SiteIcons
import cl.erz.sailer.site.SiteMenu
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow

/**
 * Hosts the real u-cursos.cl site in a WebView, same as the original app.
 * The addition here: if the page fails to load (network error) or we get
 * bounced back to /login (expired session), it automatically retries the
 * login using the credentials [SecureCredentialStore] saved on a previous
 * successful login, with a few backed-off attempts, before giving up and
 * either showing an offline screen (network problem) or sending the user to
 * [LoginActivity] (the saved credentials themselves no longer work).
 *
 * Like the original app, navigation lives in a native left drawer: the site's
 * own top bar, side menu and footer are hidden ([HIDE_SITE_CHROME_JS]) and the
 * drawer is rebuilt from that side menu ([SiteMenu], [renderDrawer]) with the
 * site's own icons. The footer's theme/language pickers are replaced by
 * [SettingsActivity], whose choices are pushed onto the site (see
 * [syncSitePreferences]) since every relogin wipes the site's cookies and
 * session - and with them, anything picked through the site itself - and its
 * links by [AboutActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var credentialStore: SecureCredentialStore

    private var recoveryAttempts = 0
    private var isRecovering = false
    private var lastFailureWasNetwork = true
    private var loadingAnimation: Animator? = null

    // Site preference changes ("lang=en", "theme=focus-dark") already requested
    // from the site, so one it doesn't accept can't cause a reload loop.
    private val sitePreferenceRequests = mutableSetOf<String>()

    // The SiteMenu JSON the drawer currently shows, and the job loading its icons.
    private var drawerMenuJson: String? = null
    private var drawerIconsJob: Job? = null

    // Set once the user confirms "Salir": the next page load (the site's own
    // logout) ends in LoginActivity instead of triggering an automatic relogin.
    private var isLoggingOut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialStore = SecureCredentialStore(this)
        if (!credentialStore.hasSavedCredentials()) {
            goToLogin(sessionExpired = false)
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureDrawer()
        configureWebView()
        binding.retryButton.setOnClickListener {
            recoveryAttempts = 0
            hideOffline()
            loadHome()
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                binding.webView.canGoBack() -> binding.webView.goBack()
                else -> finish()
            }
        }

        loadHome()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from SettingsActivity (or the system's per-app language
        // screen): give changed preferences a fresh chance to reach the site.
        if (::binding.isInitialized && binding.webView.visibility == View.VISIBLE) {
            sitePreferenceRequests.clear()
            syncSitePreferences()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A site page picked elsewhere in the app (AboutActivity's links,
        // CalendarActivity's pages without a native version).
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        if (!::binding.isInitialized) return
        if (intent.getBooleanExtra(EXTRA_IN_WEBVIEW, false) && isUCursosUrl(url)) binding.webView.loadUrl(url)
        else openSiteUrl(url)
    }

    private fun configureDrawer() {
        setSupportActionBar(binding.toolbar)
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // The site's icons keep their own colors; glyphs and the app's own
        // icons are tinted individually instead.
        binding.navigationView.itemIconTintList = null
        // Room for rowTitle()'s second line.
        binding.navigationView.itemMaxLines = 2
        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            drawerActions[item.itemId]?.invoke()
            item.isCheckable
        }
        // Until the first page tells us otherwise, show the menu seen last time.
        drawerMenuJson = SiteMenu.readCache(this)
        renderDrawer(drawerMenuJson?.let(SiteMenu::parse))
    }

    private val drawerActions = mutableMapOf<Int, () -> Unit>()

    /** A menu extracted from the current page (see [SiteMenu.EXTRACT_JS]). */
    private fun onSiteMenu(json: String) {
        if (json == drawerMenuJson || isLoggingOut) return
        val site = SiteMenu.parse(json) ?: return
        drawerMenuJson = json
        SiteMenu.writeCache(this, json)
        renderDrawer(site)
    }

    /**
     * Rebuilds the drawer: Inicio and the site's search/share/reload, then the
     * site's own lists (Favoritos, the current semester's courses,
     * Comunidades, Instituciones), then Contacto, Ajustes, Acerca and Salir.
     * Without a [site] menu yet, just the app's own rows.
     */
    private fun renderDrawer(site: SiteMenu?) {
        val menu = binding.navigationView.menu
        menu.clear()
        drawerActions.clear()
        drawerIconsJob?.cancel()
        val pendingIcons = mutableListOf<Pair<MenuItem, SiteMenu.Row>>()
        var nextId = 1

        fun add(to: Menu, group: Int, title: CharSequence, @androidx.annotation.DrawableRes icon: Int, action: () -> Unit): MenuItem {
            val id = nextId++
            drawerActions[id] = action
            return to.add(group, id, Menu.NONE, title).apply { if (icon != 0) setIcon(icon) }
        }

        fun addRow(to: Menu, group: Int, row: SiteMenu.Row, @androidx.annotation.DrawableRes fallbackIcon: Int = 0, action: () -> Unit): MenuItem =
            add(to, group, rowTitle(row), fallbackIcon, action).also { item ->
                if (row.icon != null) {
                    // Hold the icon's space so rows don't shift once it loads.
                    if (item.icon == null) item.icon = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    pendingIcons += item to row
                }
            }

        val actions = site?.actions.orEmpty()
        fun action(kind: String) = actions.firstOrNull { it.kind == kind }

        add(menu, GROUP_TOP, getString(R.string.nav_home), R.drawable.ic_home) { loadHome() }
        action(SiteMenu.KIND_SEARCH)?.let { row -> addRow(menu, GROUP_TOP, row, R.drawable.ic_search) { showSearch(row) } }
        action(SiteMenu.KIND_SHARE)?.let { row -> addRow(menu, GROUP_TOP, row) { shareCurrentPage() } }
        action(SiteMenu.KIND_RELOAD)?.let { row -> addRow(menu, GROUP_TOP, row) { binding.webView.reload() } }

        var selected: MenuItem? = null
        for (section in site?.sections.orEmpty()) {
            val subMenu = menu.addSubMenu(section.title)
            for (row in section.rows) {
                val item = addRow(subMenu, Menu.NONE, row) { row.href?.let(::openSiteUrl) }
                item.isCheckable = true
                if (row.isSelected) selected = item
            }
        }

        actions.filter { it.kind == SiteMenu.KIND_LINK }.forEach { row ->
            addRow(menu, GROUP_BOTTOM, row) { row.href?.let(::openSiteUrl) }
        }
        add(menu, GROUP_BOTTOM, getString(R.string.nav_settings), R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        add(menu, GROUP_BOTTOM, getString(R.string.nav_about), R.drawable.ic_about) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        val logout = action(SiteMenu.KIND_LOGOUT)
        if (logout != null) {
            addRow(menu, GROUP_BOTTOM, logout, R.drawable.ic_logout) { confirmLogout(logout.href) }
        } else {
            add(menu, GROUP_BOTTOM, getString(R.string.nav_logout), R.drawable.ic_logout) { confirmLogout(null) }
        }
        selected?.let { binding.navigationView.setCheckedItem(it) }

        val header = binding.navigationView.getHeaderView(0)
        val user = site?.user
        val avatar = header.findViewById<ImageView>(R.id.drawerAvatar)
        header.findViewById<TextView>(R.id.drawerName).text = user?.name ?: getString(R.string.app_name)
        header.findViewById<TextView>(R.id.drawerUsername).text = credentialStore.read()?.username
        if (user != null) {
            header.setOnClickListener {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                openSiteUrl(user.href)
            }
        } else {
            header.setOnClickListener(null)
            header.isClickable = false
        }
        if (user?.avatar == null) avatar.visibility = View.GONE

        val iconSize = dp(ICON_SIZE_DP)
        val glyphTint = themeColor(androidx.appcompat.R.attr.colorControlNormal)
        drawerIconsJob = lifecycleScope.launch {
            for ((item, row) in pendingIcons) launch {
                val bitmap = SiteIcons.load(this@MainActivity, row.icon ?: return@launch, iconSize) ?: return@launch
                item.icon = BitmapDrawable(resources, bitmap).apply { if (row.isGlyph) setTintList(glyphTint) }
            }
            user?.avatar?.let { url ->
                launch {
                    val bitmap = SiteIcons.load(this@MainActivity, url, dp(AVATAR_SIZE_DP)) ?: return@launch
                    avatar.setImageDrawable(RoundedBitmapDrawableFactory.create(resources, bitmap).apply { isCircular = true })
                    avatar.visibility = View.VISIBLE
                }
            }
        }
    }

    // A row's label, plus its secondary line (course code, community year)
    // smaller and dimmed below it, as the site shows them.
    private fun rowTitle(row: SiteMenu.Row): CharSequence {
        if (row.sub.isEmpty()) return row.label
        return SpannableStringBuilder(row.label).append("\n").apply {
            val start = length
            append(row.sub)
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            themeColor(android.R.attr.textColorSecondary)?.let {
                setSpan(ForegroundColorSpan(it.defaultColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun openSiteUrl(url: String) {
        if (AttendanceScannerActivity.isScannerUrl(url)) startActivity(AttendanceScannerActivity.intent(this, url))
        else if (CalendarActivity.isCalendarUrl(url)) startActivity(CalendarActivity.intent(this, url))
        else if (isUCursosUrl(url)) binding.webView.loadUrl(url)
    }

    // The site's search box (#widget_buscador), as a dialog.
    private fun showSearch(row: SiteMenu.Row) {
        val action = row.href ?: return
        val input = EditText(this).apply {
            hint = row.hint
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        fun search() {
            val query = input.text.toString().trim()
            if (query.isNotEmpty()) {
                openSiteUrl(Uri.parse(action).buildUpon().appendQueryParameter("q", query).build().toString())
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(row.label)
            .setView(container)
            .setPositiveButton(row.label) { _, _ -> search() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, event ->
            // The keyboard's search key, or Enter on a hardware keyboard.
            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId != EditorInfo.IME_ACTION_SEARCH && !isEnter) return@setOnEditorActionListener false
            search()
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    // The site's "Compartir" link is the current page's permalink.
    private fun shareCurrentPage() {
        val url = binding.webView.url ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .putExtra(Intent.EXTRA_SUBJECT, binding.webView.title)
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
    }

    private fun confirmLogout(siteLogoutUrl: String?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.nav_logout) { _, _ -> logout(siteLogoutUrl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Unlike an expired session, this must not be recovered from: forget the
     * saved credentials first, then end the session on the site too (its
     * logout link carries a CSRF token). AppSettings are kept, as always.
     */
    private fun logout(siteLogoutUrl: String?) {
        logoutUsername = credentialStore.read()?.username
        credentialStore.clear()
        SiteMenu.clearCache(this)
        SiteIcons.clearDiskCache(this)
        CalendarRepository.clearCache(this)
        if (siteLogoutUrl == null || !isUCursosUrl(siteLogoutUrl)) {
            finishLogout()
            return
        }
        isLoggingOut = true
        showLoading()
        binding.webView.loadUrl(siteLogoutUrl)
    }

    private var logoutUsername: String? = null

    // A page's camera request (see pageChromeClient) waiting on the system's
    // camera permission dialog.
    private var pendingCameraRequest: PermissionRequest? = null
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingCameraRequest ?: return@registerForActivityResult
        pendingCameraRequest = null
        if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny()
    }

    /**
     * Lets u-cursos.cl pages use the camera through the web camera API, as the
     * attendance page's QR scanner does (asistencias2/attendance_take). Only
     * video is ever granted, and only to the site itself; the app's own camera
     * permission is asked for the first time a page needs it.
     */
    private val pageChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources || !isUCursosUrl(request.origin.toString())) {
                request.deny()
                return
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                pendingCameraRequest?.deny()
                pendingCameraRequest = request
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingCameraRequest == request) pendingCameraRequest = null
        }
    }

    private fun finishLogout() {
        CookieManager.getInstance().removeAllCookies(null)
        goToLogin(sessionExpired = false, username = logoutUsername)
    }

    private fun themeColor(attr: Int): android.content.res.ColorStateList? {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return null
        return if (value.resourceId != 0) ContextCompat.getColorStateList(this, value.resourceId)
        else android.content.res.ColorStateList.valueOf(value.data)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = mainWebViewClient()
        webView.webChromeClient = pageChromeClient

        // Hiding the site's chrome before the page is even parsed avoids a flash
        // of it on every navigation; onPageCommitVisible/onPageFinished repeat
        // it for WebViews without document-start scripts.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, HIDE_SITE_CHROME_JS, SITE_ORIGINS)
        }
        // Receives SiteMenu.EXTRACT_JS's result; only u-cursos.cl pages get the
        // `sailerBridge` object at all.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "sailerBridge", SITE_ORIGINS) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let(::onSiteMenu)
            }
        }
    }

    private fun mainWebViewClient(): WebViewClient = object : WebViewClient() {
        // The attendance page's "Comenzar!" opens the native QR scanner
        // instead of the site's in-page one, and calendar views (Horario, a
        // course's Calendario) open natively too.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!request.isForMainFrame) return false
            when {
                AttendanceScannerActivity.isScannerUrl(url) -> startActivity(AttendanceScannerActivity.intent(this@MainActivity, url))
                CalendarActivity.isCalendarUrl(url) -> startActivity(CalendarActivity.intent(this@MainActivity, url))
                else -> return false
            }
            return true
        }

        // A <style> added this early also applies to the rest of the page as it's parsed.
        override fun onPageCommitVisible(view: WebView, url: String) {
            if (isUCursosUrl(url)) view.evaluateJavascript(HIDE_SITE_CHROME_JS, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (isLoggingOut) {
                finishLogout()
                return
            }
            if (LOGIN_PATH in url) {
                handleRecoverableFailure(networkRelated = false)
                return
            }
            if (!isUCursosUrl(url)) {
                recoveryAttempts = 0
                showContent()
                return
            }
            view.evaluateJavascript(HIDE_SITE_CHROME_JS, null)
            // An expired session doesn't always redirect to /login: u-cursos.cl's
            // root just renders the public (logged-out) frontpage instead. So
            // inspect the page itself before accepting it as a logged-in view.
            view.evaluateJavascript(LOGGED_OUT_CHECK_JS) { value ->
                // Ignore stale results from a page we've since navigated away
                // from, or that arrive while a recovery login is in progress.
                if (isRecovering || view.url != url) return@evaluateJavascript
                if (value == "true") {
                    handleRecoverableFailure(networkRelated = false)
                } else {
                    recoveryAttempts = 0
                    showContent()
                    syncSitePreferences()
                    view.evaluateJavascript(SiteMenu.EXTRACT_JS, null)
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && isLoggingOut) finishLogout()
            else if (request.isForMainFrame) handleRecoverableFailure(networkRelated = true)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                handleRecoverableFailure(networkRelated = true)
            }
        }
    }

    private fun loadHome() {
        showLoading()
        // The theme is just a cookie, so it can be set up front, avoiding a
        // reload through syncSitePreferences() (the language can't: it lives in
        // the server-side session).
        CookieManager.getInstance().setCookie(
            HOME_URL, "theme=${AppSettings.siteTheme(this)}; path=/; max-age=$THEME_COOKIE_MAX_AGE_S"
        )
        binding.webView.loadUrl(HOME_URL)
    }

    private fun isUCursosUrl(url: String): Boolean = Uri.parse(url).host?.endsWith(UCURSOS_HOST) == true

    /**
     * Makes the site's own theme/language (`kernel.theme` / `kernel.lang`)
     * match [AppSettings], by navigating the current page to the site's own
     * switch URLs - the same ones the hidden footer pickers link to. Changes
     * one at a time; the reload lands back in onPageFinished, which calls this
     * again for the next one.
     */
    private fun syncSitePreferences() {
        val webView = binding.webView
        val url = webView.url ?: return
        if (isRecovering || !isUCursosUrl(url)) return
        webView.evaluateJavascript(SITE_PREFERENCES_JS) { value ->
            if (isRecovering || webView.url != url) return@evaluateJavascript
            val current = value.trim('"').split('|')
            if (current.size != 3) return@evaluateJavascript
            val (siteLanguage, siteTheme, offersDefaultLanguage) = current
            val language = AppSettings.siteLanguage(this)
            val theme = AppSettings.siteTheme(this)

            val base = Uri.parse(url).buildUpon()
            val target = when {
                siteLanguage != language && sitePreferenceRequests.add("lang=$language") ->
                    base.appendQueryParameter("_hook", "lang").appendQueryParameter("lang", language)
                siteTheme != theme && sitePreferenceRequests.add("theme=$theme") ->
                    base.appendQueryParameter("theme", theme)
                // Accept the site's offer to keep this language as the account
                // default ("lang=!" = the session's current one) - once per
                // choice, as the site offers it again after every switch.
                siteLanguage == language && offersDefaultLanguage == "1" &&
                    AppSettings.siteDefaultLanguage(this) != language &&
                    sitePreferenceRequests.add("lang=!$language") -> {
                    AppSettings.setSiteDefaultLanguage(this, language)
                    base.appendQueryParameter("_hook", "lang").appendQueryParameter("lang", "!")
                }
                else -> {
                    // Already saved: drop the now-pointless offer from the page.
                    if (offersDefaultLanguage == "1") webView.evaluateJavascript(HIDE_DEFAULT_LANGUAGE_OFFER_JS, null)
                    return@evaluateJavascript
                }
            }.build().toString()

            showLoading()
            // Navigate from inside the page rather than with loadUrl(): the
            // language switch is only honored with a Referer, which this sends.
            webView.evaluateJavascript("location.href = ${JSONObject.quote(target)};", null)
        }
    }

    private fun handleRecoverableFailure(networkRelated: Boolean) {
        if (isRecovering || isLoggingOut) return
        lastFailureWasNetwork = networkRelated

        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            if (lastFailureWasNetwork) showOffline() else goToLogin(sessionExpired = true)
            return
        }

        val credentials = credentialStore.read()
        if (credentials == null) {
            goToLogin(sessionExpired = true)
            return
        }

        isRecovering = true
        recoveryAttempts++
        showLoading()

        lifecycleScope.launch {
            kotlinx.coroutines.delay(backoffDelayMs(recoveryAttempts))
            val result = UCursosAuthenticator(binding.webView).login(credentials.username, credentials.password)
            isRecovering = false
            // UCursosAuthenticator takes over webView's client while it drives the
            // login; hand control back to MainActivity's own client before doing
            // anything else with the WebView.
            binding.webView.webViewClient = mainWebViewClient()
            when (result) {
                is LoginResult.Success -> {
                    // A new session starts from the site's defaults again.
                    sitePreferenceRequests.clear()
                    loadHome()
                }
                is LoginResult.NetworkError -> handleRecoverableFailure(networkRelated = true)
                is LoginResult.InvalidCredentials -> goToLogin(sessionExpired = true)
                is LoginResult.Unexpected -> goToLogin(sessionExpired = true)
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long =
        (INITIAL_BACKOFF_MS * 2.0.pow(attempt - 1)).toLong().let { min(it, MAX_BACKOFF_MS) }

    private fun goToLogin(sessionExpired: Boolean, username: String? = credentialStore.read()?.username) {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_SESSION_EXPIRED, sessionExpired)
                .putExtra(LoginActivity.EXTRA_PREFILL_USERNAME, username)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    // The WebView is kept hidden (INVISIBLE, not GONE, so it stays laid out and
    // UCursosAuthenticator can still drive it) until a page is confirmed to be
    // a logged-in view, so the logged-out frontpage and the relogin flow never
    // flash on screen.
    private fun showLoading() {
        binding.webView.visibility = View.INVISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.offlineContainer.visibility = View.GONE
        startLoadingAnimation()
    }

    private fun showContent() {
        stopLoadingAnimation()
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun showOffline() {
        stopLoadingAnimation()
        binding.loadingIndicator.visibility = View.GONE
        binding.offlineContainer.visibility = View.VISIBLE
    }

    // A sailboat rocking and bobbing on a strip of waves that scrolls sideways.
    private fun startLoadingAnimation() {
        if (loadingAnimation != null) return
        val boat = binding.loadingBoat
        val waves = binding.loadingWaves
        val density = resources.displayMetrics.density
        boat.post {
            boat.pivotX = boat.width / 2f
            boat.pivotY = boat.height.toFloat()
        }

        fun ObjectAnimator.swing(durationMs: Long) = apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        val rock = ObjectAnimator.ofFloat(boat, View.ROTATION, -BOAT_ROCK_DEGREES, BOAT_ROCK_DEGREES).swing(1_400L)
        val bob = ObjectAnimator.ofFloat(boat, View.TRANSLATION_Y, 0f, BOAT_BOB_DP * density).swing(900L)
        // Scrolling by exactly one wave period (see loading_waves.xml) loops seamlessly.
        val sail = ObjectAnimator.ofFloat(waves, View.TRANSLATION_X, 0f, -WAVE_PERIOD_DP * density).apply {
            duration = 1_200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        loadingAnimation = AnimatorSet().apply {
            playTogether(rock, bob, sail)
            start()
        }
    }

    private fun stopLoadingAnimation() {
        loadingAnimation?.cancel()
        loadingAnimation = null
    }

    override fun onDestroy() {
        stopLoadingAnimation()
        super.onDestroy()
    }

    private fun hideOffline() {
        binding.offlineContainer.visibility = View.GONE
    }

    companion object {
        private const val HOME_URL = "https://www.u-cursos.cl/"
        private const val LOGIN_PATH = "/login"
        private const val UCURSOS_HOST = "u-cursos.cl"

        // Logged-out heuristic: the page offers a way to log in (a link/form
        // pointing at /login or the Cuenta Uchile auth endpoint, or a password
        // field) and no way to log out.
        // Requiring the absence of a logout link keeps logged-in pages that
        // happen to mention /login from being misdetected.
        private const val LOGGED_OUT_CHECK_JS = """
            (function() {
                var els = document.querySelectorAll('a[href], form[action]');
                var hasLogin = false, hasLogout = false;
                for (var i = 0; i < els.length; i++) {
                    var t = (els[i].getAttribute('href') || els[i].getAttribute('action') || '').toLowerCase();
                    if (t.indexOf('logout') >= 0 || t.indexOf('/salir') >= 0) hasLogout = true;
                    // The logged-out frontpage's "Entrar con Cuenta Uchile"
                    // button is a form posting to /b/auth/api.
                    else if (t.indexOf('/login') >= 0 || t.indexOf('/auth/api') >= 0) hasLogin = true;
                }
                if (hasLogout) return false;
                return hasLogin || document.querySelector('input[type=password]') !== null;
            })();
        """

        // u-cursos.cl exposes its current settings on every page as a global
        // `kernel` object. After a language switch the site also offers, in a
        // #maviso banner, to make that language the account's default (a link
        // to `?_hook=lang&lang=!`); the third field says whether it's showing.
        private const val SITE_PREFERENCES_JS =
            "(window.kernel ? kernel.lang + '|' + kernel.theme + '|' + " +
                "(document.querySelector('#maviso a[href*=\"lang=!\"]') ? 1 : 0) : '')"
        private const val HIDE_DEFAULT_LANGUAGE_OFFER_JS =
            "(function() { var a = document.querySelector('#maviso a[href*=\"lang=!\"]');" +
                " if (!a) return; var box = a.closest('#maviso'); a.closest('li').remove();" +
                " if (!box.querySelector('li')) box.remove(); })();"
        private const val THEME_COOKIE_MAX_AGE_S = 31_536_000 // one year, same as the site's

        const val EXTRA_URL = "extra_url"
        // With EXTRA_URL: show it in the WebView even if it has a native screen.
        const val EXTRA_IN_WEBVIEW = "extra_in_webview"

        private val SITE_ORIGINS = setOf("https://www.u-cursos.cl", "https://u-cursos.cl")

        // The drawer replaces the site's red top bar (#header, with its #toggler
        // hamburger), its side menu (#menu) and the blur it lays over the page
        // while that menu is open (#navigation::after); AboutActivity and
        // SettingsActivity replace its footer (#footer). The page's first block
        // then no longer needs the margin that cleared the fixed top bar.
        private const val HIDE_SITE_CHROME_JS = """
            (function() {
                if (document.getElementById('sailer-style')) return;
                var s = document.createElement('style');
                s.id = 'sailer-style';
                s.textContent = '#header, #toggler, #menu, #footer, #navigation::after { display: none !important; }' +
                    ' #navigation-wrapper > :first-child { margin-top: 0 !important; }';
                (document.head || document.documentElement).appendChild(s);
            })();
        """

        private const val GROUP_TOP = 1
        private const val GROUP_BOTTOM = 2
        private const val ICON_SIZE_DP = 24
        private const val AVATAR_SIZE_DP = 56

        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 10_000L

        private const val BOAT_ROCK_DEGREES = 8f
        private const val BOAT_BOB_DP = 4f
        private const val WAVE_PERIOD_DP = 40f
    }
}
