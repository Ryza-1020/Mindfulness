package com.focuslock.app.schedule

import com.focuslock.app.util.systemService

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focuslock.app.core.RepeatType
import com.focuslock.app.core.ScheduleCalculator
import com.focuslock.app.data.db.ScheduleDao
import com.focuslock.app.data.db.ScheduledSessionEntity
import com.focuslock.app.session.canScheduleExactAlarms
import kotlinx.coroutines.flow.Flow
import java.time.ZonedDateTime

/**
 * Arms one AlarmManager alarm per enabled schedule (the next occurrence only).
 * AlarmManager was chosen over WorkManager because WorkManager can't guarantee a start
 * at 09:00 sharp. Alarms are cleared by reboot, so [ScheduleRepository.rescheduleAll] runs on boot,
 * time / time-zone change and exact-alarm permission change.
 */
class ScheduleAlarmScheduler(private val context: Context) {
    private val am = context.systemService<AlarmManager>()

    private fun pendingIntent(id: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST_BASE + id.toInt(),
            Intent(context, ScheduledStartReceiver::class.java)
                .setAction(ScheduledStartReceiver.ACTION_START)
                .putExtra(ScheduledStartReceiver.EXTRA_ID, id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** @return true if armed as an exact alarm. */
    fun schedule(id: Long, triggerAtWall: Long): Boolean {
        val pi = pendingIntent(id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
        return try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWall, pi)
                true
            } else {
                // Inexact fallback: Android may deliver within a window of a few minutes.
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtWall, WINDOW_MS, pi)
                false
            }
        } catch (_: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtWall, WINDOW_MS, pi)
            false
        }
    }

    fun cancel(id: Long) {
        pendingIntent(id, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    private companion object {
        const val REQUEST_BASE = 10_000
        const val WINDOW_MS = 5 * 60_000L
    }
}

class ScheduleRepository(
    private val dao: ScheduleDao,
    private val scheduler: ScheduleAlarmScheduler,
) {
    val all: Flow<List<ScheduledSessionEntity>> = dao.observeAll()

    suspend fun get(id: Long): ScheduledSessionEntity? = dao.get(id)

    /** Inserts/updates and (re)arms. Returns the id. */
    suspend fun save(entity: ScheduledSessionEntity): Long {
        val id = dao.upsert(entity.copy(nextTriggerAt = null))
        val saved = entity.copy(id = id)
        arm(saved)
        return id
    }

    suspend fun delete(id: Long) {
        scheduler.cancel(id)
        dao.delete(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val e = dao.get(id) ?: return
        arm(e.copy(enabled = enabled))
    }

    suspend fun rescheduleAll() {
        dao.getEnabled().forEach { arm(it) }
    }

    /**
     * Called when an alarm fires. Advances the schedule (disables a one-off, arms the next occurrence
     * of a repeating one) and returns the entity if it should start now, or null for a stale alarm.
     */
    suspend fun onFired(id: Long): ScheduledSessionEntity? {
        val e = dao.get(id) ?: return null
        if (!e.enabled) return null
        val now = System.currentTimeMillis()
        val expected = e.nextTriggerAt
        if (expected != null && now < expected - STALE_TOLERANCE_MS) {
            // An old alarm for an occurrence that was edited/moved. Re-arm correctly and ignore.
            arm(e)
            return null
        }
        if (e.repeat == RepeatType.ONCE) {
            scheduler.cancel(id)
            dao.setState(id, enabled = false, next = null)
        } else {
            arm(e, after = ZonedDateTime.now().plusMinutes(1))
        }
        return e
    }

    private suspend fun arm(e: ScheduledSessionEntity, after: ZonedDateTime = ZonedDateTime.now()) {
        if (!e.enabled) {
            scheduler.cancel(e.id)
            dao.setState(e.id, enabled = false, next = null)
            return
        }
        val next = ScheduleCalculator.nextTrigger(e.toRule(), after)
        if (next == null) {
            scheduler.cancel(e.id)
            dao.setState(e.id, enabled = false, next = null)
        } else {
            val at = next.toInstant().toEpochMilli()
            scheduler.schedule(e.id, at)
            dao.setState(e.id, enabled = true, next = at)
        }
    }

    private companion object {
        const val STALE_TOLERANCE_MS = 2 * 60_000L
    }
}
