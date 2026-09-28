package cl.erz.sailer.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import cl.erz.sailer.R
import cl.erz.sailer.calendar.CalendarEvent
import cl.erz.sailer.calendar.CalendarRepository
import cl.erz.sailer.calendar.EventColors
import cl.erz.sailer.calendar.ICalendar
import cl.erz.sailer.databinding.ActivityCalendarBinding
import cl.erz.sailer.ui.calendar.CalendarListAdapter
import cl.erz.sailer.ui.calendar.MonthGridView
import cl.erz.sailer.ui.calendar.TimeGridView
import cl.erz.sailer.ui.theme.Palette
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.Collator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * A native version of u-cursos.cl's calendar module: a person's Horario
 * (`/usuario/<id>/horario/`) or a course's Calendario (`<course>/calendario/`),
 * which MainActivity opens here instead of in its WebView. Same five views as
 * the site - Resumen, Agenda, Día, Semana, Mes - and the same type filters,
 * but sized to the phone: the site's week table is wider than the screen.
 *
 * Everything comes from [CalendarRepository] (the site's own iCal export, plus
 * its month page for holidays); pages it has no native version of (Agregar
 * Evento, Configuración, Coordinador, an event's own page) open in MainActivity.
 */
class CalendarActivity : AppCompatActivity(), TimeGridView.Listener, MonthGridView.Listener {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var repository: CalendarRepository
    private lateinit var colors: EventColors
    private lateinit var palette: Palette
    private lateinit var base: String
    private lateinit var locale: Locale

    // A person's Horario colors by course; a course's Calendario by type.
    private var isPersonal = true
    private var mode = MODE_WEEK
    private var date: LocalDate = LocalDate.now()
    private var showPast = false

    private var events: List<CalendarEvent> = emptyList()
    private var loaded = false
    private val pages = mutableMapOf<YearMonth, CalendarRepository.Page>()
    private val pageJobs = mutableMapOf<YearMonth, Job>()
    private var refreshJob: Job? = null

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val hiddenKinds = mutableSetOf<String>()

    private val listAdapter = CalendarListAdapter(::showEvent) { day -> switchTo(MODE_DAY, day) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val target = url?.let(::parse)
        if (target == null) {
            finish()
            return
        }
        base = target.base
        isPersonal = target.isPersonal
        mode = savedInstanceState?.getString(STATE_MODE)
            ?: target.mode
            ?: prefs.getString(PREF_MODE, null)?.takeIf { it in MODES }
            ?: MODE_WEEK
        date = savedInstanceState?.getString(STATE_DATE)?.let(LocalDate::parse) ?: target.date ?: LocalDate.now()
        showPast = savedInstanceState?.getBoolean(STATE_SHOW_PAST) ?: false
        hiddenKinds += prefs.getStringSet(PREF_HIDDEN_KINDS, emptySet()).orEmpty()

        locale = resources.configuration.locales[0]
        palette = Palette.of(this)
        colors = EventColors(palette, byCourse = isPersonal)
        repository = CalendarRepository(this, base)

        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(if (isPersonal) R.string.calendar_title_personal else R.string.calendar_title_course)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setUpTabs()
        binding.previous.setOnClickListener { move(-1) }
        binding.next.setOnClickListener { move(1) }
        binding.today.setOnClickListener { date = LocalDate.now(); render() }
        binding.period.setOnClickListener { pickDate() }
        binding.showPast.isChecked = showPast
        binding.showPast.setOnCheckedChangeListener { _, checked -> showPast = checked; render() }
        binding.errorRetry.setOnClickListener { refresh() }
        binding.errorWeb.setOnClickListener { openInWebView(currentWebUrl()) }

        binding.timeGrid.listener = this
        binding.monthGrid.listener = this
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = listAdapter
        binding.gridScroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> binding.timeGrid.viewportHeight = v.height }

        repository.cachedEvents()?.let { setEvents(it) }
        render()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode)
        outState.putString(STATE_DATE, date.toString())
        outState.putBoolean(STATE_SHOW_PAST, showPast)
    }

    private fun setUpTabs() {
        val labels = mapOf(
            MODE_SUMMARY to R.string.calendar_mode_summary, MODE_AGENDA to R.string.calendar_mode_agenda,
            MODE_DAY to R.string.calendar_mode_day, MODE_WEEK to R.string.calendar_mode_week, MODE_MONTH to R.string.calendar_mode_month,
        )
        for (m in MODES) binding.tabs.addTab(binding.tabs.newTab().setText(labels.getValue(m)).setTag(m), m == mode)
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = switchTo(tab.tag as String, date)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun switchTo(newMode: String, day: LocalDate) {
        date = day
        if (newMode != mode) {
            mode = newMode
            prefs.edit().putString(PREF_MODE, mode).apply()
            binding.tabs.getTabAt(MODES.indexOf(mode))?.takeIf { !it.isSelected }?.select()
        }
        render()
    }

    private fun move(direction: Int) {
        date = when (mode) {
            MODE_DAY -> date.plusDays(direction.toLong())
            MODE_MONTH -> date.plusMonths(direction.toLong())
            else -> date.plusWeeks(direction.toLong())
        }
        render()
    }

    override fun onSwipe(direction: Int) = move(direction)

    override fun onEventClick(event: CalendarEvent) = showEvent(event)

    override fun onDayClick(date: LocalDate) = switchTo(MODE_DAY, date)

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
            render()
        }
        picker.show(supportFragmentManager, "date")
    }

    // --- Data ---

    private fun setEvents(parsed: ICalendar.Parsed) {
        // Events added to this very calendar name it as their source ("Dmitri Ramírez"): nothing to add.
        events = parsed.events.map { if (it.source != null && it.source == parsed.name) it.copy(source = null) else it }
        loaded = true
        parsed.name?.takeIf { !isPersonal }?.let { supportActionBar?.subtitle = it }
    }

    private fun refresh() {
        refreshJob?.cancel()
        binding.progress.visibility = View.VISIBLE
        binding.errorBox.visibility = View.GONE
        refreshJob = lifecycleScope.launch {
            try {
                setEvents(repository.fetchEvents())
                pages.clear()
                pageJobs.values.forEach { it.cancel() }
                pageJobs.clear()
                render()
            } catch (e: IOException) {
                onLoadFailed(e)
            } finally {
                binding.progress.visibility = View.INVISIBLE
            }
        }
    }

    private fun onLoadFailed(e: IOException) {
        val message = if (e is CalendarRepository.NotLoggedInException) R.string.calendar_error_session else R.string.calendar_error_network
        if (loaded) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_retry) { refresh() }
                .show()
        } else {
            binding.errorText.setText(message)
            binding.errorBox.visibility = View.VISIBLE
            listOf(binding.gridScroll, binding.monthGrid, binding.list).forEach { it.visibility = View.GONE }
        }
    }

    /** The site's month pages, for the holidays (and links) of the months on screen. */
    private fun ensurePages(months: Collection<YearMonth>) {
        for (month in months) {
            if (month in pages || month in pageJobs) continue
            repository.cachedPage(month)?.let { pages[month] = it }
            pageJobs[month] = lifecycleScope.launch {
                runCatching { repository.fetchPage(month) }.onSuccess { page ->
                    pages[month] = page
                    invalidateOptionsMenu()
                    render()
                }
            }
        }
    }

    private val holidays: List<CalendarEvent> get() = pages.values.flatMap { it.holidays }.distinctBy { it.id }

    private val visibleEvents: List<CalendarEvent>
        get() = events.filter { it.kind !in hiddenKinds } + holidays

    // --- Rendering ---

    private fun render() {
        if (!::binding.isInitialized) return
        val today = LocalDate.now()
        binding.tabs.getTabAt(MODES.indexOf(mode))?.takeIf { !it.isSelected }?.select()
        binding.period.text = periodTitle()
        val paged = mode != MODE_SUMMARY
        binding.previous.visibility = if (paged) View.VISIBLE else View.INVISIBLE
        binding.next.visibility = if (paged) View.VISIBLE else View.INVISIBLE
        binding.today.visibility = if (paged) View.VISIBLE else View.INVISIBLE
        binding.period.isClickable = paged
        binding.filtersScroll.visibility = if (paged) View.VISIBLE else View.GONE
        binding.showPast.visibility = if (paged) View.GONE else View.VISIBLE
        renderFilters()

        if (!loaded) {
            listOf(binding.gridScroll, binding.monthGrid, binding.list).forEach { it.visibility = View.GONE }
            return
        }
        binding.errorBox.visibility = View.GONE
        binding.gridScroll.visibility = if (mode == MODE_WEEK || mode == MODE_DAY) View.VISIBLE else View.GONE
        binding.monthGrid.visibility = if (mode == MODE_MONTH) View.VISIBLE else View.GONE
        binding.list.visibility = if (mode == MODE_AGENDA || mode == MODE_SUMMARY) View.VISIBLE else View.GONE

        when (mode) {
            MODE_WEEK, MODE_DAY -> {
                val days = if (mode == MODE_DAY) listOf(date) else weekOf(date).let { monday -> (0L..6L).map { monday.plusDays(it) } }
                ensurePages(days.map(YearMonth::from).toSet())
                binding.timeGrid.setData(days, visibleEvents, colors, isPersonal, today, locale)
            }
            MODE_MONTH -> {
                val month = YearMonth.from(date)
                ensurePages(listOf(month.minusMonths(1), month, month.plusMonths(1)))
                binding.monthGrid.setData(month, visibleEvents, colors, isPersonal, today, locale, { n -> resources.getQuantityString(R.plurals.calendar_more, n, n) })
            }
            MODE_AGENDA -> {
                val to = date.plusDays(AGENDA_DAYS - 1)
                ensurePages(setOf(YearMonth.from(date), YearMonth.from(to)))
                val inRange = visibleEvents.filter { it.overlaps(date, to) }
                val rows = mutableListOf<CalendarListAdapter.Row>()
                var day = date
                while (!day.isAfter(to)) {
                    val onDay = inRange.filter { it.occursOn(day) }.sortedWith(compareBy({ !it.allDay }, { it.start }))
                    if (onDay.isNotEmpty()) {
                        rows += CalendarListAdapter.Row.Day(day, dayTitle(day))
                        onDay.forEach { rows += CalendarListAdapter.Row.Event(it) }
                    }
                    day = day.plusDays(1)
                }
                if (rows.isEmpty()) rows += CalendarListAdapter.Row.Message(getString(R.string.calendar_empty))
                listAdapter.submit(rows, colors, ::kindLabel)
            }
            MODE_SUMMARY -> {
                ensurePages(setOf(YearMonth.from(today)))
                // Like the site: only events added by hand, from today on (or
                // this year's past ones too), without classes or holidays.
                val from = if (showPast) today.withDayOfYear(1) else today
                val list = events.filter { !it.isSchedule && it.kind != CalendarEvent.KIND_HOLIDAY && !it.endDate.isBefore(from) }
                val rows = mutableListOf<CalendarListAdapter.Row>(CalendarListAdapter.Row.Message(getString(R.string.calendar_summary_legend)))
                var lastDay: LocalDate? = null
                for (event in list) {
                    if (event.startDate != lastDay) {
                        lastDay = event.startDate
                        rows += CalendarListAdapter.Row.Day(event.startDate, dayTitle(event.startDate))
                    }
                    rows += CalendarListAdapter.Row.Event(event)
                }
                if (list.isEmpty()) rows += CalendarListAdapter.Row.Message(getString(R.string.calendar_empty))
                listAdapter.submit(rows, colors, ::kindLabel)
            }
        }
    }

    private fun renderFilters() {
        val collator = Collator.getInstance(locale)
        val kinds = events.filter { it.isSchedule }.map { it.kind }.distinct().sortedWith(collator) +
            listOfNotNull(CalendarEvent.KIND_OTHER.takeIf { events.any { !it.isSchedule } })
        val current = (0 until binding.filters.childCount).map { binding.filters.getChildAt(it).tag }
        if (current == kinds) {
            for (i in 0 until binding.filters.childCount) {
                val chip = binding.filters.getChildAt(i) as Chip
                chip.isChecked = chip.tag !in hiddenKinds
            }
            return
        }
        binding.filters.removeAllViews()
        for (kind in kinds) {
            val chip = Chip(this).apply {
                tag = kind
                text = kindLabel(kind)
                isCheckable = true
                isChecked = kind !in hiddenKinds
                // Compact, so the site's usual four fit across the screen: no
                // check mark, the state shows as filled vs. outlined instead.
                isCheckedIconVisible = false
                val density = resources.displayMetrics.density
                chipMinHeight = 30 * density
                chipStartPadding = 2 * density
                chipEndPadding = 2 * density
                textSize = 13f
                val checkedState = intArrayOf(android.R.attr.state_checked)
                chipBackgroundColor = ColorStateList(arrayOf(checkedState, intArrayOf()),
                    intArrayOf(filterFill, android.graphics.Color.TRANSPARENT))
                chipStrokeWidth = density
                chipStrokeColor = ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(filterFill, filterStroke))
                setTextColor(ColorStateList(arrayOf(checkedState, intArrayOf()), intArrayOf(filterText, filterTextOff)))
                // A course's calendar colors its blocks by type: the chip shows it.
                if (!isPersonal) {
                    chipIcon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(this@CalendarActivity.colors.ofKind(kind).text) }
                    chipIconSize = 10 * density
                    isChipIconVisible = true
                }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) hiddenKinds -= kind else hiddenKinds += kind
                    prefs.edit().putStringSet(PREF_HIDDEN_KINDS, hiddenKinds.toSet()).apply()
                    render()
                }
            }
            binding.filters.addView(chip)
        }
    }

    private val filterFill get() = palette.withAlpha(palette.accent, 0.33f)
    private val filterStroke get() = palette.withAlpha(palette.onSurfaceMuted, 0.33f)
    private val filterText get() = palette.onSurface
    private val filterTextOff get() = palette.onSurfaceMuted

    private fun kindLabel(kind: String): String = when (kind) {
        CalendarEvent.KIND_OTHER -> getString(R.string.calendar_kind_other)
        CalendarEvent.KIND_HOLIDAY -> getString(R.string.calendar_kind_holiday)
        else -> kind
    }

    private fun weekOf(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    private fun periodTitle(): String {
        val short = DateTimeFormatter.ofPattern("d MMM", locale)
        val thisYear = date.year == LocalDate.now().year
        return when (mode) {
            MODE_SUMMARY -> getString(R.string.calendar_summary_title)
            MODE_DAY -> dayTitle(date).let { if (thisYear) it else "$it ${date.year}" }
            MODE_WEEK -> {
                val monday = weekOf(date)
                val sunday = monday.plusDays(6)
                getString(R.string.calendar_week_title, monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                    "${monday.format(short)} – ${sunday.format(short)}".trimDots()) + if (thisYear) "" else " ${date.year}"
            }
            MODE_AGENDA -> "${date.format(short)} – ${date.plusDays(AGENDA_DAYS - 1).format(short)}".trimDots() + if (thisYear) "" else " ${date.year}"
            else -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(date).replaceFirstChar { it.titlecase(locale) }
        }
    }

    private fun String.trimDots() = replace(".", "")

    private fun dayTitle(day: LocalDate): String =
        DateTimeFormatter.ofPattern(getString(R.string.calendar_day_pattern), locale).format(day).replaceFirstChar { it.titlecase(locale) }

    // --- Event details ---

    private fun showEvent(event: CalendarEvent) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_calendar_event, android.widget.FrameLayout(this), false)
        val pillColors = colors.ofKind(event.kind)
        view.findViewById<TextView>(R.id.pill).apply {
            text = kindLabel(event.kind)
            setTextColor(pillColors.text)
            background = GradientDrawable().apply {
                setColor(pillColors.background)
                cornerRadius = resources.displayMetrics.density * 12
            }
        }
        view.findViewById<TextView>(R.id.title).text = event.title
        view.findViewById<TextView>(R.id.`when`).apply {
            val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
            val days = if (event.endDate != event.startDate) "${event.startDate.format(longDate)} – ${event.endDate.format(longDate)}"
            else event.startDate.format(longDate)
            text = if (event.allDay) days else "$days\n${event.start.toLocalTime()} – ${event.end.toLocalTime()}"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_schedule, 0, 0, 0)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
        }
        view.findViewById<TextView>(R.id.details).apply {
            val lines = listOfNotNull(
                event.rooms?.let { getString(R.string.calendar_rooms, it) },
                event.source?.let { getString(R.string.calendar_source, it) },
                event.courseCode?.takeIf { event.isSchedule && isPersonal }?.let { getString(R.string.calendar_course, it) },
            )
            text = lines.joinToString("\n")
            visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.open).apply {
            val url = event.url
            visibility = if (url == null) View.GONE else View.VISIBLE
            setText(if (event.isSchedule) R.string.calendar_open_course else R.string.calendar_open_event)
            setOnClickListener {
                sheet.dismiss()
                url?.let(::openInWebView)
            }
        }
        sheet.setContentView(view)
        sheet.show()
    }

    // --- Menu: the site's pages without a native version ---

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.calendar, menu)
        val page = pages[YearMonth.from(date)] ?: pages.values.firstOrNull()
        menu.findItem(R.id.action_add_event).apply {
            isVisible = page?.addEvent != null
            page?.addEvent?.let { title = it.label }
        }
        page?.extraTabs?.forEachIndexed { i, link ->
            menu.add(Menu.NONE, MENU_EXTRA_BASE + i, i, link.label).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val page = pages[YearMonth.from(date)] ?: pages.values.firstOrNull()
        when (item.itemId) {
            R.id.action_add_event -> page?.addEvent?.let { openInWebView(Uri.parse(it.url).buildUpon().clearQuery().appendQueryParameter("fecha", date.toString()).build().toString()) }
            R.id.action_reload -> refresh()
            R.id.action_open_web -> openInWebView(currentWebUrl())
            else -> page?.extraTabs?.getOrNull(item.itemId - MENU_EXTRA_BASE)?.let { openInWebView(it.url) } ?: return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun currentWebUrl(): String = if (mode == MODE_SUMMARY) "${base}resumen" else "$base$mode?fecha=$date"

    /** Opens a site page in MainActivity's WebView (bypassing this activity for calendar pages). */
    private fun openInWebView(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_URL, url)
                .putExtra(MainActivity.EXTRA_IN_WEBVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val STATE_MODE = "mode"
        private const val STATE_DATE = "date"
        private const val STATE_SHOW_PAST = "show_past"
        private const val PREF_MODE = "calendar_mode"
        private const val PREF_HIDDEN_KINDS = "calendar_hidden_kinds"
        private const val MENU_EXTRA_BASE = 1000
        private const val AGENDA_DAYS = 7L

        // The site's own names for its views, as in its URLs.
        private const val MODE_SUMMARY = "resumen"
        private const val MODE_AGENDA = "agenda"
        private const val MODE_DAY = "dia"
        private const val MODE_WEEK = "semana"
        private const val MODE_MONTH = "mes"
        private val MODES = CalendarRepository.MODES

        // `/usuario/<id>/horario/[view]` or `<anything>/calendario/[view]`; not
        // an event's own page (`calendario/o/<id>`) or the other tabs.
        private val CALENDAR_PATH = Regex("^(/usuario/[^/]+/horario/|/.+/calendario/)(resumen|agenda|dia|semana|mes)?/?$")

        private class Target(val base: String, val isPersonal: Boolean, val mode: String?, val date: LocalDate?)

        private fun parse(url: String): Target? {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host?.endsWith("u-cursos.cl") != true) return null
            val path = uri.path.orEmpty().let { if (it.endsWith("/horario") || it.endsWith("/calendario")) "$it/" else it }
            val match = CALENDAR_PATH.find(path) ?: return null
            val base = "https://${uri.host}${match.groupValues[1]}"
            val date = runCatching { uri.getQueryParameter("fecha")?.let(LocalDate::parse) }.getOrNull()
            return Target(base, match.groupValues[1].startsWith("/usuario/"), match.groupValues[2].ifEmpty { null }, date)
        }

        fun isCalendarUrl(url: String): Boolean = parse(url) != null

        fun intent(context: Context, url: String): Intent =
            Intent(context, CalendarActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
