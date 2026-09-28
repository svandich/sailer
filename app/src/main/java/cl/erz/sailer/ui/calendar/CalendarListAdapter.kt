package cl.erz.sailer.ui.calendar

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cl.erz.sailer.R
import cl.erz.sailer.calendar.CalendarEvent
import cl.erz.sailer.calendar.EventColors
import java.time.LocalDate

/** The Agenda and Resumen lists: events grouped under their day. */
class CalendarListAdapter(
    private val onEventClick: (CalendarEvent) -> Unit,
    private val onDayClick: (LocalDate) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        class Day(val date: LocalDate, val label: String) : Row()
        class Event(val event: CalendarEvent) : Row()
        class Message(val text: String) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var colors: EventColors? = null
    private var kindLabel: (String) -> String = { it }

    fun submit(rows: List<Row>, colors: EventColors, kindLabel: (String) -> String) {
        this.rows = rows
        this.colors = colors
        this.kindLabel = kindLabel
        @Suppress("NotifyDataSetChanged") // Whole new range each time.
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Day -> TYPE_DAY
        is Row.Event -> TYPE_EVENT
        is Row.Message -> TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = when (viewType) {
            TYPE_DAY -> R.layout.item_calendar_day
            TYPE_EVENT -> R.layout.item_calendar_event
            else -> R.layout.item_calendar_message
        }
        return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val view = holder.itemView
        when (val row = rows[position]) {
            is Row.Day -> (view as TextView).apply {
                text = row.label
                setOnClickListener { onDayClick(row.date) }
            }
            is Row.Message -> (view as TextView).text = row.text
            is Row.Event -> bindEvent(view, row.event)
        }
    }

    private fun bindEvent(view: View, event: CalendarEvent) {
        val c = colors?.of(event)
        view.findViewById<TextView>(R.id.time).text =
            if (event.allDay) "" else "${event.start.toLocalTime()}\n${event.end.toLocalTime()}"
        view.findViewById<View>(R.id.colorBar).background = c?.let { rounded(it.text, 2f, view) }
        view.findViewById<TextView>(R.id.title).text = event.title
        view.findViewById<TextView>(R.id.pill).apply {
            text = kindLabel(event.kind)
            val pill = colors?.ofKind(event.kind)
            background = pill?.let { rounded(it.background, 10f, view) }
            setTextColor(pill?.text ?: currentTextColor)
        }
        view.findViewById<TextView>(R.id.subtitle).apply {
            val parts = listOfNotNull(event.url?.let { event.courseCode }?.takeIf { event.isSchedule }, event.rooms, event.source)
            text = parts.joinToString(" · ")
            visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
        view.setOnClickListener { onEventClick(event) }
    }

    private fun rounded(color: Int, radiusDp: Float, view: View) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * view.resources.displayMetrics.density
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_EVENT = 1
        const val TYPE_MESSAGE = 2
    }
}
