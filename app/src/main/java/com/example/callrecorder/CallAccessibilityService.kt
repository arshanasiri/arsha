package com.example.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * سرویس دسترسی «خالی»: هیچ رویدادی را پردازش یا ذخیره نمی‌کند.
 * روی بعضی گوشی‌ها (اندروید 10 به بعد) فعال بودن آن باعث می‌شود سیستم میکروفون را
 * هنگام تماس از برنامه نگیرد و ضبط بی‌صدا نشود. تضمینی نیست و به مدل گوشی بستگی دارد.
 */
class CallAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun isEnabled(ctx: Context): Boolean = try {
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val id = ComponentName(ctx, CallAccessibilityService::class.java).flattenToString()
            v.split(':').any { it.equals(id, true) || it.endsWith("CallAccessibilityService") }
        } catch (_: Throwable) { false }
    }
}
