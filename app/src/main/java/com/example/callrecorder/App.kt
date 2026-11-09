package com.example.callrecorder

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(Prefs(this).themeMode))
    }
}
