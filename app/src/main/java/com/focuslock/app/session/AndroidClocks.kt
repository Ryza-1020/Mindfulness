package com.focuslock.app.session

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.focuslock.app.core.Clocks

/** Real device clocks. BOOT_COUNT (API 24+) lets us detect a reboot during a running segment. */
class AndroidClocks(context: Context) : Clocks {
    private val resolver = context.applicationContext.contentResolver

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun wallTime(): Long = System.currentTimeMillis()
    override fun bootCount(): Int = try {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }
}
