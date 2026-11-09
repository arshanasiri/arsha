package com.example.callrecorder

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
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
    private var pulse: ObjectAnimator? = null
    private var ringAnim: ValueAnimator? = null
    private var lastRecording = false

    // کتابخانه
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: View
    private lateinit var tvCount: TextView

    // پلیر
    private lateinit var playerBar: View
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var playerSlider: Slider
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvPlayerCur: TextView
    private lateinit var tvPlayerTotal: TextView
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var userSeeking = false

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
        refreshList()
        tick()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ ناوبری
    private fun setupNav() {
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener {
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

        recButton.setOnClickListener { toggleRecording() }

        pageRecord.findViewById<MaterialSwitch>(R.id.swAuto).apply {
            isChecked = prefs.autoRecord
            setOnCheckedChangeListener { _, c -> prefs.autoRecord = c }
        }
        pageRecord.findViewById<MaterialSwitch>(R.id.swSpeaker).apply {
            isChecked = prefs.forceSpeaker
            setOnCheckedChangeListener { _, c -> prefs.forceSpeaker = c }
        }
    }

    private fun toggleRecording() {
        if (RecorderService.isRecording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestNeededPermissions(); return
            }
            startForegroundService(
                Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START)
            )
        }
    }

    private fun applyRecordingVisuals(rec: Boolean) {
        recButton.setBackgroundResource(if (rec) R.drawable.bg_rec_active else R.drawable.bg_hero_btn)
        imgRec.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_mic)
        if (rec) {
            pulse?.cancel()
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                recButton,
                android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f)
            ).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            ringAnim?.cancel()
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
            pulse?.cancel(); pulse = null
            ringAnim?.cancel(); ringAnim = null
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
            tvState.text = "آماده‌ی ضبط"
            tvSource.text = "منبع صدا: " + if (prefs.sourceMode == 0) "خودکار"
            else Prefs.SOURCES[prefs.sourceMode - 1].short
            levelView.push(0f)
        }
        val log = RecorderService.log
        logCard.visibility = if (log.isBlank()) View.GONE else View.VISIBLE
        tvLog.text = log.trim()
        updatePlayerProgress()
        ui.postDelayed({ tick() }, 120)
    }

    // ------------------------------------------------------------------ کتابخانه
    private fun setupLibraryPage() {
        listBox = pageLibrary.findViewById(R.id.listBox)
        emptyView = pageLibrary.findViewById(R.id.emptyView)
        tvCount = pageLibrary.findViewById(R.id.tvCount)
    }

    private fun sampleRateOf(f: File): Int = try {
        f.inputStream().use { s ->
            val h = ByteArray(28); s.read(h)
            (h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8) or
                ((h[26].toInt() and 0xFF) shl 16) or ((h[27].toInt() and 0xFF) shl 24)
        }.coerceAtLeast(8000)
    } catch (_: Throwable) { 16000 }

    private fun refreshList() {
        listBox.removeAllViews()
        val files = RecorderService.recordingsDir(this).listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        tvCount.text = "${files.size} فایل"
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (f in files) {
            val row = inflater.inflate(R.layout.item_recording, listBox, false)
            val secs = if (f.length() > 44) ((f.length() - 44) / 2 / sampleRateOf(f)) else 0L
            val enhanced = f.name.endsWith("_enhanced.wav")
            row.findViewById<TextView>(R.id.tvName).text =
                SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(f.lastModified()))
            row.findViewById<TextView>(R.id.tvMeta).text =
                "${fmt(secs * 1000)} · ${f.length() / 1024} KB"
            row.findViewById<View>(R.id.tvTag).visibility = if (enhanced) View.VISIBLE else View.GONE

            val playing = playingPath == f.absolutePath && player?.isPlaying == true
            row.findViewById<MaterialButton>(R.id.btnPlay).apply {
                text = if (playing) "توقف موقت" else "پخش"
                setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                setOnClickListener { playFile(f) }
            }
            row.findViewById<View>(R.id.btnEnhance).setOnClickListener { showEnhanceSheet(f) }
            row.findViewById<View>(R.id.btnShare).setOnClickListener { share(f) }
            row.findViewById<View>(R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("حذف ضبط")
                    .setMessage("این فایل برای همیشه حذف شود؟")
                    .setPositiveButton("حذف") { _, _ ->
                        if (playingPath == f.absolutePath) closePlayer()
                        f.delete(); refreshList()
                    }
                    .setNegativeButton("لغو", null).show()
            }
            listBox.addView(row)
        }
    }

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("audio/wav")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "اشتراک‌گذاری"
            )
        )
    }

    // ------------------------------------------------------------------ پلیر
    private fun setupPlayer() {
        btnPlayPause = playerBar.findViewById(R.id.btnPlayPause)
        playerSlider = playerBar.findViewById(R.id.playerSlider)
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
        playerSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { userSeeking = true }
            override fun onStopTrackingTouch(slider: Slider) {
                player?.seekTo(slider.value.toInt()); userSeeking = false
            }
        })
        playerSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) tvPlayerCur.text = fmt(value.toLong())
        }
    }

    private fun playFile(f: File) {
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
            val dur = player!!.duration.coerceAtLeast(1)
            playerSlider.valueFrom = 0f
            playerSlider.valueTo = dur.toFloat()
            playerSlider.value = 0f
            tvPlayerTitle.text = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(f.lastModified()))
            tvPlayerTotal.text = fmt(dur.toLong())
            playerBar.visibility = View.VISIBLE
            updatePlayPauseIcon()
            refreshListIfVisible()
        } catch (e: Throwable) {
            Toast.makeText(this, "پخش ناموفق: ${e.message}", Toast.LENGTH_LONG).show()
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
        if (userSeeking) return
        try {
            val pos = p.currentPosition
            val max = playerSlider.valueTo
            playerSlider.value = pos.toFloat().coerceIn(0f, max)
            tvPlayerCur.text = fmt(pos.toLong())
        } catch (_: Throwable) {}
    }

    private fun closePlayer() {
        try { player?.stop() } catch (_: Throwable) {}
        player?.release(); player = null; playingPath = null
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
            AlertDialog.Builder(this)
                .setTitle("منبع صدا")
                .setSingleChoiceItems(names, prefs.sourceMode) { d, which ->
                    prefs.sourceMode = which; refreshSettingsTexts(); d.dismiss()
                }.show()
        }
        s.findViewById<View>(R.id.rowRate).setOnClickListener {
            AlertDialog.Builder(this)
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

        val group = s.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleTheme)
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

        s.findViewById<View>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        s.findViewById<View>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "قبلاً فعال شده است", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        s.findViewById<View>(R.id.btnEmail).setOnClickListener {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:shikmizban@gmail.com"))
                .putExtra(Intent.EXTRA_SUBJECT, "Call Recorder Pro")
            try { startActivity(i) } catch (_: Throwable) {
                Toast.makeText(this, "shikmizban@gmail.com", Toast.LENGTH_LONG).show()
            }
        }
        s.findViewById<TextView>(R.id.tvVersion).text = "Call Recorder Pro · v1.0"
    }

    private fun refreshSettingsTexts() {
        tvSourceValue.text = if (prefs.sourceMode == 0) "خودکار (پیشنهادی)"
        else Prefs.SOURCES[prefs.sourceMode - 1].short
        tvRateValue.text = "${prefs.sampleRate} Hz"
        tvGainValue.text = "${prefs.liveGainDb} dB"
    }

    // ------------------------------------------------------------------ شیت بهبود کیفیت
    private fun showEnhanceSheet(file: File) {
        val dlg = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_enhance, null)
        dlg.setContentView(v)
        dlg.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true

        v.findViewById<TextView>(R.id.tvEnhanceFile).text = file.name
        val p = AudioProcessor.Params(0.5f, 90f, floatArrayOf(-4f, -2f, 0f, 3f, 2f), 0f, true, true)

        val box = v.findViewById<LinearLayout>(R.id.enhanceSliders)
        val sliders = ArrayList<Slider>()
        fun addSlider(title: String, min: Int, max: Int, init: Int, unit: String, onChange: (Int) -> Unit): Slider {
            val row = LayoutInflater.from(this).inflate(R.layout.row_slider, box, false)
            val tvT = row.findViewById<TextView>(R.id.tvRowTitle)
            val tvV = row.findViewById<TextView>(R.id.tvRowValue)
            val sl = row.findViewById<Slider>(R.id.rowSlider)
            tvT.text = title
            sl.valueFrom = min.toFloat(); sl.valueTo = max.toFloat()
            sl.value = init.toFloat().coerceIn(min.toFloat(), max.toFloat())
            tvV.text = "${init} $unit"
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
        sliders.addAll(listOf(noise, hp, gain) + eq)

        val swComp = v.findViewById<MaterialSwitch>(R.id.swCompress)
        val swNorm = v.findViewById<MaterialSwitch>(R.id.swNormalize)
        swComp.isChecked = p.compress; swNorm.isChecked = p.normalize
        swComp.setOnCheckedChangeListener { _, c -> p.compress = c }
        swNorm.setOnCheckedChangeListener { _, c -> p.normalize = c }

        // پریست‌ها
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
                        Toast.makeText(this, "نسخه‌ی بهبودیافته ذخیره شد", Toast.LENGTH_LONG).show()
                        refreshList()
                    }
                } catch (t: Throwable) {
                    ui.post {
                        dlg.dismiss()
                        Toast.makeText(this, "خطا: ${t.message}", Toast.LENGTH_LONG).show()
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

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        pulse?.cancel(); ringAnim?.cancel()
        try { player?.release() } catch (_: Throwable) {}
        player = null
        super.onDestroy()
    }
}
