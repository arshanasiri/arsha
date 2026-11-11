package com.example.callrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * سرویس ضبط (Foreground، نوع microphone).
 *
 * دو حالت دارد:
 *  - آماده‌باش (armed): سرویس زنده می‌ماند و وضعیت تماس را زیر نظر دارد؛ با شروع تماس خودش ضبط را شروع می‌کند.
 *    چون سرویس وقتی برنامه روی صفحه بود شروع شده، اندروید 11 تا 15 اجازه‌ی استفاده از میکروفون را
 *    در پس‌زمینه هم به آن می‌دهد (برخلاف شروع سرویس از BroadcastReceiver که کرش می‌کرد).
 *  - ضبط: چند منبع صدا را به ترتیب امتحان می‌کند و اگر منبعی خطا یا فقط سکوت بدهد به بعدی می‌رود.
 *
 * هیچ فرمانی از این سرویس استثنا بیرون نمی‌دهد؛ خطاها به کاربر نشان داده می‌شوند.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_ARM = "com.example.callrecorder.ARM"
        const val ACTION_DISARM = "com.example.callrecorder.DISARM"
        const val ACTION_START = "com.example.callrecorder.START"
        const val ACTION_STOP = "com.example.callrecorder.STOP"
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL_ID = "rec"
        private const val NOTIF_ID = 77

        @Volatile var serviceAlive = false
        @Volatile var isRecording = false
        @Volatile var isArmed = false
        @Volatile var startedAuto = false
        @Volatile var inCall = false
        @Volatile var lastTest = ""
        @Volatile var startTime = 0L
        @Volatile var activeSource = "-"
        @Volatile var level = 0
        @Volatile var currentFile: String = ""
        @Volatile var log = ""
        @Volatile var eventSeq = 0
        @Volatile var eventMsg = ""
        @Volatile var eventOk = true

        fun recordingsDir(ctx: Context): File {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val d = File(base, "Recordings")
            if (!d.exists()) d.mkdirs()
            return d
        }

        fun postEvent(ok: Boolean, msg: String) {
            android.util.Log.i("CallRecorder", "event ok=$ok msg=$msg | log=${log.replace('\n', ';')}")
            eventOk = ok
            eventMsg = msg
            eventSeq++
        }

        /** ارسال امن فرمان به سرویس؛ هرگز استثنا نمی‌اندازد. */
        fun send(ctx: Context, action: String, auto: Boolean = false): Boolean {
            if ((action == ACTION_STOP || action == ACTION_DISARM) && !serviceAlive) return true
            return try {
                val i = Intent(ctx, RecorderService::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_AUTO, auto)
                ContextCompat.startForegroundService(ctx, i)
                true
            } catch (t: Throwable) {
                postEvent(false, "شروع سرویس ضبط ممکن نشد (${t.javaClass.simpleName}). برنامه را باز کنید و دوباره امتحان کنید.")
                false
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var sawAnySignal = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedSpeaker = false
    private var speakerChanged = false
    private var armed = false
    private var telCb: Any? = null
    private val effects = ArrayList<AudioEffect>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // اگر سرویس با startForegroundService آمده باید فوراً startForeground صدا زده شود.
        if (!ensureForeground()) {
            isRecording = false
            isArmed = false
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_ARM -> {
                armed = true; isArmed = true
                registerPhoneListener()
                refreshNotification()
            }
            ACTION_DISARM -> {
                armed = false; isArmed = false
                unregisterPhoneListener()
                if (worker == null) shutdown() else refreshNotification()
            }
            ACTION_START -> {
                if (worker == null) beginRecording(intent.getBooleanExtra(EXTRA_AUTO, false))
            }
            ACTION_STOP -> {
                running = false
                if (worker == null) afterRecording()
            }
            else -> if (!armed && worker == null) shutdown()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ نوتیفیکیشن
    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ضبط مکالمه", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun pi(action: String): PendingIntent = PendingIntent.getForegroundService(
        this, action.hashCode(),
        Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (isRecording) {
            b.setContentTitle("در حال ضبط…")
                .setContentText(
                    if (inCall) "برای ضبط صدای طرف مقابل، بلندگوی تماس را روشن کنید"
                    else "برای توقف از دکمه‌ی زیر استفاده کنید"
                )
                .addAction(0, "توقف ضبط", pi(ACTION_STOP))
        } else {
            b.setContentTitle("آماده‌باش ضبط خودکار")
                .setContentText("با شروع تماس، ضبط شروع می‌شود")
                .addAction(0, "شروع ضبط", pi(ACTION_START))
                .addAction(0, "غیرفعال", pi(ACTION_DISARM))
        }
        return b.build()
    }

    private fun refreshNotification() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}
    }

    private fun ensureForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (t: Throwable) {
        postEvent(
            false,
            "سیستم اجازه‌ی اجرای ضبط را نداد (${t.javaClass.simpleName}). برنامه را باز کنید و از داخل آن شروع کنید."
        )
        false
    }

    // ------------------------------------------------------------------ وضعیت تماس
    @Suppress("DEPRECATION")
    private fun registerPhoneListener() {
        if (telCb != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            postEvent(false, "مجوز «وضعیت تلفن» داده نشده؛ شروع خودکار با تماس کار نمی‌کند.")
            return
        }
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) { onCall(state) }
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), cb)
                telCb = cb
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) { onCall(state) }
                }
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                telCb = l
            }
        } catch (t: Throwable) {
            postEvent(false, "ثبت شنونده‌ی تماس ممکن نشد (${t.javaClass.simpleName}).")
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneListener() {
        val cb = telCb ?: return
        telCb = null
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31 && cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
            else if (cb is PhoneStateListener) tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        } catch (_: Throwable) {}
    }

    private fun onCall(state: Int) {
        if (!armed) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (worker == null) beginRecording(true)
            TelephonyManager.CALL_STATE_IDLE -> if (worker != null && startedAuto) running = false
        }
    }

    // ------------------------------------------------------------------ چرخه‌ی ضبط
    private fun beginRecording(auto: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            postEvent(false, "مجوز میکروفون داده نشده است.")
            afterRecording()
            return
        }
        startedAuto = auto
        running = true
        sawAnySignal = false
        log = ""
        activeSource = "-"
        startTime = System.currentTimeMillis()
        isRecording = true
        refreshNotification()
        worker = Thread({ recordLoop() }, "recorder").also { it.start() }
    }

    /** روی ترد اصلی صدا زده می‌شود. */
    private fun afterRecording() {
        worker = null
        isRecording = false
        level = 0
        if (armed) refreshNotification() else shutdown()
    }

    private fun shutdown() {
        unregisterPhoneListener()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        stopSelf()
    }

    private fun addLog(line: String) {
        if (log.length < 1800) log += line + "\n"
    }

    private fun buildChain(mode: Int, inCall: Boolean): List<Prefs.Companion.Source> {
        val all = Prefs.SOURCES
        val byId = all.associateBy { it.id }
        val auto = if (inCall) listOf(
            MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER
        ) else listOf(
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_CALL
        )
        val ordered = auto.mapNotNull { byId[it] }
        if (mode in 1..all.size) {
            val first = all[mode - 1]
            return listOf(first) + ordered.filter { it !== first }
        }
        return ordered
    }

    /** منبع PCM: یا AudioRecord واقعی، یا صدای مصنوعی (فقط برای تست خودکار). */
    private class Src(val rec: AudioRecord?, val sr: Int) {
        private var t = 0L
        fun read(buf: ShortArray): Int {
            val r = rec
            if (r != null) return r.read(buf, 0, buf.size)
            try { Thread.sleep(buf.size * 1000L / sr) } catch (_: InterruptedException) {}
            for (i in buf.indices) {
                val x = t++
                buf[i] = (9000 * sin(2 * PI * 440 * x / sr) + (Math.random() - 0.5) * 600).toInt().toShort()
            }
            return buf.size
        }
    }

    private fun openSource(src: Int, sr: Int, bufBytes: Int): Src? {
        if (BuildConfig.DEBUG && Prefs(this).fakeSource) return Src(null, sr)
        var ar: AudioRecord? = null
        return try {
            ar = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release(); return null
            }
            // پردازش‌های سیستمی خاموش می‌شوند؛ مخصوصاً حذف اکو که صدای طرف مقابل را از بلندگو پاک می‌کند.
            try {
                if (AcousticEchoCanceler.isAvailable())
                    AcousticEchoCanceler.create(ar.audioSessionId)?.also { it.enabled = false; effects.add(it) }
                if (NoiseSuppressor.isAvailable())
                    NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = false; effects.add(it) }
                if (AutomaticGainControl.isAvailable())
                    AutomaticGainControl.create(ar.audioSessionId)?.also { it.enabled = false; effects.add(it) }
            } catch (_: Throwable) {}
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.release(); return null
            }
            Src(ar, sr)
        } catch (_: Throwable) {
            try { ar?.release() } catch (_: Throwable) {}
            null
        }
    }

    private fun closeSource(src: Src?) {
        val rec = src?.rec
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        for (e in effects) try { e.release() } catch (_: Throwable) {}
        effects.clear()
    }

    /**
     * از یک منبع می‌خواند و می‌نویسد.
     * تا وقتی سیگنال نیامده چیزی نوشته نمی‌شود؛ اگر تا probeMs سیگنال نیامد (یا خطا شد) دلیل تعویض را برمی‌گرداند.
     * اگر کاربر توقف کند null برمی‌گرداند.
     */
    private fun capture(
        src: Src, writer: WavWriter, chunk: ShortArray, gain: Float,
        label: String, probeMs: Long
    ): String? {
        val probe = ArrayList<ShortArray>()
        var saw = false
        val t0 = SystemClock.elapsedRealtime()
        while (running) {
            val n = src.read(chunk)
            if (n < 0) return "خطای خواندن ($n)"
            if (n == 0) continue
            var peak = 0
            for (i in 0 until n) {
                var v = (chunk[i] * gain).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                chunk[i] = v.toShort()
                val a = abs(v)
                if (a > peak) peak = a
            }
            level = minOf(100, peak * 100 / 32768)
            if (saw) {
                writer.write(chunk, n)
                continue
            }
            if (peak >= 2) {
                saw = true
                sawAnySignal = true
                addLog("✓ $label: سیگنال دریافت شد")
                for (p in probe) writer.write(p, p.size)
                probe.clear()
                writer.write(chunk, n)
            } else {
                probe.add(chunk.copyOf(n))
                if (SystemClock.elapsedRealtime() - t0 > probeMs) {
                    return "فقط سکوت (منبع توسط سیستم مسدود است)"
                }
            }
        }
        return null
    }

    private fun forceSpeakerIfInCall(am: AudioManager) {
        try {
            if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                @Suppress("DEPRECATION")
                run {
                    savedSpeaker = am.isSpeakerphoneOn
                    am.isSpeakerphoneOn = true
                }
                speakerChanged = true
            }
        } catch (_: Throwable) {}
    }

    private fun recordLoop() {
        var writer: WavWriter? = null
        var current: Src? = null
        var file: File? = null
        var sr = 16000
        var inCallAtStart = false
        try {
            val prefs = Prefs(this)
            sr = prefs.sampleRate
            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                addLog("نرخ نمونه‌برداری $sr روی این دستگاه پشتیبانی نمی‌شود")
                return
            }
            val bufBytes = maxOf(minBuf * 2, sr)
            val chunk = ShortArray(maxOf(sr / 10, 256))
            val gain = 10.0.pow(prefs.liveGainDb / 20.0).toFloat()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            file = File(recordingsDir(this), "call_$stamp.wav")
            currentFile = file.name

            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callrecorder:rec")
                .apply { acquire(4 * 60 * 60 * 1000L) }

            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            inCallAtStart = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
            inCall = inCallAtStart
            addLog("حالت صدا: " + (if (inCallAtStart) "در تماس" else "عادی") +
                " · سرویس دسترسی: " + (if (CallAccessibilityService.isEnabled(this)) "فعال" else "غیرفعال"))
            if (inCallAtStart) refreshNotification()
            if (prefs.forceSpeaker) forceSpeakerIfInCall(am)

            writer = WavWriter(file, sr)
            val chain = buildChain(prefs.sourceMode, inCallAtStart)

            // تا وقتی کاربر توقف نکرده منابع را دوباره و دوباره امتحان می‌کنیم؛
            // ممکن است سیستم بعداً (مثلاً بعد از روشن شدن بلندگو) صدا را آزاد کند.
            var round = 0
            while (running) {
                for (s in chain) {
                    if (!running) break
                    val src = openSource(s.id, sr, bufBytes)
                    if (src == null) {
                        if (round == 0) addLog("✗ ${s.short}: باز نشد")
                        continue
                    }
                    current = src
                    activeSource = s.label
                    val heardBefore = sawAnySignal
                    val reason = capture(src, writer, chunk, gain, s.short, 1500)
                    closeSource(src)
                    current = null
                    if (reason == null) break
                    if (round == 0 || heardBefore != sawAnySignal) addLog("✗ ${s.short}: $reason")
                }
                if (!running) break
                round++
                if (round == 1 && !sawAnySignal) addLog("… سیستم صدا نمی‌دهد؛ مدام دوباره امتحان می‌کنم")
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
            }
        } catch (t: Throwable) {
            addLog("خطا: ${t.javaClass.simpleName} ${t.message ?: ""}")
        } finally {
            closeSource(current)
            try { writer?.close() } catch (_: Throwable) {}
            if (speakerChanged) {
                try {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = savedSpeaker
                } catch (_: Throwable) {}
                speakerChanged = false
            }
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}

            // نتیجه: فایل بی‌صدا هرگز نگه داشته نمی‌شود؛ به‌جایش دلیل و راه‌حل گفته می‌شود
            val f = file
            val dataBytes = if (f != null && f.exists()) maxOf(0L, f.length() - 44) else 0L
            val seconds = dataBytes / (sr * 2L)
            if (f != null && (!sawAnySignal || dataBytes < sr * 2L / 3)) {
                try { f.delete() } catch (_: Throwable) {}
                val hint = if (inCallAtStart)
                    "در تماس، اندروید معمولاً میکروفون را از برنامه‌های عادی می‌گیرد. تماس را روی بلندگو بگذارید، " +
                        "در تنظیمات «سرویس دسترسی» را فعال کنید و دوباره امتحان کنید. اگر باز هم نشد، از تنظیمات «ارسال گزارش» را بزنید."
                else
                    "صدایی از میکروفون نیامد. مجوز میکروفون را بررسی کنید و در تنظیمات «تست منابع صدا» را بزنید."
                postEvent(false, "صدایی ضبط نشد.\n$hint")
            } else {
                postEvent(true, "ضبط ذخیره شد (${seconds} ثانیه)")
                // تاریخچه‌ی تماس چند ثانیه بعد از قطع تماس نوشته می‌شود؛ بعداً مخاطب را پیدا کن
                val saved = f
                if (saved != null && CallMeta.hasCallLogPermission(this)) Thread {
                    for (delay in longArrayOf(4000, 12000)) {
                        try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                        CallMeta.resolvePending(applicationContext, listOf(saved))
                        if (CallMeta.read(saved).resolved) break
                    }
                }.start()
            }
            inCall = false
            main.post { afterRecording() }
        }
    }

    override fun onDestroy() {
        running = false
        unregisterPhoneListener()
        serviceAlive = false
        isArmed = false
        super.onDestroy()
    }
}
