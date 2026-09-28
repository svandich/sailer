package cl.erz.sailer.ui.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import cl.erz.sailer.calendar.CalendarEvent
import cl.erz.sailer.calendar.EventColors
import cl.erz.sailer.ui.theme.Palette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * The site's Mes view (`table#calendario`) sized to the screen: Monday-first
 * weeks, each day listing as many of its events ("10:15 IQ2212") as fit and
 * a "+N" for the rest. Tapping a day opens it in the Día view.
 */
class MonthGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onDayClick(date: LocalDate)
        fun onSwipe(direction: Int)
    }

    var listener: Listener? = null

    private var month: YearMonth = YearMonth.now()
    private var events: List<CalendarEvent> = emptyList()
    private var colors: EventColors? = null
    private var byCourse = true
    private var today: LocalDate = LocalDate.now()
    private var locale: Locale = Locale.getDefault()
    private var more: (Int) -> String = { "+$it" }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val headerHeight = dp(24f)
    private val numberHeight = dp(20f)
    private val barHeight = dp(15f)

    private val palette = Palette.of(context)
    private val textColor = palette.onSurface
    private val secondaryColor = palette.onSurfaceMuted
    private val accent = palette.accent

    private val linePaint = Paint().apply { color = palette.divider; strokeWidth = 1f }
    // The site's `td.finde` / `td.out` shading.
    private val weekendPaint = Paint().apply { color = palette.stripe }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * scaled; color = secondaryColor; textAlign = Paint.Align.CENTER }
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * scaled; textAlign = Paint.Align.CENTER }
    private val barPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * scaled }
    private val morePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * scaled; color = secondaryColor }

    private val firstDay: LocalDate get() = month.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
    private val weeks: Int get() {
        val last = month.atEndOfMonth()
        return ((last.toEpochDay() - firstDay.toEpochDay()) / 7 + 1).toInt()
    }

    fun setData(month: YearMonth, events: List<CalendarEvent>, colors: EventColors, byCourse: Boolean, today: LocalDate, locale: Locale, more: (Int) -> String) {
        this.month = month
        this.colors = colors
        this.byCourse = byCourse
        this.today = today
        this.locale = locale
        this.more = more
        val from = firstDay
        val to = from.plusDays(weeks * 7L - 1)
        this.events = events.filter { it.overlaps(from, to) }
            // Holidays first, then all-day events, then by time.
            .sortedWith(compareBy({ it.kind != CalendarEvent.KIND_HOLIDAY }, { !it.allDay }, { it.start }))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cellW = width / 7f
        val rowH = (height - headerHeight) / weeks

        for (i in 0 until 7) {
            val name = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) }
            canvas.drawText(name, cellW * i + cellW / 2, headerHeight - dp(7f), headerPaint)
        }
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, linePaint)

        for (w in 0 until weeks) {
            val top = headerHeight + w * rowH
            for (d in 0 until 7) {
                val date = firstDay.plusDays(w * 7L + d)
                val left = d * cellW
                val inMonth = YearMonth.from(date) == month
                if (d >= 5 || !inMonth) canvas.drawRect(left, top, left + cellW, top + rowH, weekendPaint)
                drawDay(canvas, date, inMonth, left, top, cellW, rowH)
                if (d > 0) canvas.drawLine(left, top, left, top + rowH, linePaint)
            }
            canvas.drawLine(0f, top + rowH, width.toFloat(), top + rowH, linePaint)
        }
    }

    private fun drawDay(canvas: Canvas, date: LocalDate, inMonth: Boolean, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w / 2
        if (date == today) {
            fillPaint.color = accent
            canvas.drawCircle(cx, top + numberHeight / 2 + dp(1f), dp(9f), fillPaint)
            numberPaint.color = palette.onAccent
            numberPaint.typeface = Typeface.DEFAULT_BOLD
        } else {
            numberPaint.color = if (inMonth) textColor else secondaryColor
            numberPaint.alpha = if (inMonth) 255 else 120
            numberPaint.typeface = Typeface.DEFAULT
        }
        canvas.drawText(date.dayOfMonth.toString(), cx, top + numberHeight / 2 + numberPaint.textSize * 0.4f, numberPaint)
        numberPaint.alpha = 255

        val dayEvents = events.filter { it.occursOn(date) }
        if (dayEvents.isEmpty()) return
        val space = h - numberHeight - dp(2f)
        val fit = max(0, (space / barHeight).toInt())
        val shown = if (dayEvents.size <= fit) dayEvents.size else max(0, fit - 1)
        val pad = dp(1.5f)
        for (i in 0 until shown) {
            val event = dayEvents[i]
            val c = colors?.of(event) ?: continue
            val rect = RectF(left + pad, top + numberHeight + i * barHeight, left + w - pad, top + numberHeight + (i + 1) * barHeight - dp(1f))
            fillPaint.color = c.background
            canvas.drawRoundRect(rect, dp(3f), dp(3f), fillPaint)
            barPaint.color = c.text
            val text = label(event, rect.width() - dp(4f))
            canvas.drawText(text, 0, text.length, rect.left + dp(2f), rect.centerY() + barPaint.textSize * 0.35f, barPaint)
        }
        if (shown < dayEvents.size) {
            val y = top + numberHeight + shown * barHeight + barHeight / 2 + morePaint.textSize * 0.35f
            canvas.drawText(more(dayEvents.size - shown), left + dp(3f), y, morePaint)
        }
    }

    // The site lists "10:15 Termodinámica Química"; a cell fits the course
    // code (or type, in a course's own calendar), and the time only if there's room.
    private fun label(event: CalendarEvent, width: Float): CharSequence {
        val name = when {
            event.allDay || !event.isSchedule -> event.title
            byCourse -> event.courseCode ?: event.title
            else -> event.kind
        }
        if (!event.allDay) {
            val withTime = "${event.start.toLocalTime()} $name"
            if (barPaint.measureText(withTime) <= width) return withTime
        }
        return TextUtils.ellipsize(name, barPaint, width, TextUtils.TruncateAt.END)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headerHeight) return false
            val rowH = (height - headerHeight) / weeks
            val w = ((e.y - headerHeight) / rowH).toInt().coerceIn(0, weeks - 1)
            val d = (e.x / (width / 7f)).toInt().coerceIn(0, 6)
            listener?.onDayClick(firstDay.plusDays(w * 7L + d))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) < dp(60f) || abs(dx) < abs(dy) * 1.5f) return false
            listener?.onSwipe(if (dx < 0) 1 else -1)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && handled) performClick()
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
