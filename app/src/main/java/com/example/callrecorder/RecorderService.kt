package com.example.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * سرویس ضبط. چند منبع صدا را به ترتیب امتحان می‌کند و اگر منبعی خطا بدهد یا
 * فقط سکوت دیجیتال تحویل دهد (که اندروید برای برنامه‌های عادی در تماس انجام می‌دهد)
 * به منبع بعدی می‌رود تا حداقل یکی کار کند.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.callrecorder.START"
        const val ACTION_STOP = "com.example.callrecorder.STOP"
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL_ID = "rec"
        private const val NOTIF_ID = 77

        @Volatile var isRecording = false
        @Volatile var startedAuto = false
        @Volatile var activeSource = "-"
        @Volatile var level = 0
        @Volatile var currentFile: String = ""
        @Volatile var log = ""

        fun recordingsDir(ctx: Context): File {
            val d = File(ctx.getExternalFilesDir(null), "Recordings")
            if (!d.exists()) d.mkdirs()
            return d
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedSpeaker = false
    private var speakerChanged = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                if (worker == null) stopSelfClean()
            }
            else -> {
                if (!isRecording && worker == null) {
                    startedAuto = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
                    goForeground()
                    running = true
                    worker = Thread({ recordLoop() }, "recorder").also { it.start() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("در حال ضبط مکالمه")
            .setContentIntent(open)
            .addAction(0, "توقف", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildChain(mode: Int): List<Prefs.Companion.Source> {
        val all = Prefs.SOURCES
        if (mode <= 0 || mode > all.size) {
            // ترتیب خودکار: بهترین کیفیت اول
            return all.filter { it.id != android.media.MediaRecorder.AudioSource.CAMCORDER } +
                all.filter { it.id == android.media.MediaRecorder.AudioSource.CAMCORDER }
        }
        val first = all[mode - 1]
        return listOf(first) + all.filter { it !== first }
    }

    private fun openSource(src: Int, sr: Int, bufBytes: Int): AudioRecord? {
        return try {
            val ar = AudioRecord(
                src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release(); return null
            }
            // حذف پردازش‌های سیستمی، مخصوصاً حذف اکو که صدای طرف مقابل را از بلندگو پاک می‌کند
            try {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.enabled = false
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.enabled = false
                if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(ar.audioSessionId)?.enabled = false
            } catch (_: Throwable) {}
            ar.startRecording()
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                ar.release(); return null
            }
            ar
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun recordLoop() {
        val prefs = Prefs(this)
        val sr = prefs.sampleRate
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf * 2, sr)
        val chunk = ShortArray(bufBytes / 4)
        val gain = 10.0.pow(prefs.liveGainDb / 20.0).toFloat()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(recordingsDir(this), "call_$stamp.wav")
        currentFile = file.name
        var writer: WavWriter? = null
        var record: AudioRecord? = null

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callrecorder:rec").apply { acquire(4 * 60 * 60 * 1000L) }

        if (prefs.forceSpeaker) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedSpeaker = am.isSpeakerphoneOn
            am.isSpeakerphoneOn = true
            speakerChanged = true
        }

        try {
            writer = WavWriter(file, sr)
            isRecording = true
            val chain = buildChain(prefs.sourceMode)
            var idx = 0
            log = ""

            while (running && idx < chain.size) {
                val s = chain[idx]
                record = openSource(s.id, sr, bufBytes)
                if (record == null) {
                    log += "✗ ${s.label}: باز نشد\n"
                    idx++
                    continue
                }
                activeSource = s.label
                val isLast = idx == chain.size - 1
                val probe = ArrayList<ShortArray>()
                var sawSignal = false
                val t0 = System.currentTimeMillis()
                var switchReason: String? = null

                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n < 0) { switchReason = "خطای خواندن ($n)"; break }
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        var v = (chunk[i] * gain).toInt()
                        if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                        chunk[i] = v.toShort()
                        val a = abs(v)
                        if (a > peak) peak = a
                    }
                    level = (peak * 100 / 32768)
                    if (!sawSignal) {
                        if (peak > 0) {
                            sawSignal = true
                            for (p in probe) writer.write(p, p.size)
                            probe.clear()
                            log += "✓ ${s.label}: سیگنال دریافت شد\n"
                            writer.write(chunk, n)
                        } else {
                            probe.add(chunk.copyOf(n))
                            if (!isLast && System.currentTimeMillis() - t0 > 2500) {
                                switchReason = "فقط سکوت دیجیتال (مسدود توسط سیستم)"
                                break
                            }
                            if (isLast) { // آخرین منبع: هرچه هست بنویس
                                for (p in probe) writer.write(p, p.size)
                                probe.clear()
                            }
                        }
                    } else {
                        writer.write(chunk, n)
                    }
                }

                try { record.stop() } catch (_: Throwable) {}
                record.release()
                record = null
                if (switchReason != null) {
                    log += "✗ ${s.label}: $switchReason\n"
                    idx++
                } else break
            }
        } catch (t: Throwable) {
            log += "خطا: ${t.message}\n"
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            record?.release()
            try { writer?.close() } catch (_: Throwable) {}
            if (speakerChanged) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.isSpeakerphoneOn = savedSpeaker
                speakerChanged = false
            }
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
            isRecording = false
            level = 0
            worker = null
            stopSelfClean()
        }
    }

    private fun stopSelfClean() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
