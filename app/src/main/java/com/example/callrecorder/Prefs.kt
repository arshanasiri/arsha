package com.example.callrecorder

import android.content.Context
import android.media.MediaRecorder

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = خودکار (امتحان همه‌ی منابع)، 1..N = انتخاب دستی از SOURCES */
    var sourceMode: Int
        get() = sp.getInt("sourceMode", 0)
        set(v) = sp.edit().putInt("sourceMode", v).apply()

    var forceSpeaker: Boolean
        get() = sp.getBoolean("forceSpeaker", true)
        set(v) = sp.edit().putBoolean("forceSpeaker", v).apply()

    var autoRecord: Boolean
        get() = sp.getBoolean("autoRecord", false)
        set(v) = sp.edit().putBoolean("autoRecord", v).apply()

    var sampleRate: Int
        get() = sp.getInt("sampleRate", 16000)
        set(v) = sp.edit().putInt("sampleRate", v).apply()

    /** تقویت زنده هنگام ضبط (dB) */
    var liveGainDb: Int
        get() = sp.getInt("liveGainDb", 0)
        set(v) = sp.edit().putInt("liveGainDb", v).apply()

    /** فقط برای تست خودکار: به‌جای میکروفون، صدای مصنوعی می‌خواند. */
    var fakeSource: Boolean
        get() = sp.getBoolean("fakeSource", false)
        set(v) = sp.edit().putBoolean("fakeSource", v).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean("firstRunDone", false)
        set(v) = sp.edit().putBoolean("firstRunDone", v).apply()

    /** 0 = سیستم، 1 = روشن، 2 = تاریک */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) = sp.edit().putInt("themeMode", v).apply()

    var libGrouped: Boolean
        get() = sp.getBoolean("libGrouped", true)
        set(v) = sp.edit().putBoolean("libGrouped", v).apply()

    var libSort: Int
        get() = sp.getInt("libSort", 0)
        set(v) = sp.edit().putInt("libSort", v).apply()

    /** برای برگشت به همان صفحه بعد از تغییر تم (0 = هیچ) */
    var lastTab: Int
        get() = sp.getInt("lastTab", 0)
        set(v) = sp.edit().putInt("lastTab", v).apply()

    companion object {
        fun nightModeFor(mode: Int): Int = when (mode) {
            1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        class Source(val id: Int, val label: String) {
            val short: String get() = label.substringBefore(" (")
        }

        val SOURCES = listOf(
            Source(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL (هر دو طرف، فقط برخی دستگاه‌ها)"),
            Source(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION"),
            Source(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"),
            Source(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED (میکروفون خام)"),
            Source(MediaRecorder.AudioSource.MIC, "MIC (میکروفون + بلندگو)"),
            Source(MediaRecorder.AudioSource.CAMCORDER, "CAMCORDER"),
        )
    }
}
