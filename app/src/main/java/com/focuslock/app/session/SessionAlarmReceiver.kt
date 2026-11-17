package com.focuslock.app.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focuslock.app.container
import kotlinx.coroutines.launch

/** Handles the session end alarm and the pause/resume buttons of the ongoing notification. */
class SessionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_END -> c.sessionManager.checkCompletion()
                    ACTION_PAUSE -> c.sessionManager.pause()
                    ACTION_RESUME -> c.sessionManager.resume()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_END = "com.focuslock.app.action.SESSION_END"
        const val ACTION_PAUSE = "com.focuslock.app.action.SESSION_PAUSE"
        const val ACTION_RESUME = "com.focuslock.app.action.SESSION_RESUME"
    }
}
