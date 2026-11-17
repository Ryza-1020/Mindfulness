package com.focuslock.app.ui.components

import com.focuslock.app.util.systemService

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.focuslock.app.session.canScheduleExactAlarms

/** Snapshot of every permission/feature FocusLock relies on. Re-read on every resume. */
data class PermissionState(
    val notifications: Boolean,
    val dnd: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionState {
            val nm = context.systemService<NotificationManager>()
            val pm = context.systemService<PowerManager>()
            return PermissionState(
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                dnd = nm.isNotificationPolicyAccessGranted,
                exactAlarms = canScheduleExactAlarms(context),
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}

/** Opens system settings screens. Each falls back to the app's details page if the OEM lacks the screen. */
object SystemSettings {
    private fun Context.launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            appDetails(this)
        } catch (_: SecurityException) {
            appDetails(this)
        }
    }

    fun appDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    fun dndAccess(context: Context) = context.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    fun notifications(context: Context) = context.launch(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun exactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }

    /** List screen only – no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission needed. */
    fun batteryOptimization(context: Context) = context.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    fun security(context: Context) = context.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
}
