package com.example.callrecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioProcessorTest {
    private val sr = 16000

    private fun off() = AudioProcessor.Params(0f, 0f, FloatArray(5), 0f, false, false)

    private fun tone(n: Int, f: Double, amp: Double) = DoubleArray(n) { amp * sin(2 * PI * f * it / sr) }

    private fun toShort(d: DoubleArray) =
        ShortArray(d.size) { (d[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }

    private fun rms(x: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun peak(x: ShortArray): Int = x.maxOf { abs(it.toInt()) }

    @Test
    fun lengthIsPreservedWithFullPreset() {
        val inp = toShort(tone(sr, 440.0, 0.3))
        val p = AudioProcessor.PRESETS[1].p
        val out = AudioProcessor.process(inp, sr, p)
        assertEquals(inp.size, out.size)
    }

    @Test
    fun gainSixDbRoughlyDoublesLevel() {
        val inp = toShort(tone(sr, 1000.0, 0.2))
        val p = off().also { it.gainDb = 6f }
        val out = AudioProcessor.process(inp, sr, p)
        val ratio = rms(out) / rms(inp)
        assertTrue("ratio=$ratio", ratio in 1.9..2.1)
    }

    @Test
    fun negativeGainLowersLevel() {
        val inp = toShort(tone(sr, 1000.0, 0.4))
        val p = off().also { it.gainDb = -6f }
        val out = AudioProcessor.process(inp, sr, p)
        val ratio = rms(out) / rms(inp)
        assertTrue("ratio=$ratio", ratio in 0.45..0.55)
    }

    @Test
    fun normalizeReachesTargetPeak() {
        val inp = toShort(tone(sr, 500.0, 0.1))
        val p = off().also { it.normalize = true }
        val out = AudioProcessor.process(inp, sr, p)
        val pk = peak(out) / 32767.0
        assertTrue("peak=$pk", pk in 0.85..0.93)
    }

    @Test
    fun equalizerBoostRaisesMidTone() {
        val inp = toShort(tone(sr, 1000.0, 0.05))
        val p = off().also { it.eqDb[2] = 12f }
        val out = AudioProcessor.process(inp, sr, p)
        val half = inp.size / 2
        val ratio = rms(out, half, out.size) / rms(inp, half, inp.size)
        assertTrue("ratio=$ratio", ratio > 3.0)
    }

    @Test
    fun equalizerCutLowersMidTone() {
        val inp = toShort(tone(sr, 1000.0, 0.2))
        val p = off().also { it.eqDb[2] = -12f }
        val out = AudioProcessor.process(inp, sr, p)
        val half = inp.size / 2
        val ratio = rms(out, half, out.size) / rms(inp, half, inp.size)
        assertTrue("ratio=$ratio", ratio < 0.4)
    }

    @Test
    fun highPassRemovesVeryLowFrequency() {
        val inp = toShort(tone(sr * 2, 30.0, 0.3))
        val p = off().also { it.highPassHz = 100f }
        val out = AudioProcessor.process(inp, sr, p)
        val half = inp.size / 2
        val ratio = rms(out, half, out.size) / rms(inp, half, inp.size)
        assertTrue("ratio=$ratio", ratio < 0.3)
    }

    @Test
    fun noiseReductionLowersNoiseAndKeepsTone() {
        val rnd = Random(42)
        val n = sr * 4
        val clean = DoubleArray(n) { if (it < n / 2) 0.0 else 0.3 * sin(2 * PI * 440.0 * it / sr) }
        val noisy = DoubleArray(n) { clean[it] + 0.02 * rnd.nextGaussian() }
        val inp = toShort(noisy)
        val p = off().also { it.noiseStrength = 0.8f }
        val out = AudioProcessor.process(inp, sr, p)

        val a = sr / 4
        val b = n / 2 - sr / 4
        val noiseBefore = rms(inp, a, b)
        val noiseAfter = rms(out, a, b)
        assertTrue("noise before=$noiseBefore after=$noiseAfter", noiseAfter < noiseBefore * 0.5)

        val c = n / 2 + sr / 2
        val d = n - sr / 4
        val toneBefore = rms(inp, c, d)
        val toneAfter = rms(out, c, d)
        assertTrue("tone before=$toneBefore after=$toneAfter", toneAfter > toneBefore * 0.6)
    }

    @Test
    fun silenceStaysSilentAndDoesNotCrash() {
        val inp = ShortArray(sr)
        val out = AudioProcessor.process(inp, sr, AudioProcessor.PRESETS[2].p)
        assertEquals(inp.size, out.size)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test
    fun veryShortInputDoesNotCrash() {
        val inp = toShort(tone(100, 440.0, 0.3))
        val out = AudioProcessor.process(inp, sr, AudioProcessor.PRESETS[1].p)
        assertEquals(100, out.size)
        val empty = AudioProcessor.process(ShortArray(0), sr, AudioProcessor.PRESETS[1].p)
        assertEquals(0, empty.size)
    }

    @Test
    fun outputNeverClipsHarder() {
        val inp = toShort(tone(sr, 300.0, 0.9))
        val p = off().also { it.gainDb = 24f }
        val out = AudioProcessor.process(inp, sr, p)
        assertTrue(out.all { abs(it.toInt()) <= 32767 })
    }

    @Test
    fun wavRoundTripKeepsSamplesAndHeader() {
        val inp = toShort(tone(sr, 440.0, 0.3))
        val f = File.createTempFile("rt", ".wav")
        try {
            WavWriter.writeAll(f, inp, sr)
            val (back, rate) = WavWriter.read(f)
            assertEquals(sr, rate)
            assertArrayEquals(inp, back)
            assertEquals(sr, WavWriter.sampleRateOf(f))
            assertTrue(abs(WavWriter.durationMs(f) - 1000L) <= 2L)
            val peaks = WavWriter.readPeaks(f, 50)
            assertEquals(50, peaks.size)
            assertTrue(peaks.max() in 0.28f..0.32f)
        } finally {
            f.delete()
        }
    }
}
