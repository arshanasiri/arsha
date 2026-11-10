package com.example.callrecorder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(Prefs(this).themeMode))
    }
}
