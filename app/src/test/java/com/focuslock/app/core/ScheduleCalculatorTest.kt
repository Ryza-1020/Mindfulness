package com.focuslock.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = jerusalem) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone)

    // 2026-11-19 is a Thursday, 2026-11-20 Friday, 2026-11-22 Sunday.

    @Test fun `workdays 09-00 Sunday to Thursday skips the weekend`() {
        val rule = ScheduleRule(RepeatType.WORKDAYS, 9, 0)
        assertEquals(at(2026, 11, 19, 9, 0), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 19, 8, 0)))
        assertEquals(at(2026, 11, 22, 9, 0), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 19, 9, 0)))
        assertEquals(at(2026, 11, 22, 9, 0), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 20, 7, 0)))
    }

    @Test fun `daily rolls to tomorrow after today's time`() {
        val rule = ScheduleRule(RepeatType.DAILY, 14, 30)
        assertEquals(at(2026, 11, 20, 14, 30), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 19, 15, 0)))
    }

    @Test fun `custom days`() {
        val rule = ScheduleRule(RepeatType.CUSTOM, 20, 0, DaysMask.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
        assertEquals(at(2026, 11, 20, 20, 0), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 17, 10, 0)))
        assertNull(ScheduleCalculator.nextTrigger(ScheduleRule(RepeatType.CUSTOM, 9, 0, 0), at(2026, 11, 17, 10, 0)))
    }

    @Test fun `once in the future and in the past`() {
        val day = LocalDate.of(2026, 12, 1).toEpochDay()
        val rule = ScheduleRule(RepeatType.ONCE, 9, 0, dateEpochDay = day)
        assertEquals(at(2026, 12, 1, 9, 0), ScheduleCalculator.nextTrigger(rule, at(2026, 11, 17, 10, 0)))
        assertNull(ScheduleCalculator.nextTrigger(rule, at(2026, 12, 1, 9, 0)))
    }

    @Test fun `time zone change keeps local 09-00`() {
        val rule = ScheduleRule(RepeatType.DAILY, 9, 0)
        val london = ZoneId.of("Europe/London")
        val next = ScheduleCalculator.nextTrigger(rule, at(2026, 11, 17, 8, 0, london))!!
        assertEquals(9, next.hour)
        assertEquals(london, next.zone)
    }

    @Test fun `DST spring-forward gap shifts forward instead of skipping`() {
        // Israel 2026: clocks jump 02:00 -> 03:00 on Friday 27 March.
        val rule = ScheduleRule(RepeatType.DAILY, 2, 30)
        val next = ScheduleCalculator.nextTrigger(rule, at(2026, 3, 27, 0, 0))!!
        assertEquals(LocalDate.of(2026, 3, 27), next.toLocalDate())
        assertEquals(3, next.hour)
    }
}
