package cl.erz.sailer.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Reads the iCal export of a u-cursos.cl calendar (`<calendar>/ical`), just
 * enough for its own output:
 *
 * - Schedule blocks have a role icon (`IMAGE` under `/cargos/`) and a
 *   `SUMMARY` of `<type> - <course>\n<rooms>`.
 * - Events added to a calendar have `SUMMARY` `<title> - <course/community>`.
 * - Times are UTC (`...Z`), shown in the calendar's `X-WR-TIMEZONE`; all-day
 *   events are `VALUE=DATE` (with a stray `Z`) and include their `DTEND` day.
 * - A few use a weekly/daily `RRULE`.
 * - Commas come out as `$1` instead of `\,` (a bug in the site's escaping).
 */
object ICalendar {

    class Parsed(val name: String?, val events: List<CalendarEvent>)

    private const val MAX_OCCURRENCES = 500
    private val DEFAULT_ZONE: ZoneId = ZoneId.of("America/Santiago")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parse(text: String): Parsed {
        // Unfold continuation lines (RFC 5545 3.1).
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        var zone = DEFAULT_ZONE
        var name: String? = null
        val raw = mutableListOf<Map<String, Pair<String, String>>>()
        var current: MutableMap<String, Pair<String, String>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> current = mutableMapOf()
                line == "END:VEVENT" -> current?.let { raw += it }.also { current = null }
                else -> {
                    val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                    val head = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    val key = head.substringBefore(';')
                    val event = current
                    if (event != null) event[key] = head to value
                    else if (key == "X-WR-TIMEZONE") zone = runCatching { ZoneId.of(value.trim()) }.getOrDefault(DEFAULT_ZONE)
                    else if (key == "X-WR-CALNAME") name = unescape(value).removeSuffix(" - U-Cursos")
                }
            }
        }
        val events = raw.flatMap { runCatching { toEvents(it, zone) }.getOrDefault(emptyList()) }
            .sortedWith(compareBy({ it.start }, { it.end }))
        return Parsed(name, events)
    }

    private fun toEvents(fields: Map<String, Pair<String, String>>, zone: ZoneId): List<CalendarEvent> {
        val (startHead, startValue) = fields["DTSTART"] ?: return emptyList()
        val allDay = "VALUE=DATE" in startHead && "DATE-TIME" !in startHead
        val start = time(startValue, allDay, zone) ?: return emptyList()
        val end = fields["DTEND"]?.let { time(it.second, allDay, zone) }?.takeIf { !it.isBefore(start) } ?: start
        val summary = unescape(fields["SUMMARY"]?.second.orEmpty())
        val url = fields["URL"]?.second?.trim()?.takeIf { it.startsWith("https://") }
        val isSchedule = fields["IMAGE"]?.second?.contains("/cargos/") == true
        val uid = fields["UID"]?.second ?: "$startValue|$summary"

        val kind: String
        val title: String
        val rooms: String?
        val source: String?
        if (isSchedule) {
            val firstLine = summary.substringBefore('\n')
            kind = firstLine.substringBefore(" - ").trim()
            title = firstLine.substringAfter(" - ", firstLine).trim()
            rooms = summary.substringAfter('\n', "").trim().ifEmpty { null }
            source = null
        } else {
            kind = CalendarEvent.KIND_OTHER
            val cut = summary.lastIndexOf(" - ")
            title = (if (cut > 0) summary.substring(0, cut) else summary).trim()
            source = if (cut > 0) summary.substring(cut + 3).trim() else null
            rooms = null
        }

        val first = CalendarEvent(uid, start, end, allDay, kind, title, rooms, source, url)
        val rule = fields["RRULE"]?.second ?: return listOf(first)
        return expand(first, rule, zone)
    }

    // FREQ=DAILY|WEEKLY with INTERVAL, COUNT or UNTIL, and BYDAY for weekly.
    private fun expand(first: CalendarEvent, rule: String, zone: ZoneId): List<CalendarEvent> {
        val parts = rule.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val step = when (parts["FREQ"]) {
            "DAILY" -> ChronoUnit.DAYS
            "WEEKLY" -> ChronoUnit.WEEKS
            else -> return listOf(first)
        }
        val interval = parts["INTERVAL"]?.toLongOrNull()?.coerceAtLeast(1) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()?.coerceAtMost(MAX_OCCURRENCES) ?: MAX_OCCURRENCES
        val until = parts["UNTIL"]?.let { time(it, it.length == 8 || (it.length == 9 && it.endsWith("Z")), zone) }
        val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.takeLast(2)] }?.toSet().orEmpty()
        val length = java.time.Duration.between(first.start, first.end)

        val out = mutableListOf<CalendarEvent>()
        var periodStart = first.start
        while (out.size < count) {
            val candidates = if (step == ChronoUnit.WEEKS && byDay.isNotEmpty()) {
                val monday = periodStart.minusDays((periodStart.dayOfWeek.value - 1).toLong())
                byDay.sorted().map { monday.plusDays((it.value - 1).toLong()) }.filter { !it.isBefore(first.start) }
            } else listOf(periodStart)
            for (start in candidates) {
                if (until != null && start.isAfter(until)) return out
                if (out.size >= count) return out
                out += first.copy(id = "${first.id}@${start}", start = start, end = start.plus(length))
            }
            periodStart = periodStart.plus(interval, step)
        }
        return out
    }

    private fun time(value: String, allDay: Boolean, zone: ZoneId): LocalDateTime? = runCatching {
        val v = value.trim()
        if (allDay || v.length <= 9) {
            LocalDate.parse(v.take(8), DATE).atStartOfDay()
        } else {
            val local = LocalDateTime.parse(v.take(15), DATE_TIME)
            if (v.endsWith("Z")) local.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime() else local
        }
    }.getOrNull()

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString().replace("$1", ",")
    }

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
}
