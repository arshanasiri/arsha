package com.example.callrecorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    // صفحات
    private lateinit var pageRecord: View
    private lateinit var pageLibrary: View
    private lateinit var pageSettings: View
    private lateinit var bottomNav: BottomNavigationView

    // ضبط
    private lateinit var tvTimer: TextView
    private lateinit var tvState: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvLog: TextView
    private lateinit var logCard: View
    private lateinit var levelView: LevelView
    private lateinit var recButton: FrameLayout
    private lateinit var imgRec: ImageView
    private lateinit var ring: View
    private lateinit var swAuto: MaterialSwitch
    private var pulse: ObjectAnimator? = null
    private var ringAnim: ValueAnimator? = null
    private var lastRecording = false
    private var lastEventSeq = RecorderService.eventSeq
    private var armToggledAt = 0L
    private var syncingSwitch = false

    // کتابخانه
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: View
    private lateinit var statsCard: View
    private lateinit var statCount: TextView
    private lateinit var statTime: TextView
    private lateinit var statSize: TextView
    private lateinit var statPeople: TextView
    private lateinit var selectBar: View
    private lateinit var tvSelCount: TextView
    private lateinit var etSearch: EditText
    private var allRecs: List<Rec> = emptyList()
    private var query = ""
    private var dirFilter = DirFilter.ALL
    private var onlyEnhanced = false
    private var sortMode = SortMode.NEWEST
    private var grouped = true
    private val selected = LinkedHashSet<String>()
    private val collapsed = HashSet<String>()
    private var visibleRecs: List<Rec> = emptyList()

    // پلیر
    private lateinit var playerBar: View
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var waveView: WaveformView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerCur: TextView
    private lateinit var tvPlayerTotal: TextView
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var speedIdx = 0
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)

    // تنظیمات
    private lateinit var tvSourceValue: TextView
    private lateinit var tvRateValue: TextView
    private lateinit var tvGainValue: TextView
    private val rates = listOf(8000, 16000, 44100, 48000)

    // ------------------------------------------------------------------ onCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        // میان‌بُرها (برای تست خودکار و شورت‌کات): arm = فعال‌سازی آماده‌باش، skipIntro = بدون راهنمای اول
        // فقط در نسخه‌ی آزمایشی (debug)؛ در نسخه‌ی انتشار این میان‌بُرها وجود ندارند.
        if (BuildConfig.DEBUG) {
            if (intent.getBooleanExtra("skipIntro", false)) prefs.firstRunDone = true
            if (intent.getBooleanExtra("arm", false)) prefs.autoRecord = true
            if (intent.getBooleanExtra("fake", false)) prefs.fakeSource = true
            if (intent.getBooleanExtra("nofake", false)) prefs.fakeSource = false
        }

        bottomNav = findViewById(R.id.bottomNav)
        pageRecord = findViewById(R.id.pageRecord)
        pageLibrary = findViewById(R.id.pageLibrary)
        pageSettings = findViewById(R.id.pageSettings)
        playerBar = findViewById(R.id.playerBar)

        setupNav()
        setupRecordPage()
        setupLibraryPage()
        setupPlayer()
        setupSettingsPage()

        val btnTheme = findViewById<MaterialButton>(R.id.btnTheme)
        btnTheme.setIconResource(if (isNightNow()) R.drawable.ic_sun else R.drawable.ic_moon)
        btnTheme.setOnClickListener {
            prefs.themeMode = if (isNightNow()) 1 else 2
            prefs.lastTab = bottomNav.selectedItemId
            AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(prefs.themeMode))
        }
        // بعد از تغییر تم صفحه‌ی قبلی را برگردان
        if (prefs.lastTab != 0) { bottomNav.selectedItemId = prefs.lastTab; prefs.lastTab = 0 }

        requestNeededPermissions()
        // اگر حالت آماده‌باش قبلاً فعال بوده، حالا که برنامه روی صفحه است دوباره فعالش کن
        if (prefs.autoRecord && !RecorderService.isArmed && hasPhonePerms()) {
            RecorderService.send(this, RecorderService.ACTION_ARM)
        }
        refreshList()
        tick()
        ui.post { showStartupDialogs() }
    }

    private fun isNightNow() = (resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hasPhonePerms() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun snack(msg: String, long: Boolean = true) {
        val sb = Snackbar.make(
            findViewById(android.R.id.content), msg,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        )
        sb.setAnchorView(R.id.bottomNav)
        sb.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 6
        sb.show()
    }

    // ------------------------------------------------------------------ دیالوگ‌های شروع
    private fun showStartupDialogs() {
        if (isFinishing || isDestroyed) return
        val crash = CrashLogger.pending(this)
        if (crash != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("برنامه دفعه‌ی قبل بسته شد")
                .setMessage("جزئیات خطا ذخیره شده است. با «کپی» می‌توانید آن را برای توسعه‌دهنده بفرستید.\n\n" +
                    crash.take(900))
                .setPositiveButton("کپی") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", crash))
                    CrashLogger.clear(this)
                    Toast.makeText(this, "کپی شد", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("بستن") { _, _ -> CrashLogger.clear(this) }
                .setOnCancelListener { CrashLogger.clear(this) }
                .show()
            return
        }
        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            showHelpDialog()
        }
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("راهنمای ضبط تماس")
            .setMessage(
                "۱. دکمه‌ی ضبط را بزنید یا «ضبط خودکار با تماس» را روشن کنید.\n\n" +
                "۲. در حین تماس، بلندگو را روشن کنید تا صدای طرف مقابل هم با میکروفون ضبط شود.\n\n" +
                "۳. اندروید جلوی دسترسی مستقیم برنامه‌ها به صدای خط تماس را گرفته؛ برنامه به‌طور خودکار " +
                "چند منبع صدا را امتحان می‌کند. اگر ضبط بی‌صدا بود در تنظیمات «تست منابع صدا» را بزنید.\n\n" +
                "۴. برای ضبط خودکار، برنامه را یک‌بار باز کنید و استثنای باتری را بدهید."
            )
            .setPositiveButton("متوجه شدم", null)
            .show()
    }

    // ------------------------------------------------------------------ ناوبری
    private fun setupNav() {
        bottomNav.setOnItemSelectedListener {
            pageRecord.visibility = if (it.itemId == R.id.nav_record) View.VISIBLE else View.GONE
            pageLibrary.visibility = if (it.itemId == R.id.nav_library) View.VISIBLE else View.GONE
            pageSettings.visibility = if (it.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            if (it.itemId == R.id.nav_library) refreshList()
            true
        }
    }

    // ------------------------------------------------------------------ صفحه‌ی ضبط
    private fun setupRecordPage() {
        tvTimer = pageRecord.findViewById(R.id.tvTimer)
        tvState = pageRecord.findViewById(R.id.tvState)
        tvSource = pageRecord.findViewById(R.id.tvSource)
        tvLog = pageRecord.findViewById(R.id.tvLog)
        logCard = pageRecord.findViewById(R.id.logCard)
        levelView = pageRecord.findViewById(R.id.levelView)
        recButton = pageRecord.findViewById(R.id.recButton)
        imgRec = pageRecord.findViewById(R.id.imgRec)
        ring = pageRecord.findViewById(R.id.ring)
        swAuto = pageRecord.findViewById(R.id.swAuto)

        recButton.setOnClickListener { toggleRecording() }

        syncingSwitch = true
        swAuto.isChecked = prefs.autoRecord
        syncingSwitch = false
        swAuto.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            armToggledAt = System.currentTimeMillis()
            if (checked) {
                if (!hasPhonePerms()) {
                    syncingSwitch = true; swAuto.isChecked = false; syncingSwitch = false
                    requestNeededPermissions()
                    snack("اول مجوزهای میکروفون و وضعیت تلفن را بدهید، بعد دوباره روشن کنید")
                } else {
                    prefs.autoRecord = true
                    RecorderService.send(this, RecorderService.ACTION_ARM)
                }
            } else {
                prefs.autoRecord = false
                RecorderService.send(this, RecorderService.ACTION_DISARM)
            }
        }
        pageRecord.findViewById<MaterialSwitch>(R.id.swSpeaker).apply {
            isChecked = prefs.forceSpeaker
            setOnCheckedChangeListener { _, c -> prefs.forceSpeaker = c }
        }
    }

    private fun toggleRecording() {
        if (RecorderService.isRecording) {
            RecorderService.send(this, RecorderService.ACTION_STOP)
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestNeededPermissions(); return
            }
            RecorderService.send(this, RecorderService.ACTION_START)
        }
    }

    private fun applyRecordingVisuals(rec: Boolean) {
        recButton.setBackgroundResource(if (rec) R.drawable.bg_rec_active else R.drawable.bg_hero_btn)
        imgRec.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_mic)
        pulse?.cancel(); ringAnim?.cancel()
        if (rec) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                recButton,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
            ).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1300
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    ring.scaleX = 1f + f * 0.45f
                    ring.scaleY = 1f + f * 0.45f
                    ring.alpha = 0.8f * (1f - f)
                }
                start()
            }
        } else {
            pulse = null; ringAnim = null
            recButton.scaleX = 1f; recButton.scaleY = 1f
            ring.alpha = 0f
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun tick() {
        val rec = RecorderService.isRecording
        if (rec != lastRecording) {
            applyRecordingVisuals(rec)
            if (!rec) refreshList()
            lastRecording = rec
        }
        if (rec) {
            tvTimer.text = fmt(System.currentTimeMillis() - RecorderService.startTime)
            tvState.text = "در حال ضبط…"
            tvSource.text = "منبع فعال: " + RecorderService.activeSource.substringBefore(" (")
            levelView.push(RecorderService.level / 100f)
        } else {
            tvState.text = if (RecorderService.isArmed) "آماده‌باش · منتظر تماس" else "آماده‌ی ضبط"
            tvSource.text = "منبع صدا: " + if (prefs.sourceMode == 0) "خودکار"
            else Prefs.SOURCES[prefs.sourceMode - 1].short
            levelView.push(0f)
        }
        val log = RecorderService.log
        logCard.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
        tvLog.text = log.trim()

        // همگام‌سازی کلید آماده‌باش با وضعیت واقعی سرویس
        if (System.currentTimeMillis() - armToggledAt > 2000 && swAuto.isChecked != RecorderService.isArmed &&
            (RecorderService.serviceAlive || !prefs.autoRecord)
        ) {
            syncingSwitch = true; swAuto.isChecked = RecorderService.isArmed; syncingSwitch = false
        }

        // پیام نتیجه‌ی ضبط یا خطا
        if (RecorderService.eventSeq != lastEventSeq) {
            lastEventSeq = RecorderService.eventSeq
            snack(RecorderService.eventMsg)
        }
        updatePlayerProgress()
        ui.postDelayed({ tick() }, 120)
    }

    // ------------------------------------------------------------------ کتابخانه
    private fun setupLibraryPage() {
        listBox = pageLibrary.findViewById(R.id.listBox)
        emptyView = pageLibrary.findViewById(R.id.emptyView)
        statsCard = pageLibrary.findViewById(R.id.statsCard)
        statCount = pageLibrary.findViewById(R.id.statCount)
        statPeople = pageLibrary.findViewById(R.id.statPeople)
        statTime = pageLibrary.findViewById(R.id.statTime)
        statSize = pageLibrary.findViewById(R.id.statSize)
        selectBar = pageLibrary.findViewById(R.id.selectBar)
        tvSelCount = pageLibrary.findViewById(R.id.tvSelCount)
        etSearch = pageLibrary.findViewById(R.id.etSearch)
        grouped = prefs.libGrouped
        sortMode = SortMode.values()[prefs.libSort.coerceIn(0, SortMode.values().size - 1)]

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString() ?: ""; renderList() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fun chipSetup(id: Int, on: (Boolean) -> Unit) =
            pageLibrary.findViewById<Chip>(id).setOnCheckedChangeListener { _, c -> on(c) }
        val chipAll = pageLibrary.findViewById<Chip>(R.id.chipAll)
        val chipIn = pageLibrary.findViewById<Chip>(R.id.chipIn)
        val chipOut = pageLibrary.findViewById<Chip>(R.id.chipOut)
        fun setDir(d: DirFilter) {
            dirFilter = d
            chipAll.isChecked = d == DirFilter.ALL
            chipIn.isChecked = d == DirFilter.INCOMING
            chipOut.isChecked = d == DirFilter.OUTGOING
            renderList()
        }
        chipAll.setOnClickListener { setDir(DirFilter.ALL) }
        chipIn.setOnClickListener { setDir(if (dirFilter == DirFilter.INCOMING) DirFilter.ALL else DirFilter.INCOMING) }
        chipOut.setOnClickListener { setDir(if (dirFilter == DirFilter.OUTGOING) DirFilter.ALL else DirFilter.OUTGOING) }
        chipSetup(R.id.chipEnh) { onlyEnhanced = it; renderList() }

        val btnView = pageLibrary.findViewById<MaterialButton>(R.id.btnViewMode)
        fun syncViewIcon() {
            btnView.setIconResource(if (grouped) R.drawable.ic_group else R.drawable.ic_list)
            btnView.contentDescription = if (grouped) "نمای دسته‌بندی‌شده" else "نمای فهرست"
        }
        syncViewIcon()
        btnView.setOnClickListener {
            grouped = !grouped; prefs.libGrouped = grouped; syncViewIcon(); renderList()
            snack(if (grouped) "دسته‌بندی بر اساس مخاطب" else "فهرست زمانی", false)
        }
        pageLibrary.findViewById<View>(R.id.btnSort).setOnClickListener { showSortDialog() }
        pageLibrary.findViewById<View>(R.id.btnPerm).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), 2)
        }
        pageLibrary.findViewById<View>(R.id.btnSelClose).setOnClickListener { clearSelection() }
        pageLibrary.findViewById<View>(R.id.btnSelAll).setOnClickListener {
            if (selected.size == visibleRecs.size) selected.clear()
            else { selected.clear(); visibleRecs.forEach { selected.add(it.path) } }
            renderList()
        }
        pageLibrary.findViewById<View>(R.id.btnSelDelete).setOnClickListener { confirmDeleteSelected() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selected.isNotEmpty()) clearSelection()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    private fun showSortDialog() {
        val labels = arrayOf("جدیدترین", "قدیمی‌ترین", "طولانی‌ترین", "پرحجم‌ترین", "نام مخاطب")
        MaterialAlertDialogBuilder(this)
            .setTitle("مرتب‌سازی")
            .setSingleChoiceItems(labels, sortMode.ordinal) { d, which ->
                sortMode = SortMode.values()[which]; prefs.libSort = which
                d.dismiss(); renderList()
            }.show()
    }

    private fun clearSelection() { selected.clear(); renderList() }

    /** از روی فایل‌ها لیست را می‌خواند (و مخاطب‌های نامشخص را از تاریخچه‌ی تماس پر می‌کند). */
    private fun refreshList() {
        val dir = RecorderService.recordingsDir(this)
        val wavs = dir.listFiles { f -> f.extension == "wav" }?.toList() ?: emptyList()
        CallMeta.resolvePending(this, wavs)
        allRecs = wavs.map { f ->
            val m = CallMeta.read(f)
            Rec(
                path = f.absolutePath, fileName = f.name, startMs = CallMeta.startOf(f),
                durationMs = WavWriter.durationMs(f), sizeBytes = f.length(),
                number = m.number, name = m.name, direction = m.direction,
                enhanced = f.name.endsWith("_enhanced.wav"),
            )
        }
        selected.retainAll(allRecs.map { it.path }.toSet())
        renderList()
    }

    private fun initialOf(title: String): String {
        val c = title.trim().firstOrNull { it.isLetter() } ?: return ""
        return c.uppercaseChar().toString()
    }

    private fun bindAvatar(frame: View, tv: TextView, img: View, title: String) {
        val ini = initialOf(title)
        tv.text = ini
        tv.visibility = if (ini.isEmpty()) View.GONE else View.VISIBLE
        img.visibility = if (ini.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderList() {
        listBox.removeAllViews()
        val filtered = Library.sort(Library.filter(allRecs, query, dirFilter, onlyEnhanced), sortMode)
        visibleRecs = filtered
        val inSel = selected.isNotEmpty()
        selectBar.visibility = if (inSel) View.VISIBLE else View.GONE
        tvSelCount.text = "⁦${selected.size}⁩ انتخاب"
        pageLibrary.findViewById<View>(R.id.libHeader).visibility = if (inSel) View.GONE else View.VISIBLE
        pageLibrary.findViewById<View>(R.id.permCard).visibility =
            if (!CallMeta.hasCallLogPermission(this) && allRecs.isNotEmpty()) View.VISIBLE else View.GONE

        val none = filtered.isEmpty()
        emptyView.visibility = if (none) View.VISIBLE else View.GONE
        statsCard.visibility = if (allRecs.isEmpty()) View.GONE else View.VISIBLE
        pageLibrary.findViewById<TextView>(R.id.tvEmptyTitle).text =
            if (allRecs.isEmpty()) "هنوز ضبطی ندارید" else "موردی پیدا نشد"
        pageLibrary.findViewById<TextView>(R.id.tvEmptySub).text =
            if (allRecs.isEmpty()) "از صفحه‌ی «ضبط» اولین مکالمه‌ی خود را ضبط کنید" else "جستجو یا فیلتر را تغییر دهید"

        val inflater = LayoutInflater.from(this)
        if (grouped) {
            for (g in Library.groupByContact(filtered, sortMode)) {
                val h = inflater.inflate(R.layout.item_group_header, listBox, false)
                h.findViewById<TextView>(R.id.gTitle).text = bidi(g.title)
                val nums = if (g.subtitle.isNotBlank() && g.subtitle != g.title) "${bidi(g.subtitle)} · " else ""
                h.findViewById<TextView>(R.id.gSub).text =
                    "$nums⁦${g.items.size}⁩ ضبط · ⁦${fmt(g.totalMs)}⁩"
                bindAvatar(h.findViewById(R.id.gAvatar), h.findViewById(R.id.gInitial), h.findViewById(R.id.gPerson), g.title)
                val isCollapsed = g.key in collapsed
                h.findViewById<View>(R.id.gChevron).rotation = if (isCollapsed) 90f else 0f
                h.setOnClickListener {
                    if (selected.isNotEmpty()) {
                        val paths = g.items.map { it.path }
                        if (selected.containsAll(paths)) selected.removeAll(paths.toSet()) else selected.addAll(paths)
                    } else if (!collapsed.remove(g.key)) collapsed.add(g.key)
                    renderList()
                }
                h.setOnLongClickListener { selected.addAll(g.items.map { it.path }); renderList(); true }
                listBox.addView(h)
                if (!isCollapsed) g.items.forEach { listBox.addView(buildRow(inflater, it, true)) }
            }
        } else {
            filtered.forEach { listBox.addView(buildRow(inflater, it)) }
        }
        statCount.text = allRecs.size.toString()
        statPeople.text = allRecs.map { it.key }.toSet().size.toString()
        statTime.text = fmt(allRecs.sumOf { it.durationMs })
        statSize.text = "%.1f MB".format(allRecs.sumOf { it.sizeBytes } / 1048576.0)
    }

    private fun bidi(t: String) = "⁦$t⁩"

    private fun buildRow(inflater: LayoutInflater, r: Rec, inGroup: Boolean = false): View {
        val f = File(r.path)
        val row = inflater.inflate(R.layout.item_recording, listBox, false)
        val card = row as com.google.android.material.card.MaterialCardView
        val sel = r.path in selected
        row.findViewById<TextView>(R.id.tvName).text = bidi(r.title)
        val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(r.startMs))
        row.findViewById<TextView>(R.id.tvMeta).text =
            "⁦$date · ${fmt(r.durationMs)} · ${"%.1f".format(r.sizeBytes / 1048576.0)} MB⁩"
        val dirImg = row.findViewById<ImageView>(R.id.imgDir)
        when (r.direction) {
            1 -> dirImg.setImageResource(R.drawable.ic_incoming)
            2 -> dirImg.setImageResource(R.drawable.ic_outgoing)
            else -> dirImg.visibility = View.GONE
        }
        row.findViewById<View>(R.id.tvTag).visibility = if (r.enhanced) View.VISIBLE else View.GONE
        bindAvatar(row.findViewById(R.id.avatar), row.findViewById(R.id.tvInitial), row.findViewById(R.id.imgPerson), r.title)
        row.findViewById<View>(R.id.checkMark).visibility = if (sel) View.VISIBLE else View.GONE
        card.strokeColor = androidx.core.content.ContextCompat.getColor(this, if (sel) R.color.primary else R.color.outline)
        card.strokeWidth = if (sel) dp(2) else dp(1)

        val playing = playingPath == r.path && player?.isPlaying == true
        row.findViewById<MaterialButton>(R.id.btnPlay).apply {
            setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (playing) "توقف موقت" else "پخش"
            setOnClickListener { if (selected.isNotEmpty()) toggleSel(r.path) else playFile(f) }
        }
        row.findViewById<View>(R.id.btnMore).setOnClickListener { v -> showMore(v, f) }
        row.setOnClickListener { if (selected.isNotEmpty()) toggleSel(r.path) else playFile(f) }
        row.setOnLongClickListener { toggleSel(r.path); true }
        return row
    }

    private fun toggleSel(path: String) {
        if (!selected.remove(path)) selected.add(path)
        renderList()
    }

    /** عنوان خوانا برای پلیر: نام/شماره + تاریخ */
    private fun niceName(f: File): String {
        val m = CallMeta.read(f)
        val who = m.name.ifBlank { m.number }
        val d = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(CallMeta.startOf(f)))
        return if (who.isBlank()) "مکالمه ⁦$d⁩" else "${bidi(who)} · ⁦$d⁩"
    }

    private fun showMore(anchor: View, f: File) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 0, 0, "انتخاب برای حذف")
        m.menu.add(0, 5, 1, "بهبود کیفیت صدا")
        m.menu.add(0, 6, 2, "اشتراک‌گذاری")
        m.menu.add(0, 1, 3, "تغییر نام")
        m.menu.add(0, 7, 4, "ثبت نام/شماره‌ی مخاطب")
        m.menu.add(0, 2, 5, "ذخیره در پوشه‌ی Music")
        m.menu.add(0, 3, 6, "اطلاعات فایل")
        m.menu.add(0, 4, 7, "حذف")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> toggleSel(f.absolutePath)
                1 -> renameFile(f)
                2 -> exportToMusic(f)
                3 -> showInfo(f)
                4 -> confirmDelete(f)
                5 -> showEnhanceSheet(f)
                6 -> share(f)
                7 -> editContact(f)
            }
            true
        }
        m.show()
    }

    private fun editContact(f: File) {
        val cur = CallMeta.read(f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0)
        }
        val etName = EditText(this).apply { hint = "نام"; setText(cur.name) }
        val etNum = EditText(this).apply {
            hint = "شماره"; setText(cur.number); inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        box.addView(etName); box.addView(etNum)
        MaterialAlertDialogBuilder(this).setTitle("مخاطب این ضبط").setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                CallMeta.write(f, CallMeta.Info(etNum.text.toString().trim(), etName.text.toString().trim(), cur.direction, true))
                refreshList()
            }.setNegativeButton("لغو", null).show()
    }

    private fun confirmDelete(f: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف ضبط")
            .setMessage("فایل صوتی برای همیشه از گوشی پاک می‌شود.")
            .setPositiveButton("حذف") { _, _ ->
                deleteRecordings(listOf(f)); refreshList()
            }
            .setNegativeButton("لغو", null).show()
    }

    private fun confirmDeleteSelected() {
        val files = selected.map { File(it) }
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف ⁦${files.size}⁩ ضبط")
            .setMessage("فایل‌های صوتی انتخاب‌شده برای همیشه از گوشی پاک می‌شوند و قابل بازگشت نیستند.")
            .setPositiveButton("حذف همه") { _, _ ->
                val n = deleteRecordings(files)
                selected.clear(); refreshList()
                snack("⁦$n⁩ ضبط حذف شد", false)
            }
            .setNegativeButton("لغو", null).show()
    }

    /** فایل wav + اطلاعات مخاطب + نسخه‌ی خروجی‌گرفته در Music را کامل پاک می‌کند. */
    internal fun deleteRecordings(files: List<File>): Int {
        var n = 0
        for (f in files) {
            if (playingPath == f.absolutePath) closePlayer()
            val ok = !f.exists() || f.delete()
            CallMeta.metaFile(f).delete()
            deleteExportedCopies(f.name)
            if (ok) n++
        }
        return n
    }

    private fun deleteExportedCopies(displayName: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val uris = ArrayList<Uri>()
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(displayName, "Music/CallRecorder%"), null
            )?.use { c ->
                while (c.moveToNext())
                    uris.add(android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
            }
            for (u in uris) try { contentResolver.delete(u, null, null) } catch (_: Throwable) {}
        } catch (_: Throwable) {}
    }

    private fun renameFile(f: File) {
        val input = EditText(this).apply {
            setText(f.nameWithoutExtension)
            setSelectAllOnFocus(true)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("تغییر نام")
            .setView(input)
            .setPositiveButton("ذخیره") { _, _ ->
                val clean = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (clean.isEmpty()) return@setPositiveButton
                val target = File(f.parentFile, "$clean.wav")
                when {
                    target.exists() && target != f -> snack("فایلی با این نام وجود دارد")
                    f.renameTo(target) -> {
                        if (playingPath == f.absolutePath) closePlayer()
                        CallMeta.metaFile(f).renameTo(CallMeta.metaFile(target))
                        refreshList()
                    }
                    else -> snack("تغییر نام ممکن نشد")
                }
            }
            .setNegativeButton("لغو", null).show()
    }

    private fun exportToMusic(f: File) {
        if (Build.VERSION.SDK_INT < 29) {
            snack("در این نسخه‌ی اندروید از «اشتراک‌گذاری» استفاده کنید"); return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/CallRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            snack("در Music/CallRecorder ذخیره شد")
        } catch (t: Throwable) {
            snack("ذخیره ممکن نشد: ${t.message}")
        }
    }

    private fun showInfo(f: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("اطلاعات فایل")
            .setMessage(
                "نام: ${f.name}\nمدت: ${fmt(WavWriter.durationMs(f))}\n" +
                    "نرخ نمونه‌برداری: ${WavWriter.sampleRateOf(f)} Hz\n" +
                    "حجم: ${f.length() / 1024} KB\n\nمسیر:\n${f.absolutePath}"
            )
            .setPositiveButton("بستن", null).show()
    }

    private fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("audio/wav")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "اشتراک‌گذاری"
                )
            )
        } catch (t: Throwable) {
            snack("اشتراک‌گذاری ممکن نشد: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ پلیر
    private fun setupPlayer() {
        btnPlayPause = playerBar.findViewById(R.id.btnPlayPause)
        btnSpeed = playerBar.findViewById(R.id.btnSpeed)
        waveView = playerBar.findViewById(R.id.waveView)
        tvPlayerTitle = playerBar.findViewById(R.id.tvPlayerTitle)
        tvPlayerCur = playerBar.findViewById(R.id.tvPlayerCur)
        tvPlayerTotal = playerBar.findViewById(R.id.tvPlayerTotal)

        btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.start()
                updatePlayPauseIcon(); refreshListIfVisible()
            }
        }
        playerBar.findViewById<View>(R.id.btnPlayerClose).setOnClickListener { closePlayer() }
        playerBar.findViewById<View>(R.id.btnBack).setOnClickListener { seekBy(-10_000) }
        playerBar.findViewById<View>(R.id.btnFwd).setOnClickListener { seekBy(10_000) }
        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            applySpeed()
        }
        waveView.listener = object : WaveformView.Listener {
            override fun onScrub(fraction: Float) {
                val d = player?.duration ?: return
                tvPlayerCur.text = fmt((d * fraction).toLong())
            }
            override fun onSeek(fraction: Float) {
                val p = player ?: return
                p.seekTo((p.duration * fraction).toInt())
            }
        }
    }

    private fun seekBy(deltaMs: Int) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, p.duration))
    }

    private fun applySpeed() {
        val sp = speeds[speedIdx]
        btnSpeed.text = (if (sp == sp.toInt().toFloat()) sp.toInt().toString() else sp.toString()) + "×"
        val p = player ?: return
        try {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(sp)
            if (!wasPlaying && p.isPlaying) p.pause()
        } catch (_: Throwable) {}
    }

    fun playFile(f: File) {
        if (playingPath == f.absolutePath && player != null) {
            player?.let { if (it.isPlaying) it.pause() else it.start() }
            updatePlayPauseIcon(); refreshListIfVisible(); return
        }
        closePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    updatePlayPauseIcon(); refreshListIfVisible()
                }
            }
            playingPath = f.absolutePath
            applySpeed()
            val dur = player!!.duration.coerceAtLeast(1)
            tvPlayerTitle.text = niceName(f)
            tvPlayerTotal.text = fmt(dur.toLong())
            tvPlayerCur.text = fmt(0)
            waveView.peaks = FloatArray(0)
            waveView.progress = 0f
            playerBar.visibility = View.VISIBLE
            updatePlayPauseIcon()
            refreshListIfVisible()
            Thread {
                val peaks = try { WavWriter.readPeaks(f, 80) } catch (_: Throwable) { FloatArray(0) }
                ui.post { if (playingPath == f.absolutePath) waveView.peaks = peaks }
            }.start()
        } catch (e: Throwable) {
            snack("پخش ناموفق: ${e.message}")
            closePlayer()
        }
    }

    private fun updatePlayPauseIcon() {
        btnPlayPause.setIconResource(
            if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updatePlayerProgress() {
        val p = player ?: return
        try {
            val d = p.duration.coerceAtLeast(1)
            val pos = p.currentPosition
            waveView.progress = pos.toFloat() / d
            tvPlayerCur.text = fmt(pos.toLong())
        } catch (_: Throwable) {}
    }

    private fun closePlayer() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null; playingPath = null
        playerBar.visibility = View.GONE
        refreshListIfVisible()
    }

    private fun refreshListIfVisible() {
        if (pageLibrary.visibility == View.VISIBLE) refreshList()
    }

    // ------------------------------------------------------------------ تنظیمات
    private fun setupSettingsPage() {
        val s = pageSettings
        tvSourceValue = s.findViewById(R.id.tvSourceValue)
        tvRateValue = s.findViewById(R.id.tvRateValue)
        tvGainValue = s.findViewById(R.id.tvGainValue)

        refreshSettingsTexts()

        s.findViewById<View>(R.id.rowSource).setOnClickListener {
            val names = (listOf("خودکار (پیشنهادی)") + Prefs.SOURCES.map { it.label }).toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle("منبع صدا")
                .setSingleChoiceItems(names, prefs.sourceMode) { d, which ->
                    prefs.sourceMode = which; refreshSettingsTexts(); d.dismiss()
                }.show()
        }
        s.findViewById<View>(R.id.rowRate).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("نرخ نمونه‌برداری")
                .setSingleChoiceItems(
                    rates.map { "$it Hz" }.toTypedArray(),
                    rates.indexOf(prefs.sampleRate).coerceAtLeast(0)
                ) { d, which ->
                    prefs.sampleRate = rates[which]; refreshSettingsTexts(); d.dismiss()
                }.show()
        }
        s.findViewById<Slider>(R.id.sliderGain).apply {
            value = prefs.liveGainDb.toFloat()
            addOnChangeListener { _, v, _ ->
                prefs.liveGainDb = v.toInt(); tvGainValue.text = "${v.toInt()} dB"
            }
        }

        val group = s.findViewById<MaterialButtonToggleGroup>(R.id.toggleTheme)
        group.check(
            when (prefs.themeMode) { 1 -> R.id.btnThemeLight; 2 -> R.id.btnThemeDark; else -> R.id.btnThemeSystem }
        )
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) { R.id.btnThemeLight -> 1; R.id.btnThemeDark -> 2; else -> 0 }
            if (mode != prefs.themeMode) {
                prefs.themeMode = mode
                AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(mode))
            }
        }

        s.findViewById<View>(R.id.btnTestSources).setOnClickListener { runSourceTest() }
        s.findViewById<View>(R.id.btnA11y).setOnClickListener { showA11yGuide() }
        s.findViewById<View>(R.id.btnReport).setOnClickListener { shareReport() }
        updateA11yStatus()
        s.findViewById<View>(R.id.btnHelp).setOnClickListener { showHelpDialog() }
        s.findViewById<View>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                snack("قبلاً فعال شده است", false)
            } else {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Throwable) { snack("این صفحه در دستگاه شما در دسترس نیست") }
            }
        }
        s.findViewById<View>(R.id.btnEmail).setOnClickListener {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:shikmizban@gmail.com"))
                .putExtra(Intent.EXTRA_SUBJECT, "Call Recorder Pro")
            try { startActivity(i) } catch (_: Throwable) {
                Toast.makeText(this, "shikmizban@gmail.com", Toast.LENGTH_LONG).show()
            }
        }
        s.findViewById<TextView>(R.id.tvVersion).text = "Call Recorder Pro · v2.1"
    }

    private fun refreshSettingsTexts() {
        tvSourceValue.text = if (prefs.sourceMode == 0) "خودکار (پیشنهادی)"
        else Prefs.SOURCES[prefs.sourceMode - 1].short
        tvRateValue.text = "${prefs.sampleRate} Hz"
        tvGainValue.text = "${prefs.liveGainDb} dB"
    }

    override fun onResume() {
        super.onResume()
        if (::pageSettings.isInitialized) updateA11yStatus()
    }

    private fun updateA11yStatus() {
        val on = CallAccessibilityService.isEnabled(this)
        pageSettings.findViewById<TextView>(R.id.tvA11yStatus).text =
            if (on) "وضعیت: فعال ✓" else "وضعیت: غیرفعال"
        pageSettings.findViewById<MaterialButton>(R.id.btnA11y).text =
            if (on) "تنظیمات سرویس دسترسی" else "فعال‌سازی سرویس دسترسی"
    }

    private fun showA11yGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle("فعال‌سازی سرویس دسترسی")
            .setMessage(
                "۱. «رفتن به تنظیمات» را بزنید و در لیست، «Call Recorder Pro» را پیدا و روشن کنید.\n\n" +
                "۲. اگر گزینه خاکستری بود (اندروید ۱۳ به بالا): تنظیمات گوشی ← برنامه‌ها ← Call Recorder Pro ← " +
                "منوی سه‌نقطه‌ی بالا ← «Allow restricted settings»، سپس دوباره مرحله‌ی ۱.\n\n" +
                "۳. برگردید و یک تماس را روی بلندگو ضبط کنید.\n\n" +
                "این سرویس هیچ متن یا اطلاعاتی از صفحه نمی‌خواند. روی بعضی گوشی‌ها کمک می‌کند و روی بعضی تغییری ندارد."
            )
            .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Throwable) { snack("این صفحه در دستگاه شما در دسترس نیست") }
            }
            .setNeutralButton("اطلاعات برنامه") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun audioModeName(): String {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        return when (am.mode) {
            android.media.AudioManager.MODE_IN_CALL -> "در تماس (IN_CALL)"
            android.media.AudioManager.MODE_IN_COMMUNICATION -> "ارتباط صوتی (IN_COMMUNICATION)"
            android.media.AudioManager.MODE_RINGTONE -> "زنگ (RINGTONE)"
            else -> "عادی (NORMAL)"
        }
    }

    private fun buildReport(): String {
        fun perm(p: String) = if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "NO"
        return buildString {
            appendLine("Call Recorder Pro v2.1 - diagnostic report")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("audio mode now: ${audioModeName()}")
            appendLine("accessibility service enabled: ${CallAccessibilityService.isEnabled(this@MainActivity)}")
            appendLine("permissions: mic=${perm(Manifest.permission.RECORD_AUDIO)} phone=${perm(Manifest.permission.READ_PHONE_STATE)}")
            appendLine("settings: source=${prefs.sourceMode} rate=${prefs.sampleRate} liveGain=${prefs.liveGainDb} speaker=${prefs.forceSpeaker} armed=${RecorderService.isArmed}")
            appendLine()
            appendLine("last recording log:")
            appendLine(RecorderService.log.ifBlank { "(none)" })
            appendLine("last event: ok=${RecorderService.eventOk} ${RecorderService.eventMsg.replace('\n', ' ')}")
            appendLine()
            appendLine("last source test:")
            appendLine(RecorderService.lastTest.ifBlank { "(not run)" })
        }
    }

    private fun shareReport() {
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_EMAIL, arrayOf("shikmizban@gmail.com"))
                        .putExtra(Intent.EXTRA_SUBJECT, "Call Recorder Pro - گزارش عیب‌یابی")
                        .putExtra(Intent.EXTRA_TEXT, buildReport()),
                    "ارسال گزارش"
                )
            )
        } catch (t: Throwable) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("report", buildReport()))
            snack("گزارش کپی شد")
        }
    }

    private fun runSourceTest() {
        if (RecorderService.isRecording) { snack("اول ضبط جاری را متوقف کنید"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions(); return
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("تست منابع صدا")
            .setMessage("در حال تست… (حدود ۸ ثانیه)\nلطفاً کمی صحبت کنید یا صدا پخش کنید.")
            .setCancelable(false)
            .show()
        Thread {
            val res = try { SourceTester.run() } catch (_: Throwable) { emptyList() }
            ui.post {
                dlg.dismiss()
                val mode = audioModeName()
                val body = if (res.isEmpty()) "تست ممکن نشد."
                else res.joinToString("\n") { "• ${it.name}: ${it.describe()}" }
                RecorderService.lastTest = "audio mode: $mode\n" + body
                val inCallNow = mode.contains("IN_CALL") || mode.contains("IN_COMMUNICATION")
                val msg = "حالت صدای سیستم: $mode\n\n" + body + "\n\n" +
                    if (inCallNow) "این تست در حین تماس گرفته شد؛ منبع‌هایی که «سیگنال دارد» برای ضبط تماس قابل استفاده‌اند."
                    else "توجه: این تست خارج از تماس گرفته شد. ضبط در حین تماس ممکن است متفاوت باشد؛ اندروید در تماس معمولاً میکروفون را مسدود می‌کند."
                MaterialAlertDialogBuilder(this)
                    .setTitle("نتیجه‌ی تست")
                    .setMessage(msg)
                    .setPositiveButton("بستن", null)
                    .show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ شیت بهبود کیفیت
    fun showEnhanceSheet(file: File) {
        if (file.length() > 24L * 1024 * 1024) {
            snack("این فایل برای پردازش روی گوشی خیلی بزرگ است (حداکثر حدود ۲۴ مگابایت)")
            return
        }
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_enhance, null)
        dlg.setContentView(v)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true

        v.findViewById<TextView>(R.id.tvEnhanceFile).text = file.name
        val p = AudioProcessor.Params(0.5f, 90f, floatArrayOf(-4f, -2f, 0f, 3f, 2f), 0f, true, true)

        val box = v.findViewById<LinearLayout>(R.id.enhanceSliders)
        fun addSlider(title: String, min: Int, max: Int, init: Int, unit: String, onChange: (Int) -> Unit): Slider {
            val row = LayoutInflater.from(this).inflate(R.layout.row_slider, box, false)
            val tvT = row.findViewById<TextView>(R.id.tvRowTitle)
            val tvV = row.findViewById<TextView>(R.id.tvRowValue)
            val sl = row.findViewById<Slider>(R.id.rowSlider)
            tvT.text = title
            sl.valueFrom = min.toFloat(); sl.valueTo = max.toFloat()
            sl.value = init.toFloat().coerceIn(min.toFloat(), max.toFloat())
            tvV.text = "$init $unit"
            sl.addOnChangeListener { _, value, _ ->
                tvV.text = "${value.toInt()} $unit"; onChange(value.toInt())
            }
            box.addView(row)
            return sl
        }

        val noise = addSlider("کاهش نویز", 0, 100, (p.noiseStrength * 100).toInt(), "%") { p.noiseStrength = it / 100f }
        val hp = addSlider("فیلتر صدای بم و لرزش (High-pass)", 0, 300, p.highPassHz.toInt(), "Hz") { p.highPassHz = it.toFloat() }
        val eq = AudioProcessor.EQ_LABELS.mapIndexed { i, name ->
            addSlider("اکولایزر · $name", -12, 12, p.eqDb[i].toInt(), "dB") { p.eqDb[i] = it.toFloat() }
        }
        val gain = addSlider("بلند / کم کردن صدا", -12, 24, p.gainDb.toInt(), "dB") { p.gainDb = it.toFloat() }

        val swComp = v.findViewById<MaterialSwitch>(R.id.swCompress)
        val swNorm = v.findViewById<MaterialSwitch>(R.id.swNormalize)
        swComp.isChecked = p.compress; swNorm.isChecked = p.normalize
        swComp.setOnCheckedChangeListener { _, c -> p.compress = c }
        swNorm.setOnCheckedChangeListener { _, c -> p.normalize = c }

        val chips = v.findViewById<ChipGroup>(R.id.chipPresets)
        AudioProcessor.PRESETS.forEachIndexed { idx, preset ->
            if (idx == 0) return@forEachIndexed
            val chip = LayoutInflater.from(this).inflate(R.layout.chip_preset, chips, false) as Chip
            chip.text = preset.name
            chip.id = View.generateViewId()
            chip.setOnClickListener {
                val q = preset.p
                noise.value = (q.noiseStrength * 100)
                hp.value = q.highPassHz
                for (i in 0 until 5) eq[i].value = q.eqDb[i]
                gain.value = q.gainDb.coerceIn(-12f, 24f)
                swComp.isChecked = q.compress
                swNorm.isChecked = q.normalize
            }
            chips.addView(chip)
        }

        val progress = v.findViewById<LinearProgressIndicator>(R.id.enhanceProgress)
        val btn = v.findViewById<MaterialButton>(R.id.btnApply)
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "در حال پردازش…"
            progress.visibility = View.VISIBLE
            dlg.setCancelable(false)
            Thread {
                try {
                    val (samples, sr) = WavWriter.read(file)
                    val out = AudioProcessor.process(samples, sr, p) { pr ->
                        ui.post { progress.setProgressCompat((pr * 100).toInt(), true) }
                    }
                    val base = file.nameWithoutExtension.removeSuffix("_enhanced")
                    val target = File(file.parentFile, base + "_enhanced.wav")
                    WavWriter.writeAll(target, out, sr)
                    ui.post {
                        dlg.dismiss()
                        snack("نسخه‌ی بهبودیافته ذخیره شد")
                        refreshList()
                    }
                } catch (t: Throwable) {
                    ui.post {
                        dlg.dismiss()
                        snack("خطا در پردازش: ${t.javaClass.simpleName} ${t.message ?: ""}")
                    }
                }
            }.start()
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ مجوزها
    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.READ_CALL_LOG
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::pageLibrary.isInitialized) refreshList()
        val micIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (micIdx >= 0 && grantResults.getOrNull(micIdx) != PackageManager.PERMISSION_GRANTED) {
            MaterialAlertDialogBuilder(this)
                .setTitle("مجوز میکروفون لازم است")
                .setMessage("بدون مجوز میکروفون هیچ چیزی ضبط نمی‌شود. از تنظیمات برنامه، مجوز «میکروفون» را فعال کنید.")
                .setPositiveButton("باز کردن تنظیمات") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("بعداً", null)
                .show()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        pulse?.cancel(); ringAnim?.cancel()
        try { player?.release() } catch (_: Throwable) {}
        player = null
        super.onDestroy()
    }
}
