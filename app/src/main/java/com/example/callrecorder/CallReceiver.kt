package com.example.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** شروع/توقف خودکار ضبط با شروع و پایان تماس (اگر در تنظیمات فعال باشد). */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs(context).autoRecord) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (!RecorderService.isRecording) {
                    val i = Intent(context, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_START)
                        .putExtra(RecorderService.EXTRA_AUTO, true)
                    try {
                        context.startForegroundService(i)
                    } catch (_: Throwable) {
                        // اندروید 12+ ممکن است شروع سرویس از پس‌زمینه را رد کند
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (RecorderService.isRecording && RecorderService.startedAuto) {
                    context.startService(
                        Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                    )
                }
            }
        }
    }
}
