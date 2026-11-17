package com.focuslock.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

enum class RepeatType { ONCE, DAILY, WORKDAYS, CUSTOM }

/**
 * A recurring (or one-off) start time expressed in *local* time.
 * Because it is stored as hour/minute and not as an instant, "09:00" stays 09:00 after a
 * time-zone change or a DST switch – the next trigger is simply recomputed.
 */
data class ScheduleRule(
    val repeat: RepeatType,
    val hour: Int,
    val minute: Int,
    /** Bit (dayOfWeek.value - 1) set for each selected day. Used by CUSTOM. */
    val daysMask: Int = 0,
    /** Local date for ONCE, as epoch day. */
    val dateEpochDay: Long? = null,
)

object DaysMask {
    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0
    fun of(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or bit(d) }
    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)

    val ALL: Int = DayOfWeek.values().fold(0) { acc, d -> acc or bit(d) }

    /** Israeli work week: Sunday – Thursday. */
    val WORKDAYS_IL: Int = of(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    )

    /** Display order for an Israeli week (Sunday first). */
    val WEEK_ORDER: List<DayOfWeek> = listOf(
        DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )
}

object ScheduleCalculator {

    fun effectiveMask(rule: ScheduleRule): Int = when (rule.repeat) {
        RepeatType.ONCE -> 0
        RepeatType.DAILY -> DaysMask.ALL
        RepeatType.WORKDAYS -> DaysMask.WORKDAYS_IL
        RepeatType.CUSTOM -> rule.daysMask
    }

    /**
     * Next start strictly after [after], in [after]'s zone. Returns null if the rule never fires again
     * (ONCE in the past, or CUSTOM with no days).
     * DST gaps are handled by [java.time.LocalDateTime.atZone], which shifts a non-existent local time forward.
     */
    fun nextTrigger(rule: ScheduleRule, after: ZonedDateTime): ZonedDateTime? {
        val zone = after.zone
        if (rule.repeat == RepeatType.ONCE) {
            val day = rule.dateEpochDay ?: return null
            val t = LocalDate.ofEpochDay(day).atTime(rule.hour, rule.minute).atZone(zone)
            return if (t.isAfter(after)) t else null
        }
        val mask = effectiveMask(rule)
        if (mask == 0) return null
        var date = after.toLocalDate()
        repeat(8) {
            if (DaysMask.contains(mask, date.dayOfWeek)) {
                val t = date.atTime(rule.hour, rule.minute).atZone(zone)
                if (t.isAfter(after)) return t
            }
            date = date.plusDays(1)
        }
        return null
    }
}
