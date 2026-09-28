package cl.erz.sailer.calendar

import androidx.core.graphics.ColorUtils
import cl.erz.sailer.ui.theme.Palette
import java.util.zip.CRC32

/**
 * The site's `.rainbow.rainbow-<hue>` colors (focus / focus-dark themes):
 * text `hsl(h,30%,40%)` on `hsl(h,65%,90%)` when light, `hsl(h,35%,55%)` on
 * `hsl(h,25%,20%)` when dark. Like the site, a person's Horario colors blocks
 * by course and a course's Calendario by type; holidays use its `warn` look.
 */
class EventColors(palette: Palette, private val byCourse: Boolean) {

    class Pair(val background: Int, val text: Int, val border: Int)

    // The site's light/dark formulas, picked by the palette's background.
    private val dark = palette.isDark
    private val cache = HashMap<String, Pair>()

    private val holiday = Pair(palette.warnContainer, palette.onWarnContainer,
        ColorUtils.blendARGB(palette.warnContainer, palette.onWarnContainer, 0.2f))

    fun of(event: CalendarEvent): Pair {
        if (event.kind == CalendarEvent.KIND_HOLIDAY) return holiday
        val key = if (byCourse) event.courseUrl ?: event.source ?: event.kind else event.kind
        return cache.getOrPut(key) { forHue(hue(key)) }
    }

    /** The colors of a type's filter chip / pill. */
    fun ofKind(kind: String): Pair =
        if (kind == CalendarEvent.KIND_HOLIDAY) holiday else cache.getOrPut("kind|$kind") { forHue(hue(kind)) }

    private fun forHue(hue: Float): Pair = if (dark) Pair(
        hsl(hue, 0.25f, 0.20f), hsl(hue, 0.35f, 0.62f), hsl(hue - 10, 0.25f, 0.28f),
    ) else Pair(
        hsl(hue, 0.65f, 0.90f), hsl(hue, 0.30f, 0.36f), hsl(hue - 10, 0.35f, 0.78f),
    )

    private fun hsl(h: Float, s: Float, l: Float) = ColorUtils.HSLToColor(floatArrayOf((h + 360) % 360, s, l))

    private fun hue(key: String): Float = (CRC32().apply { update(key.toByteArray()) }.value % 360).toFloat()
}
