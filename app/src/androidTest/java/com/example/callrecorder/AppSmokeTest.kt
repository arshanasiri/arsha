package com.example.callrecorder

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    private val grants = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(*grants.toTypedArray())

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun shot(name: String) {
        shell("mkdir -p /data/local/tmp/shots")
        shell("screencap -p /data/local/tmp/shots/$name.png")
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return true
            Thread.sleep(100)
        }
        return cond()
    }

    private fun recordings(): List<File> =
        RecorderService.recordingsDir(ctx).listFiles { f -> f.extension == "wav" }?.toList() ?: emptyList()

    private fun makeSample(name: String, ageMin: Int, seconds: Int = 6): File {
        val sr = 16000
        val rnd = Random(7)
        val s = ShortArray(sr * seconds) { i ->
            val t = i.toDouble() / sr
            val env = 0.5 + 0.5 * sin(2 * PI * 0.7 * t)
            val v = env * (0.25 * sin(2 * PI * 220 * t) + 0.12 * sin(2 * PI * 880 * t)) + 0.03 * rnd.nextGaussian()
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        val f = File(RecorderService.recordingsDir(ctx), name)
        WavWriter.writeAll(f, s, sr)
        f.setLastModified(System.currentTimeMillis() - ageMin * 60_000L)
        return f
    }

    @Before
    fun setUp() {
        Prefs(ctx).apply { firstRunDone = true; autoRecord = false; sourceMode = 0; sampleRate = 16000; themeMode = 0 }
        RecorderService.recordingsDir(ctx).listFiles()?.forEach { it.delete() }
        CrashLogger.clear(ctx)
    }

    @After
    fun tearDown() {
        RecorderService.send(ctx, RecorderService.ACTION_STOP)
        RecorderService.send(ctx, RecorderService.ACTION_DISARM)
        shell("cmd uimode night auto")
    }

    private fun selectTab(sc: ActivityScenario<MainActivity>, id: Int) {
        sc.onActivity { it.findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = id }
        Thread.sleep(700)
    }

    private fun visible(sc: ActivityScenario<MainActivity>, id: Int): Boolean {
        var v = false
        sc.onActivity { v = it.findViewById<View>(id).visibility == View.VISIBLE }
        return v
    }

    private fun tour(prefix: String) {
        val a = makeSample("call_20261109_101500.wav", 5)
        makeSample("call_20261108_184200.wav", 900, 9)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(900)
            assertTrue(visible(sc, R.id.pageRecord))
            shot("${prefix}_1_record")

            selectTab(sc, R.id.nav_library)
            assertTrue(visible(sc, R.id.pageLibrary))
            shot("${prefix}_2_library")

            sc.onActivity { it.playFile(a) }
            Thread.sleep(1200)
            shot("${prefix}_3_player")

            selectTab(sc, R.id.nav_settings)
            assertTrue(visible(sc, R.id.pageSettings))
            shot("${prefix}_4_settings")
            sc.onActivity { (it.findViewById<View>(R.id.pageSettings) as ScrollView).fullScroll(View.FOCUS_DOWN) }
            Thread.sleep(500)
            shot("${prefix}_5_settings_bottom")

            selectTab(sc, R.id.nav_library)
            sc.onActivity { it.showEnhanceSheet(a) }
            Thread.sleep(1200)
            shot("${prefix}_6_enhance")
        }
    }

    @Test
    fun tourLightTheme() {
        shell("cmd uimode night no")
        Thread.sleep(800)
        tour("light")
    }

    @Test
    fun tourDarkTheme() {
        shell("cmd uimode night yes")
        Thread.sleep(800)
        tour("dark")
    }

    @Test
    fun recordingProducesWavFile() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(700)
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_START))
            assertTrue(
                "recording never started: ${RecorderService.eventMsg}",
                waitFor(10_000) { RecorderService.isRecording }
            )
            Thread.sleep(4_000)
            shot("recording_state")
            // زنجیره‌ی کامل منابع (تا ~۹ ثانیه) + کمی ضبط روی منبع نهایی
            Thread.sleep(11_000)
            assertTrue(RecorderService.isRecording)
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_STOP))
            assertTrue("recording never stopped", waitFor(10_000) { !RecorderService.isRecording })
            waitFor(5_000) { RecorderService.eventSeq > 0 }
            Log.i("CITEST", "log=${RecorderService.log} | event=${RecorderService.eventMsg} ok=${RecorderService.eventOk}")

            val files = recordings()
            assertEquals("expected exactly one recording; log=${RecorderService.log}", 1, files.size)
            val dataBytes = files[0].length() - 44
            assertTrue("recording too short: $dataBytes bytes; log=${RecorderService.log}", dataBytes > 16000 * 2 * 2)
            assertTrue(WavWriter.durationMs(files[0]) > 2000)
            assertTrue(RecorderService.eventMsg.isNotBlank())

            selectTab(sc, R.id.nav_library)
            shot("library_after_recording")
        }
    }

    @Test
    fun armedModeStartsAndStopsCleanly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_ARM))
            assertTrue("never armed: ${RecorderService.eventMsg}", waitFor(10_000) { RecorderService.isArmed })
            assertTrue(RecorderService.serviceAlive)
            assertFalse(RecorderService.isRecording)
            // شروع ضبط از حالت آماده‌باش و توقف آن، سرویس باید زنده بماند
            RecorderService.send(ctx, RecorderService.ACTION_START)
            assertTrue(waitFor(10_000) { RecorderService.isRecording })
            Thread.sleep(1_500)
            RecorderService.send(ctx, RecorderService.ACTION_STOP)
            assertTrue(waitFor(10_000) { !RecorderService.isRecording })
            assertTrue("service should stay armed", RecorderService.isArmed && RecorderService.serviceAlive)
            RecorderService.send(ctx, RecorderService.ACTION_DISARM)
            assertTrue("service should stop", waitFor(10_000) { !RecorderService.serviceAlive })
        }
    }

    @Test
    fun stopAndDisarmWithoutServiceAreSafe() {
        assertTrue(RecorderService.send(ctx, RecorderService.ACTION_STOP))
        assertTrue(RecorderService.send(ctx, RecorderService.ACTION_DISARM))
        Thread.sleep(500)
        assertFalse(RecorderService.serviceAlive)
    }

    @Test
    fun enhanceRunsOnDevice() {
        val f = makeSample("call_20261109_090000.wav", 1, 10)
        val (samples, sr) = WavWriter.read(f)
        val t0 = SystemClock.elapsedRealtime()
        val out = AudioProcessor.process(samples, sr, AudioProcessor.PRESETS[2].p)
        val took = SystemClock.elapsedRealtime() - t0
        Log.i("CITEST", "enhance took ${took}ms for ${samples.size} samples")
        assertEquals(samples.size, out.size)
        assertTrue("too slow: ${took}ms", took < 30_000)
    }
}
