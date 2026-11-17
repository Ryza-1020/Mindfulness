package com.focuslock.app.session

import com.focuslock.app.util.systemService

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.focuslock.app.R
import com.focuslock.app.core.Clocks
import com.focuslock.app.core.SessionSnapshot
import com.focuslock.app.core.SessionStatus
import com.focuslock.app.core.TimerMath
import com.focuslock.app.core.formatHms
import com.focuslock.app.ui.MainActivity

/** All notifications FocusLock posts. Platform APIs only (minSdk 26 → channels always exist). */
object Notifications {
    const val CH_SESSION = "session"
    const val CH_SCHEDULE = "schedule"
    const val CH_RESULT = "result"

    const val ID_ONGOING = 1001
    const val ID_RESULT = 1002
    private const val ID_SCHEDULE_BASE = 20_000

    fun createChannels(context: Context) {
        val nm = context.systemService<NotificationManager>()
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SESSION, context.getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_session_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CH_SCHEDULE, context.getString(R.string.channel_schedule), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_schedule_desc)
                },
                NotificationChannel(CH_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, requestCode: Int = 0, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply(extras)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun sessionAction(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, SessionAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The persistent foreground-service notification. The countdown is drawn by the system chronometer. */
    fun ongoing(context: Context, s: SessionSnapshot, clocks: Clocks): Notification {
        val b = Notification.Builder(context, CH_SESSION)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setColor(context.getColor(R.color.notification_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
        if (s.status == SessionStatus.RUNNING) {
            b.setContentTitle(context.getString(R.string.notif_running_title))
                .setContentText(s.taskName.ifBlank { null })
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(clocks.wallTime() + TimerMath.remainingMs(s, clocks))
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.notif_action_pause), sessionAction(context, SessionAlarmReceiver.ACTION_PAUSE)).build(),
                )
        } else {
            b.setContentTitle(context.getString(R.string.notif_paused_title))
                .setContentText(context.getString(R.string.notif_paused_text, formatHms(TimerMath.remainingMs(s, clocks))))
                .setShowWhen(false)
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.notif_action_resume), sessionAction(context, SessionAlarmReceiver.ACTION_RESUME)).build(),
                )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    fun updateOngoing(context: Context, s: SessionSnapshot, clocks: Clocks) {
        if (!canPost(context)) return
        context.systemService<NotificationManager>().notify(ID_ONGOING, ongoing(context, s, clocks))
    }

    fun showCompleted(context: Context, workedText: String) {
        if (!canPost(context)) return
        val n = Notification.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notif_completed_title))
            .setContentText(context.getString(R.string.notif_completed_text, workedText))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.systemService<NotificationManager>().notify(ID_RESULT, n)
    }

    fun cancelOngoing(context: Context) {
        context.systemService<NotificationManager>().cancel(ID_ONGOING)
    }

    fun cancelResult(context: Context) {
        context.systemService<NotificationManager>().cancel(ID_RESULT)
    }

    fun showScheduledStarted(context: Context, scheduleId: Long, title: String, durationText: String) {
        post(
            context, scheduleId,
            Notification.Builder(context, CH_SCHEDULE)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle(context.getString(R.string.notif_sched_started_title, title))
                .setContentText(context.getString(R.string.notif_sched_started_text, durationText))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, requestCode = (ID_SCHEDULE_BASE + scheduleId).toInt())),
        )
    }

    /** Used when auto-start is off: a notification with a "Start session" button (opens the app). */
    fun showScheduledPrompt(context: Context, scheduleId: Long, title: String, durationText: String) {
        val start = openApp(context, requestCode = (ID_SCHEDULE_BASE + scheduleId).toInt()) {
            putExtra(MainActivity.EXTRA_START_SCHEDULED_ID, scheduleId)
        }
        post(
            context, scheduleId,
            Notification.Builder(context, CH_SCHEDULE)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle(context.getString(R.string.notif_sched_prompt_title, title))
                .setContentText(context.getString(R.string.notif_sched_prompt_text, durationText))
                .setAutoCancel(true)
                .setContentIntent(start)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.notif_sched_action_start), start).build()),
        )
    }

    fun showScheduledSkipped(context: Context, scheduleId: Long, title: String) {
        post(
            context, scheduleId,
            Notification.Builder(context, CH_SCHEDULE)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setContentTitle(context.getString(R.string.notif_sched_skipped_title, title))
                .setContentText(context.getString(R.string.notif_sched_skipped_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context)),
        )
    }

    fun cancelScheduled(context: Context, scheduleId: Long) {
        context.systemService<NotificationManager>().cancel((ID_SCHEDULE_BASE + scheduleId).toInt())
    }

    private fun post(context: Context, scheduleId: Long, b: Notification.Builder) {
        if (!canPost(context)) return
        b.setColor(context.getColor(R.color.notification_accent))
        context.systemService<NotificationManager>()
            .notify((ID_SCHEDULE_BASE + scheduleId).toInt(), b.build())
    }
}
