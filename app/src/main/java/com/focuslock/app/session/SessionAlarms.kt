package com.focuslock.app.session

import com.focuslock.app.util.systemService

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** True if exact alarms are allowed (always before Android 12; user-grantable from 12). */
fun canScheduleExactAlarms(context: Context): Boolean {
    val am = context.systemService<AlarmManager>()
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
}

/**
 * The alarm that ends a running session at zero, even if the process was killed.
 * Uses ELAPSED_REALTIME so wall-clock / time-zone changes don't move it.
 */
object SessionEndAlarm {
    private const val REQUEST_CODE = 1

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SessionAlarmReceiver::class.java).setAction(SessionAlarmReceiver.ACTION_END)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, remainingMs: Long) {
        val am = context.systemService<AlarmManager>()
        val at = SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0)
        val pi = pendingIntent(context)
        try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                // Without the exact-alarm permission Android may delay this by a few minutes in Doze.
                // The displayed time is still exact; only the automatic end may lag.
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        context.systemService<AlarmManager>().cancel(pendingIntent(context))
    }
}
