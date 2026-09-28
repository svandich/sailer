package cl.erz.sailer.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One occurrence of an event in a u-cursos.cl calendar (a person's Horario
 * or a course/community's Calendario), as [ICalendar] reads it from the
 * site's own iCal export, or a holiday read from its month page.
 *
 * [kind] is what the site's filter checkboxes act on: a class type
 * ("Cátedra", "Auxiliar", "Control", ...) for schedule blocks, [KIND_OTHER]
 * ("Otros Eventos") for events added to a calendar by hand, or [KIND_HOLIDAY].
 */
data class CalendarEvent(
    val id: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val kind: String,
    val title: String,
    val rooms: String?,
    // The course or community it belongs to ("IQ2212-3", "COMCADCC-1 Estudiantes DCC").
    val source: String?,
    val url: String?,
) {
    val startDate: LocalDate get() = start.toLocalDate()

    // All-day events include their end date, as the site shows them; timed
    // ones ending at midnight don't reach into the next day.
    val endDate: LocalDate
        get() = if (allDay || end.toLocalTime() != LocalTime.MIDNIGHT) end.toLocalDate()
        else end.toLocalDate().minusDays(1).coerceAtLeast(startDate)

    fun occursOn(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)

    fun overlaps(from: LocalDate, to: LocalDate): Boolean = !endDate.isBefore(from) && !startDate.isAfter(to)

    val isSchedule: Boolean get() = kind != KIND_OTHER && kind != KIND_HOLIDAY

    /** The course's page, e.g. `https://www.u-cursos.cl/ingenieria/2026/2/IQ2212/3/`. */
    val courseUrl: String? get() = url?.let { COURSE_URL.find(it)?.value }

    /** The course code alone ("IQ2212"), for narrow blocks. */
    val courseCode: String? get() = url?.let { COURSE_URL.find(it)?.groupValues?.get(1) }

    companion object {
        const val KIND_OTHER = "_eventos_"
        const val KIND_HOLIDAY = "_feriado_"

        // .../<institución>/<año>/<semestre>/<código>/<sección>/
        private val COURSE_URL = Regex("^https://[^/]+/[^/]+/\\d{4}/\\d+/([^/]+)/\\d+/")
    }
}
