package com.example.callrecorder

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
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
        add(Manifest.permission.READ_CALL_LOG)
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
        Prefs(ctx).apply { firstRunDone = true; autoRecord = false; sourceMode = 0; sampleRate = 16000; themeMode = 0; fakeSource = false }
        RecorderService.recordingsDir(ctx).listFiles()?.forEach { it.delete() }
        CrashLogger.clear(ctx)
    }

    @After
    fun tearDown() {
        RecorderService.send(ctx, RecorderService.ACTION_STOP)
        RecorderService.send(ctx, RecorderService.ACTION_DISARM)
        Prefs(ctx).fakeSource = false
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

    /** مسیر موفق: با منبع آزمایشی (صدای مصنوعی) باید فایل WAV شنیدنی ساخته شود. */
    @Test
    fun recordingWithSignalProducesAudibleWav() {
        Prefs(ctx).fakeSource = true
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(700)
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_START))
            assertTrue(
                "recording never started: ${RecorderService.eventMsg}",
                waitFor(10_000) { RecorderService.isRecording }
            )
            Thread.sleep(4_000)
            shot("recording_state")
            Thread.sleep(2_000)
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_STOP))
            assertTrue("recording never stopped", waitFor(10_000) { !RecorderService.isRecording })
            waitFor(5_000) { RecorderService.eventSeq > 0 }
            Log.i("CITEST", "log=${RecorderService.log} | event=${RecorderService.eventMsg} ok=${RecorderService.eventOk}")

            assertTrue("event should be ok: ${RecorderService.eventMsg}", RecorderService.eventOk)
            assertTrue(RecorderService.log.contains("✓"))
            val files = recordings()
            assertEquals("expected exactly one recording", 1, files.size)
            assertTrue("recording too short", WavWriter.durationMs(files[0]) in 4_500L..8_500L)

            // محتوای فایل واقعاً صدا دارد (نه سکوت)
            val (samples, _) = WavWriter.read(files[0])
            var peak = 0
            for (x in samples) peak = maxOf(peak, kotlin.math.abs(x.toInt()))
            assertTrue("file is silent, peak=$peak", peak > 4000)

            selectTab(sc, R.id.nav_library)
            shot("library_after_recording")
        }
    }

    /** مسیر شکست: میکروفون شبیه‌ساز بی‌صدا است؛ نباید فایل خالی بماند و باید پیام روشن بیاید. */
    @Test
    fun silentMicGivesClearErrorAndNoEmptyFile() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(500)
            val seqBefore = RecorderService.eventSeq
            assertTrue(RecorderService.send(ctx, RecorderService.ACTION_START))
            assertTrue(waitFor(10_000) { RecorderService.isRecording })
            Thread.sleep(5_000)
            RecorderService.send(ctx, RecorderService.ACTION_STOP)
            assertTrue(waitFor(10_000) { !RecorderService.isRecording })
            assertTrue(waitFor(5_000) { RecorderService.eventSeq > seqBefore })
            Log.i("CITEST", "silent-mic: log=${RecorderService.log} | event=${RecorderService.eventMsg} ok=${RecorderService.eventOk}")
            assertTrue(RecorderService.eventMsg.isNotBlank())
            val files = recordings()
            if (RecorderService.eventOk) {
                // اگر این شبیه‌ساز میکروفون واقعی داشت، فایل باید صدا داشته باشد
                assertEquals(1, files.size)
            } else {
                assertEquals("no empty file may remain after failure", 0, files.size)
                assertTrue(RecorderService.eventMsg.contains("صدایی ضبط نشد"))
            }
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
    fun accessibilityServiceIsOffByDefault() {
        assertFalse(CallAccessibilityService.isEnabled(ctx))
    }

    @Test
    fun stopAndDisarmWithoutServiceAreSafe() {
        assertTrue(RecorderService.send(ctx, RecorderService.ACTION_STOP))
        assertTrue(RecorderService.send(ctx, RecorderService.ACTION_DISARM))
        Thread.sleep(500)
        assertFalse(RecorderService.serviceAlive)
    }

    private fun isNight(sc: ActivityScenario<MainActivity>): Boolean {
        var n = false
        sc.onActivity {
            n = (it.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        return n
    }

    @Test
    fun themeToggleButtonSwitchesBothWays() {
        shell("cmd uimode night no")
        Thread.sleep(500)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(800)
            assertFalse("should start light", isNight(sc))
            selectTab(sc, R.id.nav_library)
            sc.onActivity { it.findViewById<View>(R.id.btnTheme).performClick() }
            assertTrue("dark after 1st click", waitFor(6000) { isNight(sc) })
            Thread.sleep(600)
            assertTrue("tab kept after theme change", visible(sc, R.id.pageLibrary))
            shot("theme_toggled_dark")
            sc.onActivity { it.findViewById<View>(R.id.btnTheme).performClick() }
            assertTrue("light after 2nd click", waitFor(6000) { !isNight(sc) })
            shot("theme_toggled_light")
        }
    }

    private fun writeMeta(f: File, number: String, name: String, dir: Int) =
        CallMeta.write(f, CallMeta.Info(number, name, dir, true))

    @Test
    fun libraryGroupsByContactAndBulkDeleteRemovesFiles() {
        val a1 = makeSample("call_20261109_101500.wav", 5);  writeMeta(a1, "+989121234567", "علی رضایی", 1)
        val a2 = makeSample("call_20261109_111500.wav", 50); writeMeta(a2, "09121234567", "", 2)
        val b1 = makeSample("call_20261108_184200.wav", 900, 9); writeMeta(b1, "02188776655", "", 1)
        val c1 = makeSample("call_20261107_090000.wav", 2000);   writeMeta(c1, "", "", 0)
        assertEquals(4, recordings().size)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            Thread.sleep(800)
            selectTab(sc, R.id.nav_library)
            var titles = emptyList<String>()
            sc.onActivity {
                val box = it.findViewById<android.widget.LinearLayout>(R.id.listBox)
                titles = (0 until box.childCount).mapNotNull { i ->
                    box.getChildAt(i).findViewById<TextView>(R.id.gTitle)?.text?.toString()
                }
            }
            // +98912… و 0912… یک مخاطب‌اند → ۳ گروه (علی، تهران، ناشناس)
            assertEquals("groups: $titles", 3, titles.size)
            assertTrue(titles.joinToString().contains("علی"))
            shot("library_grouped")

            // انتخاب با لمس طولانی، سپس «همه»
            sc.onActivity {
                val box = it.findViewById<android.widget.LinearLayout>(R.id.listBox)
                val row = (0 until box.childCount).map { i -> box.getChildAt(i) }
                    .first { v -> v.findViewById<View>(R.id.tvInitial) != null && v.findViewById<TextView>(R.id.gTitle) == null }
                row.performLongClick()
            }
            Thread.sleep(500)
            assertTrue(visible(sc, R.id.selectBar))
            sc.onActivity { it.findViewById<View>(R.id.btnSelAll).performClick() }
            Thread.sleep(500)
            shot("library_selection")
            sc.onActivity { it.deleteRecordings(listOf(a1, a2)) }
        }
        assertFalse(a1.exists()); assertFalse(a2.exists())
        assertFalse(CallMeta.metaFile(a1).exists()); assertFalse(CallMeta.metaFile(a2).exists())
        assertTrue(b1.exists() && c1.exists())
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
