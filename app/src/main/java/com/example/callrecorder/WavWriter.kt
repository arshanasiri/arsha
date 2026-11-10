package com.example.callrecorder

import java.io.File
import java.io.RandomAccessFile

/** نویسنده‌ی WAV (PCM 16-bit مونو). هدر هنگام close() اصلاح می‌شود. */
class WavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    val bytesWritten: Long get() = dataBytes

    init {
        raf.setLength(0)
        raf.write(ByteArray(44)) // جای هدر
    }

    fun write(samples: ShortArray, count: Int) {
        val buf = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            buf[2 * i] = (s and 0xFF).toByte()
            buf[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        raf.write(buf)
        dataBytes += buf.size
    }

    fun close() {
        try {
            raf.seek(0)
            raf.write(header(dataBytes, sampleRate))
        } finally {
            raf.close()
        }
    }

    companion object {
        fun header(dataLen: Long, sr: Int): ByteArray {
            val total = dataLen + 36
            val byteRate = sr * 2
            fun le32(v: Long) = byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
            )
            fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
            return "RIFF".toByteArray() + le32(total) + "WAVE".toByteArray() +
                "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(sr.toLong()) +
                le32(byteRate.toLong()) + le16(2) + le16(16) +
                "data".toByteArray() + le32(dataLen)
        }

        /** خواندن کامل یک WAV مونو 16 بیتی تولیدشده توسط همین برنامه. */
        fun read(file: File): Pair<ShortArray, Int> {
            val bytes = file.readBytes()
            val sr = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
            val n = (bytes.size - 44) / 2
            val out = ShortArray(n)
            for (i in 0 until n) {
                out[i] = ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()
            }
            return out to sr
        }

        /** نرخ نمونه‌برداری از هدر. */
        fun sampleRateOf(file: File): Int = try {
            file.inputStream().use { s ->
                val h = ByteArray(28)
                if (s.read(h) < 28) return 16000
                ((h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8) or
                    ((h[26].toInt() and 0xFF) shl 16) or ((h[27].toInt() and 0xFF) shl 24)).coerceAtLeast(8000)
            }
        } catch (_: Throwable) { 16000 }

        fun durationMs(file: File): Long {
            val data = maxOf(0L, file.length() - 44)
            return data / 2 * 1000L / sampleRateOf(file)
        }

        /** پیک هر بازه برای رسم شکل موج (0..1). فایل را جریانی می‌خواند. */
        fun readPeaks(file: File, bins: Int): FloatArray {
            val out = FloatArray(bins)
            val total = maxOf(0L, file.length() - 44) / 2
            if (total <= 0L) return out
            val per = maxOf(1L, total / bins)
            java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { s ->
                s.skip(44)
                var idx = 0L
                val b = ByteArray(2)
                while (idx < total) {
                    if (s.read(b) < 2) break
                    val v = ((b[0].toInt() and 0xFF) or (b[1].toInt() shl 8)).toShort().toInt()
                    val bin = minOf(bins - 1, (idx / per).toInt())
                    val a = kotlin.math.abs(v) / 32768f
                    if (a > out[bin]) out[bin] = a
                    idx++
                }
            }
            return out
        }

        fun writeAll(file: File, samples: ShortArray, sr: Int) {
            val w = WavWriter(file, sr)
            w.write(samples, samples.size)
            w.close()
        }
    }
}
