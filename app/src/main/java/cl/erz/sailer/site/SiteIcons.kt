package cl.erz.sailer.site

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Base64
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Loads [SiteMenu] icons as square bitmaps: `data:` URLs (the page-rendered
 * glyphs) are decoded directly; anything else is fetched once over https and
 * kept on disk - the site's icon URLs are versioned (`..._v34709.svg`) or
 * content-addressed (avatars), so they never need refreshing.
 */
object SiteIcons {

    private const val DISK_DIR = "site_icons"
    private const val TIMEOUT_MS = 10_000

    private val memory = LruCache<String, Bitmap>(64)

    suspend fun load(context: Context, source: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$sizePx|$source"
        memory.get(key)?.let { return@withContext it }
        val bytes = when {
            source.startsWith("data:") -> runCatching { Base64.decode(source.substringAfter(','), Base64.DEFAULT) }.getOrNull()
            source.startsWith("https://") -> fetch(context, source)
            else -> null
        } ?: return@withContext null
        runCatching { decode(bytes, sizePx) }.getOrNull()?.also { memory.put(key, it) }
    }

    fun clearDiskCache(context: Context) {
        File(context.cacheDir, DISK_DIR).deleteRecursively()
    }

    private fun fetch(context: Context, url: String): ByteArray? {
        val file = File(File(context.cacheDir, DISK_DIR), sha256(url))
        if (file.exists()) return file.readBytes()
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()?.also { bytes ->
            file.parentFile?.mkdirs()
            runCatching { file.writeBytes(bytes) }
        }
    }

    private fun decode(bytes: ByteArray, sizePx: Int): Bitmap? {
        val head = String(bytes, 0, minOf(bytes.size, 512)).trimStart()
        return if (head.startsWith("<")) renderSvg(bytes, sizePx) else scaleToSquare(bytes, sizePx)
    }

    private fun renderSvg(bytes: ByteArray, sizePx: Int): Bitmap {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
            svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
        }
        // Otherwise a fixed width/height (e.g. the role icons' 100x100) is
        // drawn at that many pixels, cropped to the bitmap, instead of scaled.
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
        return bitmap
    }

    // Center-cropped, like the site's round avatar.
    private fun scaleToSquare(bytes: ByteArray, sizePx: Int): Bitmap? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
