package com.focuslock.app.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.focuslock.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive during a session, shows the countdown
 * notification and checkpoints the timer. It holds no timer state of its own – everything comes
 * from [SessionManager] – so the system may kill and restart it (START_STICKY) at any time.
 */
class FocusSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = container.sessionManager
        val snap = manager.snapshot()
        if (snap == null) {
            // Restarted by the system after the process died: let restore() decide.
            scope.launch {
                manager.restore()
                if (manager.snapshot() == null) stopSelf()
            }
            // We must still call startForeground() promptly after startForegroundService().
            promoteToForeground(null)
            return START_STICKY
        }
        promoteToForeground(snap)
        startTicker()
        return START_STICKY
    }

    private fun promoteToForeground(snap: com.focuslock.app.core.SessionSnapshot?) {
        val manager = container.sessionManager
        val s = snap ?: manager.snapshot()
        val notification = if (s != null) {
            Notifications.ongoing(this, s, manager.clocks)
        } else {
            android.app.Notification.Builder(this, Notifications.CH_SESSION)
                .setSmallIcon(com.focuslock.app.R.drawable.ic_stat_focus)
                .setContentTitle(getString(com.focuslock.app.R.string.app_name))
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifications.ID_ONGOING, notification)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException. The session still works (alarm + persisted
            // state); only the background keep-alive is missing.
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)
                val manager = container.sessionManager
                manager.checkpoint()
                if (manager.snapshot() == null) {
                    stopSelf()
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FocusLockService"
        private const val CHECKPOINT_INTERVAL_MS = 15_000L

        /** Starts or refreshes the service. Returns false if Android refused (background start limits). */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, FocusSessionService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService refused", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FocusSessionService::class.java))
        }
    }
}
