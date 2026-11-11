package com.example.callrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import org.json.JSONObject
import java.io.File

/** اطلاعات تماس هر ضبط؛ کنار فایل wav به‌صورت <name>.json ذخیره می‌شود. */
object CallMeta {
    fun metaFile(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    class Info(val number: String, val name: String, val direction: Int, val resolved: Boolean)

    fun read(wav: File): Info {
        return try {
            val f = metaFile(wav)
            if (!f.exists()) return Info("", "", 0, false)
            val j = JSONObject(f.readText())
            Info(j.optString("number"), j.optString("name"), j.optInt("direction"), j.optBoolean("resolved"))
        } catch (_: Throwable) { Info("", "", 0, false) }
    }

    fun write(wav: File, i: Info) {
        try {
            metaFile(wav).writeText(JSONObject()
                .put("number", i.number).put("name", i.name)
                .put("direction", i.direction).put("resolved", i.resolved).toString())
        } catch (_: Throwable) {}
    }

    /** نام فایل ضبط → زمان شروع (میلی‌ثانیه) */
    fun startOf(wav: File): Long {
        val m = Regex("""call_(\d{8})_(\d{6})""").find(wav.name)
        if (m != null) {
            try {
                val df = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
                return df.parse(m.groupValues[1] + m.groupValues[2])!!.time
            } catch (_: Throwable) {}
        }
        return wav.lastModified()
    }

    fun hasCallLogPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /**
     * ضبط‌هایی که هنوز مخاطبشان معلوم نیست را با تاریخچه‌ی تماس تطبیق می‌دهد.
     * تماسی انتخاب می‌شود که زمان شروعش نزدیک شروع ضبط باشد
     * (ضبط می‌تواند چند ثانیه بعد از شروع تماس یا دقیقه‌ها قبل از ثبت‌شدنش شروع شود).
     */
    fun resolvePending(ctx: Context, wavs: List<File>) {
        if (!hasCallLogPermission(ctx)) return
        val pending = wavs.filter { !read(it).resolved }
        if (pending.isEmpty()) return
        val calls = ArrayList<LongArray>()   // date, durationSec, type
        val nums = ArrayList<String>(); val names = ArrayList<String>()
        try {
            val minStart = pending.minOf { startOf(it) } - 10 * 60_000L
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
                    CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?", arrayOf(minStart.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    nums.add(c.getString(0) ?: ""); names.add(c.getString(1) ?: "")
                    calls.add(longArrayOf(c.getLong(2), c.getLong(3), c.getLong(4)))
                }
            }
        } catch (_: Throwable) { return }
        for (w in pending) {
            val s = startOf(w)
            val ageMs = System.currentTimeMillis() - s
            val idx = Matcher.best(calls.map { it.copyOf() }, s, WavWriter.durationMs(w))
            if (idx >= 0) {
                val type = calls[idx][2].toInt()
                val dir = if (type == CallLog.Calls.OUTGOING_TYPE) 2 else if (type == CallLog.Calls.INCOMING_TYPE) 1 else 0
                write(w, Info(nums[idx], names[idx], dir, true))
            } else if (ageMs > 15 * 60_000L) {
                write(w, Info("", "", 0, true))   // دیگر پیدا نمی‌شود؛ تکرار نکن
            }
        }
    }

    /** تطبیق خالص (قابل تست): کدام تماس به این ضبط می‌خورد؟ -1 = هیچ. */
    object Matcher {
        fun best(calls: List<LongArray>, recStart: Long, recDurMs: Long): Int {
            var bi = -1; var bd = Long.MAX_VALUE
            for ((i, c) in calls.withIndex()) {
                val date = c[0]; val end = date + c[1] * 1000L
                val recEnd = recStart + recDurMs
                // ضبط باید با بازه‌ی تماس همپوشانی داشته باشد (با ۳۰ ثانیه تلورانس)
                val overlap = recStart <= end + 30_000L && recEnd >= date - 30_000L
                if (!overlap) continue
                val d = kotlin.math.abs(recStart - date)
                if (d < bd) { bd = d; bi = i }
            }
            return bi
        }
    }
}
