package com.example.callrecorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** هر منبع صدا را ~۱٫۲ ثانیه تست می‌کند تا کاربر ببیند کدام روی دستگاهش کار می‌کند. */
object SourceTester {

    class Result(val name: String, val opened: Boolean, val peak: Int, val rmsDb: Int) {
        val hasSignal: Boolean get() = opened && peak >= 2
        fun describe(): String = when {
            !opened -> "✗ باز نشد"
            peak < 2 -> "⚠ باز شد ولی فقط سکوت (مسدود)"
            else -> "✓ سیگنال دارد (اوج ${peak * 100 / 32768}٪ · ${rmsDb} dB)"
        }
    }

    /** باید روی ترد پس‌زمینه صدا زده شود. */
    fun run(onEach: (Int, Int) -> Unit = { _, _ -> }): List<Result> {
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val out = ArrayList<Result>()
        val total = Prefs.SOURCES.size
        Prefs.SOURCES.forEachIndexed { idx, s ->
            onEach(idx, total)
            var ar: AudioRecord? = null
            var opened = false
            var peak = 0
            var sumSq = 0.0
            var count = 0L
            try {
                ar = AudioRecord(
                    s.id, sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, sr)
                )
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    ar.startRecording()
                    if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        opened = true
                        val buf = ShortArray(1600)
                        val t0 = SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime() - t0 < 1200) {
                            val n = ar.read(buf, 0, buf.size)
                            if (n < 0) break
                            for (i in 0 until n) {
                                val a = abs(buf[i].toInt())
                                if (a > peak) peak = a
                                sumSq += buf[i].toDouble() * buf[i]
                            }
                            count += n
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { ar?.stop() } catch (_: Throwable) {}
                try { ar?.release() } catch (_: Throwable) {}
            }
            val rms = if (count > 0) sqrt(sumSq / count) else 0.0
            val db = if (rms > 0) (20 * log10(rms / 32768.0)).toInt() else -99
            out.add(Result(s.short, opened, peak, db))
        }
        return out
    }
}
