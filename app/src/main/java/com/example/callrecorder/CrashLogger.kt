package com.example.callrecorder

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/** آخرین کرش را در یک فایل نگه می‌دارد تا در اجرای بعدی به کاربر نشان داده شود. */
object CrashLogger {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(app).writeText("زمان: ${Date()}\nترد: ${t.name}\n\n${Log.getStackTraceString(e)}")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readText()
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Throwable) {}
    }
}
