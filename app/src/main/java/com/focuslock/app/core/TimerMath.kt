package com.focuslock.app.core

/**
 * Pure timer logic. The UI never counts seconds itself – it always asks [TimerMath]
 * for the remaining time, computed from absolute clock stamps stored in [SessionSnapshot].
 *
 * Clock strategy:
 *  - Within one boot we measure with `elapsedRealtime` (monotonic, keeps counting in deep sleep,
 *    immune to the user changing the wall clock or time zone).
 *  - `elapsedRealtime` restarts at 0 after a reboot. If a reboot happened during a running segment
 *    we fall back to the wall-clock delta of that segment (sanity-capped), then re-anchor.
 *  - The running segment is checkpointed regularly, so a wall-clock change followed by a reboot
 *    can only affect the last few seconds.
 */
enum class SessionStatus { RUNNING, PAUSED }

data class SessionSnapshot(
    val sessionId: String,
    val taskName: String,
    /** Total planned work time. */
    val plannedMs: Long,
    val status: SessionStatus,
    /** Work time already counted before the current running segment (or until the pause). */
    val accumulatedMs: Long,
    /** `elapsedRealtime` when the current running segment started. Meaningful only while RUNNING. */
    val segmentStartElapsed: Long,
    /** Wall clock at the same instant – used only to bridge a reboot. */
    val segmentStartWall: Long,
    /** Android boot counter at segment start (-1 if unknown). */
    val bootCount: Int,
    /** Wall clock when the session was first started (for statistics). */
    val startedAtWall: Long,
    val pauseCount: Int = 0,
    /** Id of the scheduled session that started this one, if any. */
    val scheduledId: Long? = null,
)

/** Clock source abstraction so the math is testable. */
interface Clocks {
    fun elapsedRealtime(): Long
    fun wallTime(): Long
    fun bootCount(): Int
}

object TimerMath {
    /** If the device was off longer than this, we don't trust the wall clock delta. */
    const val MAX_OFFLINE_GAP_MS: Long = 24L * 60 * 60 * 1000

    fun start(
        sessionId: String,
        taskName: String,
        plannedMs: Long,
        clocks: Clocks,
        scheduledId: Long? = null,
    ): SessionSnapshot {
        require(plannedMs > 0) { "plannedMs must be positive" }
        val wall = clocks.wallTime()
        return SessionSnapshot(
            sessionId = sessionId,
            taskName = taskName,
            plannedMs = plannedMs,
            status = SessionStatus.RUNNING,
            accumulatedMs = 0,
            segmentStartElapsed = clocks.elapsedRealtime(),
            segmentStartWall = wall,
            bootCount = clocks.bootCount(),
            startedAtWall = wall,
            scheduledId = scheduledId,
        )
    }

    /** Duration of the current running segment. */
    fun segmentMs(s: SessionSnapshot, clocks: Clocks): Long {
        if (s.status != SessionStatus.RUNNING) return 0
        val nowElapsed = clocks.elapsedRealtime()
        val sameBoot = s.bootCount == clocks.bootCount() && nowElapsed >= s.segmentStartElapsed
        if (sameBoot) return nowElapsed - s.segmentStartElapsed
        // Rebooted during the segment: bridge with the wall clock.
        val wallDelta = clocks.wallTime() - s.segmentStartWall
        return if (wallDelta in 0..MAX_OFFLINE_GAP_MS) wallDelta else 0
    }

    /** Work time done so far, clamped to [0, plannedMs]. */
    fun workedMs(s: SessionSnapshot, clocks: Clocks): Long =
        (s.accumulatedMs + segmentMs(s, clocks)).coerceIn(0, s.plannedMs)

    fun remainingMs(s: SessionSnapshot, clocks: Clocks): Long = s.plannedMs - workedMs(s, clocks)

    fun isFinished(s: SessionSnapshot, clocks: Clocks): Boolean = remainingMs(s, clocks) <= 0

    fun progress(s: SessionSnapshot, clocks: Clocks): Float =
        (workedMs(s, clocks).toDouble() / s.plannedMs).toFloat().coerceIn(0f, 1f)

    /** Folds the running segment into [SessionSnapshot.accumulatedMs] and re-anchors on the current clocks. */
    fun checkpoint(s: SessionSnapshot, clocks: Clocks): SessionSnapshot {
        if (s.status != SessionStatus.RUNNING) return s
        return s.copy(
            accumulatedMs = workedMs(s, clocks),
            segmentStartElapsed = clocks.elapsedRealtime(),
            segmentStartWall = clocks.wallTime(),
            bootCount = clocks.bootCount(),
        )
    }

    fun pause(s: SessionSnapshot, clocks: Clocks): SessionSnapshot {
        if (s.status != SessionStatus.RUNNING) return s
        return checkpoint(s, clocks).copy(status = SessionStatus.PAUSED, pauseCount = s.pauseCount + 1)
    }

    fun resume(s: SessionSnapshot, clocks: Clocks): SessionSnapshot {
        if (s.status != SessionStatus.PAUSED) return s
        return s.copy(
            status = SessionStatus.RUNNING,
            segmentStartElapsed = clocks.elapsedRealtime(),
            segmentStartWall = clocks.wallTime(),
            bootCount = clocks.bootCount(),
        )
    }
}

/** HH:MM:SS, rounding the seconds up so the display reaches 00:00:00 exactly at the end. */
fun formatHms(ms: Long): String {
    val totalSec = ((ms.coerceAtLeast(0) + 999) / 1000)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec)
}

/** Whole minutes, rounded to nearest (used for "you worked N minutes"). */
fun roundedMinutes(ms: Long): Int = ((ms + 30_000) / 60_000).toInt()
