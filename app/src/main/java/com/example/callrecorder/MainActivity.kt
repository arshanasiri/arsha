package com.example.callrecorder

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
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
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var recBtn: Button
    private lateinit var listBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingPath: String? = null
    private var lastRecording = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)

        title = "Call Recorder Pro"
        buildRecordSection()
        buildSettingsSection()
        buildListSection()

        requestNeededPermissions()
        refreshList()
        tick()
    }

    // ---------------------------------------------------------------- UI helpers
    private fun header(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(20), 0, dp(6))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun label(parent: LinearLayout, text: String): TextView {
        val t = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, dp(8), 0, 0) }
        parent.addView(t)
        return t
    }

    private fun seek(
        parent: LinearLayout, title: String, min: Int, max: Int, init: Int, unit: String,
        onChange: (Int) -> Unit
    ): SeekBar {
        val tv = label(parent, "$title: $init $unit")
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = init - min
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$title: ${p + min} $unit"
                    onChange(p + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(sb)
        return sb
    }

    // ---------------------------------------------------------------- ضبط
    private fun buildRecordSection() {
        statusText = TextView(this).apply { textSize = 15f; text = "آماده" }
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        recBtn = Button(this).apply {
            text = "▶ شروع ضبط"
            textSize = 18f
            setOnClickListener { toggleRecording() }
        }
        logText = TextView(this).apply { textSize = 12f; setPadding(0, dp(6), 0, 0) }
        root.addView(recBtn)
        root.addView(statusText)
        root.addView(levelBar)
        root.addView(logText)
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

    // ---------------------------------------------------------------- تنظیمات
    private fun buildSettingsSection() {
        header("تنظیمات ضبط")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)

        label(box, "منبع صدا (در صورت خطا یا سکوت، خودکار منبع بعدی امتحان می‌شود)")
        val names = listOf("خودکار (پیشنهادی)") + Prefs.SOURCES.map { it.label }
        box.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(prefs.sourceMode)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.sourceMode = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        box.addView(CheckBox(this).apply {
            text = "روشن کردن خودکار بلندگو (برای ضبط صدای طرف مقابل با میکروفون)"
            isChecked = prefs.forceSpeaker
            setOnCheckedChangeListener { _, c -> prefs.forceSpeaker = c }
        })
        box.addView(CheckBox(this).apply {
            text = "شروع/توقف خودکار ضبط با تماس"
            isChecked = prefs.autoRecord
            setOnCheckedChangeListener { _, c -> prefs.autoRecord = c }
        })

        label(box, "نرخ نمونه‌برداری")
        val rates = listOf(8000, 16000, 44100, 48000)
        box.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rates.map { "$it Hz" }
            )
            setSelection(rates.indexOf(prefs.sampleRate).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.sampleRate = rates[pos] }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })

        seek(box, "تقویت زنده هنگام ضبط", 0, 24, prefs.liveGainDb, "dB") { prefs.liveGainDb = it }

        box.addView(Button(this).apply {
            text = "مجوز «نمایش روی سایر برنامه‌ها» (برای ضبط خودکار)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })
        box.addView(Button(this).apply {
            text = "استثنا از بهینه‌سازی باتری"
            setOnClickListener {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this@MainActivity, "قبلاً فعال شده", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })
    }

    // ---------------------------------------------------------------- لیست ضبط‌ها
    private fun buildListSection() {
        header("ضبط‌های من")
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val files = RecorderService.recordingsDir(this).listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "هنوز چیزی ضبط نشده." })
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val dur = if (f.length() > 44) "%.0f ثانیه".format((f.length() - 44) / 2.0 / sampleRateOf(f)) else "-"
            row.addView(TextView(this).apply {
                text = "${f.name}\n$dur · ${f.length() / 1024} KB · " +
                    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(f.lastModified()))
                textSize = 13f
            })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(t: String, a: () -> Unit) = btns.addView(Button(this).apply {
                text = t; textSize = 12f; setOnClickListener { a() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            btn(if (playingPath == f.absolutePath) "■ توقف" else "▶ پخش") { togglePlay(f) }
            btn("✨ بهبود") { showEnhanceDialog(f) }
            btn("اشتراک") { share(f) }
            btn("حذف") {
                AlertDialog.Builder(this).setMessage("حذف ${f.name}؟")
                    .setPositiveButton("حذف") { _, _ -> stopPlayer(); f.delete(); refreshList() }
                    .setNegativeButton("لغو", null).show()
            }
            row.addView(btns)
            listBox.addView(row)
        }
    }

    private fun sampleRateOf(f: File): Int = try {
        f.inputStream().use { s ->
            val h = ByteArray(28); s.read(h)
            (h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8) or
                ((h[26].toInt() and 0xFF) shl 16) or ((h[27].toInt() and 0xFF) shl 24)
        }.coerceAtLeast(8000)
    } catch (_: Throwable) { 16000 }

    // ---------------------------------------------------------------- پخش و اشتراک
    private fun stopPlayer() {
        try { player?.stop() } catch (_: Throwable) {}
        player?.release(); player = null; playingPath = null
    }

    private fun togglePlay(f: File) {
        val same = playingPath == f.absolutePath
        stopPlayer()
        if (!same) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare(); start()
                    setOnCompletionListener { stopPlayer(); refreshList() }
                }
                playingPath = f.absolutePath
            } catch (e: Throwable) {
                Toast.makeText(this, "پخش ناموفق: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        refreshList()
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

    // ---------------------------------------------------------------- پنجره‌ی بهبود کیفیت
    private fun showEnhanceDialog(file: File) {
        val p = AudioProcessor.Params(0.5f, 90f, floatArrayOf(-4f, -2f, 0f, 3f, 2f), 0f, true, true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val sc = ScrollView(this).apply { addView(box) }

        val seeks = ArrayList<SeekBar>()
        var loading = false
        val presetSpinner = Spinner(this)
        label(box, "پریست آماده")
        box.addView(presetSpinner)

        val noise = seek(box, "کاهش نویز", 0, 100, (p.noiseStrength * 100).toInt(), "%") { p.noiseStrength = it / 100f }
        val hp = seek(box, "فیلتر صدای بم/لرزش (High-pass)", 0, 300, p.highPassHz.toInt(), "Hz") { p.highPassHz = it.toFloat() }
        val eqSeeks = AudioProcessor.EQ_LABELS.mapIndexed { i, name ->
            seek(box, "اکولایزر $name", -12, 12, p.eqDb[i].toInt(), "dB") { p.eqDb[i] = it.toFloat() }
        }
        val gain = seek(box, "بلند/کم کردن صدا", -12, 24, p.gainDb.toInt(), "dB") { p.gainDb = it.toFloat() }
        val cbComp = CheckBox(this).apply {
            text = "کمپرسور (یکنواخت کردن صدای بلند و آرام)"; isChecked = p.compress
            setOnCheckedChangeListener { _, c -> p.compress = c }
        }
        val cbNorm = CheckBox(this).apply {
            text = "نرمال‌سازی (رساندن اوج صدا به -1dB)"; isChecked = p.normalize
            setOnCheckedChangeListener { _, c -> p.normalize = c }
        }
        box.addView(cbComp); box.addView(cbNorm)
        seeks.addAll(listOf(noise, hp, gain) + eqSeeks)

        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, AudioProcessor.PRESETS.map { it.name }
        )
        presetSpinner.setSelection(1)
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pa: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) return
                val q = AudioProcessor.PRESETS[pos].p
                noise.progress = (q.noiseStrength * 100).toInt()
                hp.progress = q.highPassHz.toInt()
                for (i in 0 until 5) eqSeeks[i].progress = q.eqDb[i].toInt() + 12
                gain.progress = q.gainDb.toInt() + 12
                cbComp.isChecked = q.compress
                cbNorm.isChecked = q.normalize
            }
            override fun onNothingSelected(pa: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("بهبود کیفیت: ${file.name}")
            .setView(sc)
            .setPositiveButton("پردازش و ذخیره") { _, _ -> runProcessing(file, p) }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun runProcessing(file: File, p: AudioProcessor.Params) {
        val dlg = ProgressDialog(this).apply {
            setMessage("در حال پردازش...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val (samples, sr) = WavWriter.read(file)
                val out = AudioProcessor.process(samples, sr, p) { pr ->
                    ui.post { dlg.progress = (pr * 100).toInt() }
                }
                val target = File(file.parentFile, file.nameWithoutExtension + "_enhanced.wav")
                WavWriter.writeAll(target, out, sr)
                ui.post {
                    dlg.dismiss()
                    Toast.makeText(this, "ذخیره شد: ${target.name}", Toast.LENGTH_LONG).show()
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

    // ---------------------------------------------------------------- مجوزها و به‌روزرسانی وضعیت
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

    private fun tick() {
        val rec = RecorderService.isRecording
        recBtn.text = if (rec) "■ توقف ضبط" else "▶ شروع ضبط"
        statusText.text = if (rec) "در حال ضبط · منبع: ${RecorderService.activeSource}\n${RecorderService.currentFile}" else "آماده"
        levelBar.progress = RecorderService.level
        logText.text = RecorderService.log
        if (lastRecording && !rec) refreshList()
        lastRecording = rec
        ui.postDelayed({ tick() }, 400)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopPlayer()
        super.onDestroy()
    }
}
