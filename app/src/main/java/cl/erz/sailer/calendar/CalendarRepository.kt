package cl.erz.sailer.calendar

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

/**
 * Data for [cl.erz.sailer.ui.CalendarActivity], fetched with the WebView's
 * session cookies from a u-cursos.cl calendar ([base], e.g.
 * `https://www.u-cursos.cl/usuario/<id>/horario/`):
 *
 * - Every event, from the calendar's iCal export (`<base>ical`) - one request
 *   for all dates, kept on disk so the calendar opens instantly and offline.
 * - Per month, what only the site's own month page (`<base>mes?fecha=`)
 *   shows: holidays, whether "Agregar Evento" is offered, and the module's
 *   other tabs (Configuración, Coordinador).
 */
class CalendarRepository(private val context: Context, private val base: String) {

    /** The site answered with something other than the calendar: a logged-out page. */
    class NotLoggedInException : IOException("not logged in")

    class Page(val holidays: List<CalendarEvent>, val addEvent: Link?, val extraTabs: List<Link>)
    data class Link(val label: String, val url: String)

    private val dir = File(context.cacheDir, DISK_DIR)
    private val key = sha256(base)

    fun cachedEvents(): ICalendar.Parsed? =
        runCatching { File(dir, "$key.ics").takeIf { it.exists() }?.readText()?.let(ICalendar::parse) }.getOrNull()

    suspend fun fetchEvents(): ICalendar.Parsed = withContext(Dispatchers.IO) {
        val text = get("${base}ical", expectCalendar = true)
        val parsed = ICalendar.parse(text)
        dir.mkdirs()
        File(dir, "$key.ics").writeText(text)
        parsed
    }

    fun cachedPage(month: YearMonth): Page? =
        runCatching { File(dir, "$key-$month.json").takeIf { it.exists() }?.readText()?.let(::pageFromJson) }.getOrNull()

    suspend fun fetchPage(month: YearMonth): Page = withContext(Dispatchers.IO) {
        val html = get("${base}mes?fecha=${month.atDay(1)}", expectCalendar = false)
        val page = parsePage(html)
        dir.mkdirs()
        File(dir, "$key-$month.json").writeText(pageToJson(page))
        page
    }

    private fun parsePage(html: String): Page {
        val holidays = mutableListOf<CalendarEvent>()
        for (cell in DAY_CELL.findAll(html)) {
            val date = runCatching { LocalDate.parse(cell.groupValues[1]) }.getOrNull() ?: continue
            for (li in HOLIDAY.findAll(cell.groupValues[2])) {
                val name = text(li.groupValues[1]).ifEmpty { continue }
                holidays += CalendarEvent(
                    id = "holiday|$date|$name", start = date.atStartOfDay(), end = date.atStartOfDay(), allDay = true,
                    kind = CalendarEvent.KIND_HOLIDAY, title = name, rooms = null, source = null, url = null,
                )
            }
        }
        val addEvent = ADD_EVENT.find(html)?.let { Link(text(it.groupValues[2]), resolve(it.groupValues[1])) }
        val tabs = MODULE_TABS.find(html)?.groupValues?.get(1).orEmpty()
        val extraTabs = TAB_LINK.findAll(tabs)
            .map { Link(text(it.groupValues[2]), resolve(it.groupValues[1])) }
            .filter { link -> MODES.none { mode -> Uri.parse(link.url).path.orEmpty().trimEnd('/').endsWith("/$mode") } }
            .toList()
        return Page(holidays.distinctBy { it.id }, addEvent, extraTabs)
    }

    private fun resolve(href: String): String {
        val decoded = text(href)
        return if (decoded.startsWith("https://")) decoded else base + decoded.removePrefix("/")
    }

    private fun get(url: String, expectCalendar: Boolean): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = false
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()?.let { connection.setRequestProperty("User-Agent", it) }
        try {
            val code = connection.responseCode
            // An expired session redirects to /login (or shows the public frontpage).
            if (code in 300..399) throw NotLoggedInException()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            // The iCal export is UTF-8, the site's pages ISO-8859-1.
            val charset = CHARSET.find(connection.contentType.orEmpty())?.groupValues?.get(1)
                ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
            val body = connection.inputStream.use { it.readBytes() }.toString(charset)
            if (expectCalendar && !body.startsWith("BEGIN:VCALENDAR")) throw NotLoggedInException()
            if (!expectCalendar && "id=\"calendario\"" !in body) throw NotLoggedInException()
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun pageToJson(page: Page): String = JSONObject()
        .put("holidays", JSONArray(page.holidays.map { JSONObject().put("date", it.startDate.toString()).put("name", it.title) }))
        .put("addEvent", page.addEvent?.let { JSONObject().put("label", it.label).put("url", it.url) })
        .put("tabs", JSONArray(page.extraTabs.map { JSONObject().put("label", it.label).put("url", it.url) }))
        .toString()

    private fun pageFromJson(json: String): Page {
        val o = JSONObject(json)
        val holidays = o.getJSONArray("holidays").let { a ->
            (0 until a.length()).map { i ->
                val h = a.getJSONObject(i)
                val date = LocalDate.parse(h.getString("date"))
                val name = h.getString("name")
                CalendarEvent("holiday|$date|$name", date.atStartOfDay(), date.atStartOfDay(), true, CalendarEvent.KIND_HOLIDAY, name, null, null, null)
            }
        }
        fun link(l: JSONObject) = Link(l.getString("label"), l.getString("url"))
        val tabs = o.getJSONArray("tabs").let { a -> (0 until a.length()).map { link(a.getJSONObject(it)) } }
        return Page(holidays, o.optJSONObject("addEvent")?.let(::link), tabs)
    }

    companion object {
        private const val DISK_DIR = "calendar"
        private const val TIMEOUT_MS = 15_000

        val MODES = listOf("resumen", "agenda", "dia", "semana", "mes")

        // Month page (mes): each day cell is `<a href="dia?fecha=..." class="dia">..</a><ul>..</ul>`,
        // holidays being `<li class="amost warn">name</li>` (sic) in that list.
        private val DAY_CELL = Regex("""<a[^>]*href="dia\?fecha=(\d{4}-\d{2}-\d{2})"[^>]*>.*?</a>\s*<ul>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
        private val HOLIDAY = Regex("""<li class="a[l]?most warn">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        private val ADD_EVENT = Regex("""<a href="(evento[^"]*)" class="boton[^"]*">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val MODULE_TABS = Regex("""<ul class="modulo">(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
        private val CHARSET = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
        private val TAB_LINK = Regex("""<a href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        private fun text(html: String): String =
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

        fun clearCache(context: Context) {
            File(context.cacheDir, DISK_DIR).deleteRecursively()
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
