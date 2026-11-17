package com.focuslock.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focuslock.app.R
import com.focuslock.app.container
import com.focuslock.app.session.Notifications
import kotlinx.coroutines.launch

/**
 * A scheduled session is due.
 *
 * Android does not let an app open a full-screen activity from the background, so we never promise
 * that. With auto-start on, the timer + silencing start right away (allowed from an exact alarm) and a
 * notification invites the user to open the timer screen. With auto-start off, the notification has a
 * "Start session" button.
 */
class ScheduledStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id < 0) return
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                val e = c.scheduleRepository.onFired(id) ?: return@launch
                val title = e.title.ifBlank { context.getString(R.string.schedule_untitled) }
                val duration = context.getString(R.string.minutes_long, e.durationMin)
                when {
                    c.sessionManager.snapshot() != null ->
                        Notifications.showScheduledSkipped(context, e.id, title)
                    c.settings.current().autoStartScheduled -> {
                        // Post first: once DND is on, our own alert would be silenced too.
                        Notifications.showScheduledStarted(context, e.id, title, duration)
                        c.sessionManager.start(e.durationMin, e.title, scheduledId = e.id, rememberAsDefault = false)
                    }
                    else -> Notifications.showScheduledPrompt(context, e.id, title, duration)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.focuslock.app.action.SCHEDULED_START"
        const val EXTRA_ID = "schedule_id"
    }
}

/** Boot, app update, clock / time-zone change, exact-alarm permission change → re-arm everything. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                c.scheduleRepository.rescheduleAll()
                // Session recovery also runs from Application.onCreate; restore() is idempotent.
                c.sessionManager.restore()
            } finally {
                pending.finish()
            }
        }
    }
}
