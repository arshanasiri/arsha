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

    companion object {
        class Source(val id: Int, val label: String)

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
