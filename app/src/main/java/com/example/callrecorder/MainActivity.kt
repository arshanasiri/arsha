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
        if (intent.getBooleanExtra("skipIntro", false)) prefs.firstRunDone = true
        if (intent.getBooleanExtra("arm", false)) prefs.autoRecord = true
        if (intent.getBooleanExtra("fake", false)) prefs.fakeSource = true

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

        findViewById<MaterialButton>(R.id.btnTheme).setOnClickListener {
            val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            prefs.themeMode = if (night) 1 else 2
            AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(prefs.themeMode))
        }

        requestNeededPermissions()
        // اگر حالت آماده‌باش قبلاً فعال بوده، حالا که برنامه روی صفحه است دوباره فعالش کن
        if (prefs.autoRecord && !RecorderService.isArmed && hasPhonePerms()) {
            RecorderService.send(this, RecorderService.ACTION_ARM)
        }
        refreshList()
        tick()
        ui.post { showStartupDialogs() }
    }

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
        statTime = pageLibrary.findViewById(R.id.statTime)
        statSize = pageLibrary.findViewById(R.id.statSize)
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val files = RecorderService.recordingsDir(this).listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        statsCard.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        var totalMs = 0L
        var totalBytes = 0L
        val inflater = LayoutInflater.from(this)
        for (f in files) {
            val dur = WavWriter.durationMs(f)
            totalMs += dur
            totalBytes += f.length()
            val row = inflater.inflate(R.layout.item_recording, listBox, false)
            val enhanced = f.name.endsWith("_enhanced.wav")
            row.findViewById<TextView>(R.id.tvName).text = niceName(f)
            row.findViewById<TextView>(R.id.tvMeta).text =
                "\u2066${fmt(dur)} · ${"%.1f".format(f.length() / 1048576.0)} MB\u2069"
            row.findViewById<View>(R.id.tvTag).visibility = if (enhanced) View.VISIBLE else View.GONE

            val playing = playingPath == f.absolutePath && player?.isPlaying == true
            row.findViewById<MaterialButton>(R.id.btnPlay).apply {
                text = if (playing) "توقف موقت" else "پخش"
                setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                setOnClickListener { playFile(f) }
            }
            row.findViewById<View>(R.id.btnEnhance).setOnClickListener { showEnhanceSheet(f) }
            row.findViewById<View>(R.id.btnShare).setOnClickListener { share(f) }
            row.findViewById<View>(R.id.btnMore).setOnClickListener { v -> showMore(v, f) }
            listBox.addView(row)
        }
        statCount.text = files.size.toString()
        statTime.text = fmt(totalMs)
        statSize.text = "%.1f MB".format(totalBytes / 1048576.0)
    }

    /** عنوان خوانا؛ تاریخ و ساعت داخل یک بلوک چپ‌به‌راست تا ترتیب ارقام به‌هم نریزد. */
    private fun niceName(f: File): String {
        val m = Regex("""call_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""").find(f.nameWithoutExtension)
            ?: return f.nameWithoutExtension
        val (y, mo, d, h, mi, _) = m.destructured
        return "مکالمه \u2066$y/$mo/$d  $h:$mi\u2069"
    }

    private fun showMore(anchor: View, f: File) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "تغییر نام")
        m.menu.add(0, 2, 1, "ذخیره در پوشه‌ی Music")
        m.menu.add(0, 3, 2, "اطلاعات فایل")
        m.menu.add(0, 4, 3, "حذف")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> renameFile(f)
                2 -> exportToMusic(f)
                3 -> showInfo(f)
                4 -> confirmDelete(f)
            }
            true
        }
        m.show()
    }

    private fun confirmDelete(f: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف ضبط")
            .setMessage("این فایل برای همیشه حذف شود؟")
            .setPositiveButton("حذف") { _, _ ->
                if (playingPath == f.absolutePath) closePlayer()
                f.delete(); refreshList()
            }
            .setNegativeButton("لغو", null).show()
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
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
