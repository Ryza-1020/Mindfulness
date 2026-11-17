package com.focuslock.app.session

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.focuslock.app.block.DistractionController
import com.focuslock.app.block.DndConfig
import com.focuslock.app.block.DndStatus
import com.focuslock.app.core.Clocks
import com.focuslock.app.core.SessionSnapshot
import com.focuslock.app.core.SessionStatus
import com.focuslock.app.core.TimerMath
import com.focuslock.app.core.roundedMinutes
import com.focuslock.app.data.ActiveSessionStore
import com.focuslock.app.data.AppSettings
import com.focuslock.app.data.SettingsRepository
import com.focuslock.app.data.db.SessionRecordDao
import com.focuslock.app.data.db.SessionRecordEntity
import com.focuslock.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Shown on the "well done" screen after a session ends. */
data class CompletionSummary(
    val completed: Boolean,
    val workedMs: Long,
    val plannedMs: Long,
    val taskName: String,
)

data class SessionState(
    val active: SessionSnapshot? = null,
    val completion: CompletionSummary? = null,
    val dnd: DndStatus = DndStatus.OFF,
)

enum class StartResult { STARTED, ALREADY_ACTIVE }

/**
 * Single source of truth for the work session.
 *
 * Order of operations is chosen so that a crash at any point is recoverable:
 * persist state → change device state (DND, alarms, service). On the way out:
 * clear persisted state last-but-safe (restore() treats "no session + DND applied" as orphaned
 * and restores it).
 *
 * All mutations are serialised with a [Mutex]. Callers: UI (ViewModels), notification actions,
 * alarm receivers, the foreground service and boot recovery.
 */
