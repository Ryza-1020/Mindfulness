package com.focuslock.app.core

/** Minimal, framework-free view of a finished session, for statistics. */
data class SessionRecordData(
    val dayEpoch: Long,
    val plannedMs: Long,
    val actualMs: Long,
    val completed: Boolean,
)

data class DayBar(val dayEpoch: Long, val minutes: Int)

data class StatsSummary(
    val todayMinutes: Int,
    val weekMinutes: Int,
    val weekCompleted: Int,
    val weekEndedEarly: Int,
    /** Actual / planned for the week, 0..1, or null when nothing was planned. */
    val weekRatio: Float?,
    /** Oldest first, exactly [days] entries. */
    val bars: List<DayBar>,
)

object StatsCalculator {
    /**
     * @param records sessions whose [SessionRecordData.dayEpoch] is within the window (others are ignored).
     * @param todayEpoch local "today" as epoch day.
     */
    fun summarize(records: List<SessionRecordData>, todayEpoch: Long, days: Int = 7): StatsSummary {
        val from = todayEpoch - (days - 1)
        val window = records.filter { it.dayEpoch in from..todayEpoch }
        val perDay = window.groupBy { it.dayEpoch }.mapValues { (_, list) -> list.sumOf { it.actualMs } }
        val bars = (from..todayEpoch).map { d -> DayBar(d, roundedMinutes(perDay[d] ?: 0L)) }
        val planned = window.sumOf { it.plannedMs }
        val actual = window.sumOf { it.actualMs }
        return StatsSummary(
            todayMinutes = roundedMinutes(perDay[todayEpoch] ?: 0L),
            weekMinutes = roundedMinutes(actual),
            weekCompleted = window.count { it.completed },
            weekEndedEarly = window.count { !it.completed },
            weekRatio = if (planned > 0) (actual.toDouble() / planned).toFloat().coerceIn(0f, 1f) else null,
            bars = bars,
        )
    }
}
