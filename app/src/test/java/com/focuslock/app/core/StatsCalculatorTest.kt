package com.focuslock.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCalculatorTest {
    private val min = 60_000L

    @Test fun `summarizes today, week, completed, early and ratio`() {
        val today = 20_000L
        val records = listOf(
            SessionRecordData(today, 45 * min, 45 * min, completed = true),
            SessionRecordData(today, 25 * min, 10 * min, completed = false),
            SessionRecordData(today - 2, 60 * min, 60 * min, completed = true),
            SessionRecordData(today - 9, 60 * min, 60 * min, completed = true), // outside window
        )
        val s = StatsCalculator.summarize(records, today)
        assertEquals(55, s.todayMinutes)
        assertEquals(115, s.weekMinutes)
        assertEquals(2, s.weekCompleted)
        assertEquals(1, s.weekEndedEarly)
        assertEquals(115f / 130f, s.weekRatio!!, 0.0001f)
        assertEquals(7, s.bars.size)
        assertEquals(today, s.bars.last().dayEpoch)
        assertEquals(60, s.bars[4].minutes)
    }

    @Test fun `empty`() {
        val s = StatsCalculator.summarize(emptyList(), 100)
        assertEquals(0, s.weekMinutes)
        assertNull(s.weekRatio)
        assertEquals(7, s.bars.size)
    }
}