class SessionManager(
    private val context: Context,
    private val store: ActiveSessionStore,
    private val settings: SettingsRepository,
    private val distraction: DistractionController,
    private val records: SessionRecordDao,
    val clocks: Clocks,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SessionState(active = store.load()))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun snapshot(): SessionSnapshot? = _state.value.active

    suspend fun start(
        minutes: Int,
        taskName: String,
        scheduledId: Long? = null,
        rememberAsDefault: Boolean = true,
    ): StartResult = mutex.withLock {
        if (_state.value.active != null) return StartResult.ALREADY_ACTIVE
        val snap = TimerMath.start(
            sessionId = UUID.randomUUID().toString(),
            taskName = taskName.trim(),
            plannedMs = minutes.coerceIn(1, 600) * 60_000L,
            clocks = clocks,
            scheduledId = scheduledId,
        )
        store.save(snap)
        if (rememberAsDefault) settings.update { it.copy(lastDurationMin = minutes) }
        val cfg = settings.current()
        val dnd = applyDnd(cfg, running = true)
        Notifications.cancelResult(context)
        _state.value = SessionState(active = snap, completion = null, dnd = dnd)
        SessionEndAlarm.schedule(context, TimerMath.remainingMs(snap, clocks))
        FocusSessionService.start(context)
        StartResult.STARTED
    }

    suspend fun pause(): Unit = mutex.withLock {
        val s = _state.value.active ?: return@withLock
        if (s.status != SessionStatus.RUNNING) return@withLock
        if (TimerMath.isFinished(s, clocks)) return@withLock finishLocked(completed = true)
        val paused = TimerMath.pause(s, clocks)
        store.save(paused)
        SessionEndAlarm.cancel(context)
        val dnd = applyDnd(settings.current(), running = false)
        _state.update { it.copy(active = paused, dnd = dnd) }
        Notifications.updateOngoing(context, paused, clocks)
    }

    suspend fun resume(): Unit = mutex.withLock {
        val s = _state.value.active ?: return@withLock
        if (s.status != SessionStatus.PAUSED) return@withLock
        val running = TimerMath.resume(s, clocks)
        store.save(running)
        val dnd = applyDnd(settings.current(), running = true)
        _state.update { it.copy(active = running, dnd = dnd) }
        SessionEndAlarm.schedule(context, TimerMath.remainingMs(running, clocks))
        Notifications.updateOngoing(context, running, clocks)
    }

    /** Toggle "keep silencing while paused" from the pause screen; applies immediately. */
    suspend fun setKeepSilenceDuringPause(keep: Boolean) {
        settings.update { it.copy(keepSilenceDuringPause = keep) }
        mutex.withLock {
            val s = _state.value.active ?: return@withLock
            val dnd = applyDnd(settings.current(), running = s.status == SessionStatus.RUNNING)
            _state.update { it.copy(dnd = dnd) }
        }
    }

    suspend fun finishEarly(): Unit = mutex.withLock { finishLocked(completed = false) }

    /** Called by the end alarm, the service tick and the UI when it sees 00:00:00. */
    suspend fun checkCompletion(): Unit = mutex.withLock {
        val s = _state.value.active ?: return@withLock
        if (TimerMath.isFinished(s, clocks)) {
            finishLocked(completed = true)
        } else if (s.status == SessionStatus.RUNNING) {
            // Fired early (inexact alarm) – re-arm for the real end.
            SessionEndAlarm.schedule(context, TimerMath.remainingMs(s, clocks))
        }
    }

    /** Periodic persistence of the running segment (limits the impact of clock changes + reboot). */
    suspend fun checkpoint(): Unit = mutex.withLock {
        val s = _state.value.active ?: return@withLock
        if (s.status != SessionStatus.RUNNING) return@withLock
        if (TimerMath.isFinished(s, clocks)) return@withLock finishLocked(completed = true)
        val c = TimerMath.checkpoint(s, clocks)
        store.save(c)
        _state.update { it.copy(active = c) }
    }

    fun dismissCompletion() {
        _state.update { it.copy(completion = null) }
        Notifications.cancelResult(context)
    }

    /**
     * Recovery at process start (app launch, service restart by the system, boot, alarm).
     * Idempotent.
     */
    suspend fun restore(): Unit = mutex.withLock {
        val stored = store.load()
        if (stored == null) {
            // Crash/kill after DND was applied but before it was removed → put it back.
            distraction.deactivate()
            _state.update { it.copy(active = null) }
            return@withLock
        }
        if (TimerMath.isFinished(stored, clocks)) {
            _state.update { it.copy(active = stored) }
            return@withLock finishLocked(completed = true)
        }
        // Re-anchor on the current boot's clocks (bridges a reboot with the wall-clock delta once).
        val fixed = if (stored.status == SessionStatus.RUNNING && stored.bootCount != clocks.bootCount()) {
            TimerMath.checkpoint(stored, clocks).also(store::save)
        } else {
            stored
        }
        val dnd = applyDnd(settings.current(), running = fixed.status == SessionStatus.RUNNING)
        _state.update { it.copy(active = fixed, dnd = dnd) }
        if (fixed.status == SessionStatus.RUNNING) {
            SessionEndAlarm.schedule(context, TimerMath.remainingMs(fixed, clocks))
        }
        FocusSessionService.start(context)
    }

    /** Re-evaluates DND after the user granted/revoked access (called on app resume). */
    suspend fun refreshDnd(): Unit = mutex.withLock {
        val s = _state.value.active ?: return@withLock
        val dnd = applyDnd(settings.current(), running = s.status == SessionStatus.RUNNING)
        _state.update { it.copy(dnd = dnd) }
    }

    // ---------------------------------------------------------------------------------------------

    private suspend fun finishLocked(completed: Boolean) {
        val s = _state.value.active ?: return
        val worked = TimerMath.workedMs(s, clocks)
        val done = completed || worked >= s.plannedMs
        val endedAt = clocks.wallTime()

        SessionEndAlarm.cancel(context)
        distraction.deactivate()
        store.clear()
        FocusSessionService.stop(context)
        Notifications.cancelOngoing(context)

        runCatching {
            records.insert(
                SessionRecordEntity(
                    startedAt = s.startedAtWall,
                    endedAt = endedAt,
                    plannedMs = s.plannedMs,
                    actualMs = worked,
                    completed = done,
                    taskName = s.taskName,
                    dayEpoch = Instant.ofEpochMilli(s.startedAtWall).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
                ),
            )
        }

        _state.value = SessionState(
            active = null,
            completion = CompletionSummary(done, worked, s.plannedMs, s.taskName),
            dnd = DndStatus.OFF,
        )

        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (done && !inForeground) {
            Notifications.showCompleted(context, context.getString(R.string.minutes_long, roundedMinutes(worked)))
        }
    }

    private fun applyDnd(cfg: AppSettings, running: Boolean): DndStatus {
        if (!cfg.blockNotifications) {
            distraction.deactivate()
            return DndStatus.OFF
        }
        if (!running && !cfg.keepSilenceDuringPause) {
            distraction.deactivate()
            return DndStatus.PAUSED
        }
        return distraction.activate(DndConfig(cfg.callsPolicy, cfg.allowRepeatCallers))
    }
}
