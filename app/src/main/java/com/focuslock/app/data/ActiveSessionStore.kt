package com.focuslock.app.data

import android.content.Context
import com.focuslock.app.core.SessionSnapshot
import com.focuslock.app.core.SessionStatus

/**
 * Persists the active session synchronously (SharedPreferences + commit()).
 *
 * DataStore is used for settings, but the active session needs synchronous reads at process start
 * (service restart, boot receiver, Application.onCreate) and must be on disk *before* we change any
 * device state – so a crash at any point can be recovered.
 */
class ActiveSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("active_session", Context.MODE_PRIVATE)

    fun load(): SessionSnapshot? {
        if (!prefs.getBoolean(K_ACTIVE, false)) return null
        return runCatching {
            SessionSnapshot(
                sessionId = prefs.getString(K_ID, null) ?: return null,
                taskName = prefs.getString(K_TASK, "") ?: "",
                plannedMs = prefs.getLong(K_PLANNED, 0),
                status = SessionStatus.valueOf(prefs.getString(K_STATUS, SessionStatus.PAUSED.name)!!),
                accumulatedMs = prefs.getLong(K_ACC, 0),
                segmentStartElapsed = prefs.getLong(K_SEG_EL, 0),
                segmentStartWall = prefs.getLong(K_SEG_WALL, 0),
                bootCount = prefs.getInt(K_BOOT, -1),
                startedAtWall = prefs.getLong(K_STARTED, 0),
                pauseCount = prefs.getInt(K_PAUSES, 0),
                scheduledId = if (prefs.contains(K_SCHED)) prefs.getLong(K_SCHED, 0) else null,
            )
        }.getOrNull()?.takeIf { it.plannedMs > 0 }
    }

    fun save(s: SessionSnapshot) {
        prefs.edit()
            .putBoolean(K_ACTIVE, true)
            .putString(K_ID, s.sessionId)
            .putString(K_TASK, s.taskName)
            .putLong(K_PLANNED, s.plannedMs)
            .putString(K_STATUS, s.status.name)
            .putLong(K_ACC, s.accumulatedMs)
            .putLong(K_SEG_EL, s.segmentStartElapsed)
            .putLong(K_SEG_WALL, s.segmentStartWall)
            .putInt(K_BOOT, s.bootCount)
            .putLong(K_STARTED, s.startedAtWall)
            .putInt(K_PAUSES, s.pauseCount)
            .apply { if (s.scheduledId != null) putLong(K_SCHED, s.scheduledId) else remove(K_SCHED) }
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val K_ACTIVE = "active"
        const val K_ID = "id"
        const val K_TASK = "task"
        const val K_PLANNED = "planned"
        const val K_STATUS = "status"
        const val K_ACC = "acc"
        const val K_SEG_EL = "seg_elapsed"
        const val K_SEG_WALL = "seg_wall"
        const val K_BOOT = "boot"
        const val K_STARTED = "started"
        const val K_PAUSES = "pauses"
        const val K_SCHED = "sched"
    }
}
