package com.example.callrecorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** پردازش آفلاین صدا: هایپس، کاهش نویز طیفی، اکولایزر ۵ باندی، کمپرسور، نرمال‌سازی، گین و لیمیتر. */
object AudioProcessor {

    class Params(
        var noiseStrength: Float = 0f,          // 0..1
        var highPassHz: Float = 0f,             // 0 = خاموش
        var eqDb: FloatArray = FloatArray(5),   // dB برای هر باند
        var gainDb: Float = 0f,
        var compress: Boolean = false,
        var normalize: Boolean = true,
    )

    val EQ_FREQS = floatArrayOf(100f, 300f, 1000f, 3000f, 6000f)
    val EQ_LABELS = listOf("100 Hz (بم)", "300 Hz", "1 kHz", "3 kHz (وضوح گفتار)", "6 kHz (زیر)")

    class Preset(val name: String, val p: Params)

    val PRESETS = listOf(
        Preset("سفارشی", Params()),
        Preset("وضوح گفتار", Params(0.5f, 90f, floatArrayOf(-4f, -2f, 0f, 3f, 2f), 0f, true, true)),
        Preset("حذف نویز قوی", Params(0.85f, 100f, floatArrayOf(-3f, 0f, 0f, 2f, -2f), 0f, true, true)),
        Preset("تقویت صدای کم", Params(0.4f, 80f, floatArrayOf(0f, 0f, 1f, 3f, 1f), 6f, true, true)),
        Preset("بدون تغییر", Params(0f, 0f, FloatArray(5), 0f, false, false)),
    )

    // ---------------- Biquad ----------------
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun highPass(sr: Int, f: Double, q: Double = 0.7071): Biquad {
        val w0 = 2 * PI * f / sr
        val c = cos(w0); val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    private fun peaking(sr: Int, f: Double, gainDb: Double, q: Double = 1.0): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / sr
        val c = cos(w0); val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
    }

    // ---------------- FFT ----------------
    private class FFT(val n: Int) {
        private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
        private val rev = IntArray(n).also {
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) it[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + half) {
                        val wr = cosT[k]
                        val wi = if (inverse) sinT[k] else -sinT[k]
                        val tr = re[j + half] * wr - im[j + half] * wi
                        val ti = re[j + half] * wi + im[j + half] * wr
                        re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    i += size
                }
                size *= 2
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }

    // ---------------- کاهش نویز طیفی ----------------
    private fun denoise(x: FloatArray, sr: Int, strength: Float, progress: (Float) -> Unit): FloatArray {
        val n = 1024
        val hop = 256
        val bins = n / 2 + 1
        val fft = FFT(n)
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }

        val padded = FloatArray(x.size + 2 * n)
        System.arraycopy(x, 0, padded, n, x.size)
        val frames = (padded.size - n) / hop + 1

