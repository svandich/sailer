package cl.erz.sailer.ui.theme

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import cl.erz.sailer.R

/**
 * Every color Sailer's own drawing uses, resolved from the current theme.
 * Code that paints (custom views, programmatic chips, pills) takes its colors
 * from here instead of hardcoding them or checking night mode, so that a
 * user-built theme (a few chosen colors, see plan.md "Theming") only has to
 * change what [of] returns.
 */
data class Palette(
    @ColorInt val primary: Int,
    @ColorInt val accent: Int,
    @ColorInt val onAccent: Int,
    @ColorInt val accentText: Int,
    @ColorInt val background: Int,
    @ColorInt val surface: Int,
    @ColorInt val onSurface: Int,
    @ColorInt val onSurfaceMuted: Int,
    @ColorInt val divider: Int,
    @ColorInt val stripe: Int,
    @ColorInt val warnContainer: Int,
    @ColorInt val onWarnContainer: Int,
    @ColorInt val now: Int,
) {
    /** Whether content sits on a dark background - decided by the colors, not by night mode. */
    val isDark: Boolean get() = ColorUtils.calculateLuminance(background) < 0.5

    /** [color] at [alpha] (0-1), for tints and highlights. */
    fun withAlpha(@ColorInt color: Int, alpha: Float): Int = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())

    companion object {
        fun of(context: Context): Palette {
            val theme = context.theme
            fun color(@AttrRes attr: Int): Int {
                val a = theme.obtainStyledAttributes(intArrayOf(attr))
                return try { a.getColor(0, 0xFF888888.toInt()) } finally { a.recycle() }
            }
            return Palette(
                primary = color(androidx.appcompat.R.attr.colorPrimary),
                accent = color(androidx.appcompat.R.attr.colorAccent),
                onAccent = color(R.attr.sailerOnAccent),
                accentText = color(R.attr.sailerAccentText),
                background = color(android.R.attr.windowBackground),
                surface = color(R.attr.sailerSurface),
                onSurface = color(R.attr.sailerOnSurface),
                onSurfaceMuted = color(R.attr.sailerOnSurfaceMuted),
                divider = color(R.attr.sailerDivider),
                stripe = color(R.attr.sailerStripe),
                warnContainer = color(R.attr.sailerWarnContainer),
                onWarnContainer = color(R.attr.sailerOnWarnContainer),
                now = color(R.attr.sailerNow),
            )
        }
    }
}
