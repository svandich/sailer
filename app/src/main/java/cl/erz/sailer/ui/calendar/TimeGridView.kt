package cl.erz.sailer.ui.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
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
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The site's Semana/Día grid (`table.dhorario`), drawn natively so every day
 * fits the screen's width: a column per day (empty weekend days narrower), an
 * all-day strip for holidays and all-day events, and hour rows stretched to
 * fill [viewportHeight] - only when that would make them too short does the
 * grid grow taller than the screen (it sits in a vertical scroll view).
 */
class TimeGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onEventClick(event: CalendarEvent)
        fun onDayClick(date: LocalDate)
        fun onSwipe(direction: Int)
    }

    var listener: Listener? = null

    /** Height of the visible area; the hours are stretched to fill it. */
    var viewportHeight: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var days: List<LocalDate> = emptyList()
    private var timed: List<CalendarEvent> = emptyList()
    private var allDay: List<CalendarEvent> = emptyList()
    private var colors: EventColors? = null
    private var byCourse = true
    private var today: LocalDate = LocalDate.now()
    private var locale: Locale = Locale.getDefault()
    private var firstHour = DEFAULT_FIRST_HOUR
    private var lastHour = DEFAULT_LAST_HOUR

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val gutter = dp(28f)
    private val headerHeight = dp(40f)
    private val allDayRowHeight = dp(18f)
    private val minHourHeight = dp(34f)
    private val radius = dp(3f)

    // Computed by layoutGrid().
    private var hourHeight = minHourHeight
    private var allDayRows = 0
    private val columnX = mutableListOf<Float>()
    private val columnW = mutableListOf<Float>()
    private val blocks = mutableListOf<Block>()
    private val allDayBlocks = mutableListOf<Block>()

    private class Block(val event: CalendarEvent, val rect: RectF, val lines: List<StaticLayout>)

    private val palette = Palette.of(context)
    private val textColor = palette.onSurface
    private val secondaryColor = palette.onSurfaceMuted
    private val accent = palette.accent

    private val linePaint = Paint().apply { color = palette.divider; strokeWidth = 1f }
    private val weekendPaint = Paint().apply { color = palette.stripe }
    private val todayPaint = Paint().apply { color = palette.withAlpha(accent, 0.08f) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.now; strokeWidth = dp(1.5f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowLabelRect = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val hourPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * scaled; color = secondaryColor }
    private val dayNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * scaled; color = secondaryColor; textAlign = Paint.Align.CENTER }
    private val dayNumberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15 * scaled; color = textColor; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val nowLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9 * scaled; color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }

    fun setData(
        days: List<LocalDate>,
        events: List<CalendarEvent>,
        colors: EventColors,
        byCourse: Boolean,
        today: LocalDate,
        locale: Locale,
    ) {
        this.days = days
        this.colors = colors
        this.byCourse = byCourse
        this.today = today
        this.locale = locale
        val first = days.firstOrNull()
        val last = days.lastOrNull()
        val inRange = if (first == null || last == null) emptyList() else events.filter { it.overlaps(first, last) }
        allDay = inRange.filter { it.allDay }
        timed = inRange.filter { !it.allDay }
        val starts = timed.map { if (it.startDate.isBefore(first)) 0 else it.start.hour }
        val ends = timed.map { if (it.endDate.isAfter(last) || it.end.toLocalDate().isAfter(it.startDate)) 24 else it.end.hour + if (it.end.minute > 0) 1 else 0 }
        firstHour = min(DEFAULT_FIRST_HOUR, starts.minOrNull() ?: DEFAULT_FIRST_HOUR)
        lastHour = max(DEFAULT_LAST_HOUR, ends.maxOrNull() ?: DEFAULT_LAST_HOUR).coerceAtMost(24)
        allDayRows = days.maxOfOrNull { day -> allDay.count { it.occursOn(day) } }?.coerceAtMost(MAX_ALL_DAY_ROWS) ?: 0
        requestLayout()
        invalidate()
    }

    private val hours get() = lastHour - firstHour
    private val allDayHeight get() = if (allDayRows == 0) 0f else allDayRows * allDayRowHeight + dp(4f)
    private val gridTop get() = headerHeight + allDayHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (if (viewportHeight > 0) viewportHeight else MeasureSpec.getSize(heightMeasureSpec)).toFloat()
        hourHeight = max(minHourHeight, (available - gridTop) / max(1, hours))
        val height = max(available, gridTop + hourHeight * hours)
        setMeasuredDimension(width, ceil(height).toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutGrid()
    }

    private fun layoutGrid() {
        columnX.clear()
        columnW.clear()
        blocks.clear()
        allDayBlocks.clear()
        if (days.isEmpty() || width == 0) return

        // Weekends without anything on them take less room, as they're rarely used.
        val weights = days.map { day ->
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
            if (days.size > 1 && weekend && (timed + allDay).none { it.occursOn(day) }) EMPTY_WEEKEND_WEIGHT else 1f
        }
        val unit = (width - gutter) / weights.sum()
        var x = gutter
        for (w in weights) {
            columnX += x
            columnW += w * unit
            x += w * unit
        }

        val wide = days.size == 1
        for ((i, day) in days.withIndex()) {
            // All-day strip.
            allDay.filter { it.occursOn(day) }.take(MAX_ALL_DAY_ROWS).forEachIndexed { row, event ->
                val rect = RectF(columnX[i] + dp(1f), headerHeight + row * allDayRowHeight + dp(1f),
                    columnX[i] + columnW[i] - dp(1f), headerHeight + (row + 1) * allDayRowHeight - dp(1f))
                allDayBlocks += Block(event, rect, lines(event, rect, allDayLine = true, wide = wide))
            }

            // Overlapping events share the day's width, as columns.
            val dayEvents = timed.filter { it.occursOn(day) }.sortedWith(compareBy({ it.start }, { -it.end.toEpochSecond0() }))
            for (cluster in clusters(dayEvents)) {
                val columnsEnd = mutableListOf<LocalDateTime>()
                val placed = cluster.map { event ->
                    val col = columnsEnd.indexOfFirst { !it.isAfter(event.start) }.let { if (it >= 0) it else { columnsEnd += event.end; columnsEnd.lastIndex } }
                    columnsEnd[col] = event.end
                    event to col
                }
                val count = columnsEnd.size
                for ((event, col) in placed) {
                    val w = columnW[i] / count
                    val top = y(if (event.startDate.isBefore(day)) day.atStartOfDay() else event.start, day)
                    val bottom = y(if (event.endDate.isAfter(day)) day.plusDays(1).atStartOfDay() else event.end, day)
                    val rect = RectF(columnX[i] + col * w + dp(1f), top + dp(1f), columnX[i] + (col + 1) * w - dp(1f), max(bottom, top + dp(18f)) - dp(1f))
                    blocks += Block(event, rect, lines(event, rect, allDayLine = false, wide = wide && count == 1))
                }
            }
        }
    }

    private fun LocalDateTime.toEpochSecond0() = toEpochSecond(java.time.ZoneOffset.UTC)

    private fun clusters(sorted: List<CalendarEvent>): List<List<CalendarEvent>> {
        val out = mutableListOf<MutableList<CalendarEvent>>()
        var clusterEnd: LocalDateTime? = null
        for (event in sorted) {
            if (clusterEnd == null || !event.start.isBefore(clusterEnd)) {
                out += mutableListOf(event)
                clusterEnd = event.end
            } else {
                out.last() += event
                if (event.end.isAfter(clusterEnd)) clusterEnd = event.end
            }
        }
        return out
    }

    private fun y(time: LocalDateTime, day: LocalDate): Float {
        val minutes = if (time.toLocalDate().isAfter(day)) 24 * 60 else time.hour * 60 + time.minute
        return gridTop + (minutes - firstHour * 60) / 60f * hourHeight
    }

    /**
     * The block's text, one layout per line, most important first and cut
     * to what fits: narrow week blocks get the course code, type, room and
     * start time (a course's own calendar leaves out the course); a day's
     * wide block gets the full names and time range.
     */
    private fun lines(event: CalendarEvent, rect: RectF, allDayLine: Boolean, wide: Boolean): List<StaticLayout> {
        val pad = dp(3f)
        val width = (rect.width() - 2 * pad).toInt()
        if (width <= 0) return emptyList()
        val c = colors?.of(event) ?: return emptyList()
        // Layouts keep their paint, so each block needs its own.
        val size = (if (wide) 12 else 10) * scaled
        val blockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = c.text }
        val blockBoldPaint = TextPaint(blockPaint).apply { typeface = Typeface.DEFAULT_BOLD }

        if (allDayLine) return listOf(layout(event.title, blockBoldPaint, width, 1))

        val time = if (wide) "${event.start.toLocalTime()} – ${event.end.toLocalTime()}" else event.start.toLocalTime().toString()
        val parts: List<Pair<String, Boolean>> = when {
            !event.isSchedule -> listOfNotNull(event.title to true, event.source?.takeIf { wide }?.let { it to false }, time to false)
            byCourse && wide -> listOfNotNull(event.title to true, "${event.kind}${event.rooms?.let { " · $it" } ?: ""}" to false,
                event.courseCode?.let { it to false }, time to false)
            byCourse -> listOfNotNull((event.courseCode ?: event.title) to true, event.kind to false, event.rooms?.let { it to false }, time to false)
            wide -> listOfNotNull(event.kind to true, event.title to false, event.rooms?.let { it to false }, time to false)
            else -> listOfNotNull(event.kind to true, event.rooms?.let { it to false }, time to false)
        }
        val available = rect.height() - 2 * pad
        val out = mutableListOf<StaticLayout>()
        var used = 0f
        for ((index, part) in parts.withIndex()) {
            val (text, bold) = part
            val paint = if (bold) blockBoldPaint else blockPaint
            val lineHeight = paint.fontSpacing
            val remainingLines = ((available - used) / lineHeight).toInt()
            if (remainingLines <= 0) break
            // A hand-added event's title may wrap, leaving a line for the time.
            val maxLines = if (index == 0 && !event.isSchedule) max(1, min(3, remainingLines - 1)) else 1
            val layout = layout(text, paint, width, maxLines)
            out += layout
            used += layout.height
        }
        return out
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty() || columnX.size != days.size) return
        val bottom = gridTop + hours * hourHeight

        // Weekend and today backgrounds, then the day separators.
        for ((i, day) in days.withIndex()) {
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
            if (weekend && days.size > 1) canvas.drawRect(columnX[i], 0f, columnX[i] + columnW[i], bottom, weekendPaint)
            if (day == today && days.size > 1) canvas.drawRect(columnX[i], 0f, columnX[i] + columnW[i], bottom, todayPaint)
            canvas.drawLine(columnX[i], headerHeight, columnX[i], bottom, linePaint)
        }

        // Day headers: "Lun" over "28", today's number circled like the site's month view.
        for ((i, day) in days.withIndex()) {
            val cx = columnX[i] + columnW[i] / 2
            val name = if (days.size == 1) day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
            canvas.drawText(TextUtils.ellipsize(name.replaceFirstChar { it.titlecase(locale) }, dayNamePaint, columnW[i], TextUtils.TruncateAt.END).toString(),
                cx, dp(14f), dayNamePaint)
            val number = day.dayOfMonth.toString()
            if (day == today) {
                fillPaint.color = accent
                canvas.drawCircle(cx, dp(29f), dp(11f), fillPaint)
                dayNumberPaint.color = palette.onAccent
            } else {
                dayNumberPaint.color = textColor
            }
            canvas.drawText(number, cx, dp(34f), dayNumberPaint)
        }
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, linePaint)

        // Hour lines and labels.
        for (h in 0..hours) {
            val y = gridTop + h * hourHeight
            canvas.drawLine(gutter, y, width.toFloat(), y, linePaint)
            if (h < hours) canvas.drawText("%02d".format(firstHour + h), dp(4f), y + hourPaint.textSize + dp(2f), hourPaint)
        }
        if (allDayRows > 0) canvas.drawLine(0f, gridTop, width.toFloat(), gridTop, linePaint)

        for (block in allDayBlocks + blocks) drawBlock(canvas, block)

        // "Now" line across the week, bolder over today, with the time in the gutter.
        val todayIndex = days.indexOf(today)
        if (todayIndex >= 0) {
            val now = LocalDateTime.now()
            val y = y(now, today)
            if (y in gridTop..bottom) {
                nowPaint.alpha = 90
                canvas.drawLine(gutter, y, width.toFloat(), y, nowPaint)
                nowPaint.alpha = 255
                canvas.drawLine(columnX[todayIndex], y, columnX[todayIndex] + columnW[todayIndex], y, nowPaint)
                val label = "%02d:%02d".format(now.hour, now.minute)
                val half = nowLabelPaint.textSize * 0.75f
                fillPaint.color = palette.now
                nowLabelRect.set(0f, y - half, gutter - dp(1f), y + half)
                canvas.drawRoundRect(nowLabelRect, dp(2f), dp(2f), fillPaint)
                canvas.drawText(label, (gutter - dp(1f)) / 2, y + nowLabelPaint.textSize * 0.35f, nowLabelPaint)
            }
        }
    }

    private fun drawBlock(canvas: Canvas, block: Block) {
        val c = colors?.of(block.event) ?: return
        fillPaint.color = c.background
        canvas.drawRoundRect(block.rect, radius, radius, fillPaint)
        strokePaint.color = c.border
        canvas.drawRoundRect(block.rect, radius, radius, strokePaint)
        canvas.save()
        canvas.clipRect(block.rect)
        canvas.translate(block.rect.left + dp(3f), block.rect.top + if (block.event.allDay) dp(1.5f) else dp(3f))
        for (line in block.lines) {
            line.draw(canvas)
            canvas.translate(0f, line.height.toFloat())
        }
        canvas.restore()
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            (blocks + allDayBlocks).lastOrNull { it.rect.contains(e.x, e.y) }?.let {
                listener?.onEventClick(it.event)
                return true
            }
            if (e.y < headerHeight && days.size > 1) {
                val i = columnX.indexOfLast { e.x >= it }
                if (i >= 0) listener?.onDayClick(days[i])
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) < dp(SWIPE_MIN_DP) || abs(dx) < abs(dy) * 1.5f) return false
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

    private companion object {
        const val DEFAULT_FIRST_HOUR = 8
        const val DEFAULT_LAST_HOUR = 19
        const val MAX_ALL_DAY_ROWS = 3
        const val EMPTY_WEEKEND_WEIGHT = 0.55f
        const val SWIPE_MIN_DP = 60f
    }
}