        // مرحله ۱: انرژی فریم‌ها و انتخاب آرام‌ترین ۱۰٪ به‌عنوان پروفایل نویز
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var e = 0.0
            val s = f * hop
            for (i in 0 until n step 4) e += padded[s + i].toDouble() * padded[s + i]
            energy[f] = (e / (n / 4)).toFloat()
        }
        val valid = (0 until frames).filter { energy[it] > 1e-9f }.sortedBy { energy[it] }
        if (valid.isEmpty()) return x
        val take = max(3, min(2000, valid.size / 10))
        val noiseFrames = valid.take(take)

        val noise = DoubleArray(bins)
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (f in noiseFrames) {
            val s = f * hop
            for (i in 0 until n) { re[i] = padded[s + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im, false)
            for (k in 0 until bins) noise[k] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        for (k in 0 until bins) noise[k] /= noiseFrames.size
        progress(0.15f)

        // مرحله ۲: تفریق طیفی با هموارسازی گین
        val alpha = 1.0 + 2.5 * strength
        val floor = 0.12 - 0.10 * strength
        val out = DoubleArray(padded.size)
        val prevGain = DoubleArray(bins) { 1.0 }
        val gain = DoubleArray(bins)
        val smoothed = DoubleArray(bins)

        for (f in 0 until frames) {
            val s = f * hop
            for (i in 0 until n) { re[i] = padded[s + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im, false)
            for (k in 0 until bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-12
                gain[k] = max((mag - alpha * noise[k]) / mag, floor)
            }
            // هموارسازی در فرکانس و زمان (کاهش «نویز موسیقایی»)
            for (k in 0 until bins) {
                val a = gain[max(k - 1, 0)]; val b = gain[k]; val c = gain[min(k + 1, bins - 1)]
                val g = (a + 2 * b + c) / 4
                // افت سریع‌تر، بازگشت آهسته‌تر
                smoothed[k] = if (g < prevGain[k]) 0.5 * prevGain[k] + 0.5 * g else 0.8 * prevGain[k] + 0.2 * g
                prevGain[k] = smoothed[k]
            }
            for (k in 0 until bins) {
                re[k] *= smoothed[k]; im[k] *= smoothed[k]
                if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
            }
            fft.transform(re, im, true)
            for (i in 0 until n) out[s + i] += re[i] * win[i] / 1.5
            if (f % 200 == 0) progress(0.15f + 0.6f * f / frames)
        }
        return FloatArray(x.size) { out[n + it].toFloat() }
    }

    // ---------------- کمپرسور ----------------
    private fun compress(x: FloatArray, sr: Int) {
        val thrDb = -22.0
        val ratio = 3.0
        val atk = exp(-1.0 / (sr * 0.008))
        val rel = exp(-1.0 / (sr * 0.150))
        var env = 0.0
        for (i in x.indices) {
            val a = abs(x[i]).toDouble()
            env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
            val db = 20 * log10(env + 1e-9)
            val over = db - thrDb
            if (over > 0) x[i] = (x[i] * 10.0.pow(-over * (1 - 1 / ratio) / 20)).toFloat()
        }
    }

    // ---------------- زنجیره‌ی اصلی ----------------
    /** خروجی: نمونه‌های پردازش‌شده. progress مقدار 0..1 می‌گیرد. */
    fun process(input: ShortArray, sr: Int, p: Params, progress: (Float) -> Unit = {}): ShortArray {
        var x = FloatArray(input.size) { input[it] / 32768f }
        progress(0.02f)

        if (p.highPassHz >= 20f) {
            val hp = highPass(sr, p.highPassHz.toDouble())
            for (i in x.indices) x[i] = hp.process(x[i].toDouble()).toFloat()
        }

        if (p.noiseStrength > 0.01f) {
            x = denoise(x, sr, p.noiseStrength.coerceIn(0f, 1f)) { progress(it) }
        }
        progress(0.8f)

        val filters = ArrayList<Biquad>()
        for (b in 0 until 5) {
            val g = p.eqDb[b]
            if (abs(g) >= 0.1f && EQ_FREQS[b] < sr * 0.45f) {
                filters.add(peaking(sr, EQ_FREQS[b].toDouble(), g.toDouble()))
            }
        }
        if (filters.isNotEmpty()) {
            for (i in x.indices) {
                var v = x[i].toDouble()
                for (f in filters) v = f.process(v)
                x[i] = v.toFloat()
            }
        }
        progress(0.88f)

        if (p.compress) compress(x, sr)

        if (p.normalize) {
            var peak = 0f
            for (v in x) { val a = abs(v); if (a > peak) peak = a }
            if (peak > 1e-5f) {
                val g = 0.89f / peak
                for (i in x.indices) x[i] *= g
            }
        }

        if (abs(p.gainDb) > 0.05f) {
            val g = 10f.pow(p.gainDb / 20f)
            for (i in x.indices) x[i] *= g
        }
        progress(0.95f)

        // لیمیتر نرم برای جلوگیری از کلیپ شدن
        return ShortArray(x.size) {
            var v = x[it]
            val a = abs(v)
            if (a > 0.9f) v = (if (v < 0) -1f else 1f) * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
            (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
