package com.focuslock.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake clocks that can simulate sleep, reboot and manual wall-clock changes. */
class FakeClocks(var elapsed: Long = 1_000_000, var wall: Long = 1_700_000_000_000, var boot: Int = 5) : Clocks {
    override fun elapsedRealtime() = elapsed
    override fun wallTime() = wall
    override fun bootCount() = boot
    fun advance(ms: Long) { elapsed += ms; wall += ms }
    fun reboot(offMs: Long) { boot += 1; elapsed = 30_000; wall += offMs }
}

private const val MIN = 60_000L

class TimerMathTest {

    @Test fun `scenario 1 - 45 minutes completes automatically`() {
        val c = FakeClocks()
        val s = TimerMath.start("a", "", 45 * MIN, c)
        c.advance(44 * MIN + 59_000)
        assertFalse(TimerMath.isFinished(s, c))
        assertEquals("00:00:01", formatHms(TimerMath.remainingMs(s, c)))
        c.advance(1_000)
        assertTrue(TimerMath.isFinished(s, c))
        assertEquals(45 * MIN, TimerMath.workedMs(s, c))
        assertEquals("00:00:00", formatHms(TimerMath.remainingMs(s, c)))
    }

    @Test fun `scenario 2 - pause after 10 minutes for 5 minutes leaves 35`() {
        val c = FakeClocks()
        var s = TimerMath.start("a", "", 45 * MIN, c)
        c.advance(10 * MIN)
        s = TimerMath.pause(s, c)
        c.advance(5 * MIN)
        assertEquals(35 * MIN, TimerMath.remainingMs(s, c))
        s = TimerMath.resume(s, c)
        assertEquals(35 * MIN, TimerMath.remainingMs(s, c))
        c.advance(35 * MIN)
        assertTrue(TimerMath.isFinished(s, c))
        assertEquals(1, s.pauseCount)
    }

    @Test fun `screen off - elapsed realtime keeps counting`() {
        val c = FakeClocks()
        val s = TimerMath.start("a", "", 25 * MIN, c)
        c.elapsed += 10 * MIN // deep sleep: elapsedRealtime still advances
        c.wall += 10 * MIN
        assertEquals(15 * MIN, TimerMath.remainingMs(s, c))
    }

    @Test fun `wall clock change does not affect running timer`() {
        val c = FakeClocks()
        val s = TimerMath.start("a", "", 60 * MIN, c)
        c.advance(20 * MIN)
        c.wall -= 3 * 60 * MIN // user sets the clock back 3 hours / time zone change
        assertEquals(40 * MIN, TimerMath.remainingMs(s, c))
        c.wall += 10 * 60 * MIN
        assertEquals(40 * MIN, TimerMath.remainingMs(s, c))
    }

    @Test fun `scenario 6 - reboot during a running session counts the off time by wall clock`() {
        val c = FakeClocks()
        var s = TimerMath.start("a", "", 60 * MIN, c)
        c.advance(20 * MIN)
        s = TimerMath.checkpoint(s, c)
        c.advance(1 * MIN)
        c.reboot(offMs = 4 * MIN)
        // 20 (checkpointed) + 1 (since checkpoint) + 4 (off) = 25 worked
        assertEquals(35 * MIN, TimerMath.remainingMs(s, c))
        s = TimerMath.checkpoint(s, c) // re-anchor on the new boot
        c.advance(5 * MIN)
        assertEquals(30 * MIN, TimerMath.remainingMs(s, c))
    }

    @Test fun `reboot while paused keeps the remaining time`() {
        val c = FakeClocks()
        var s = TimerMath.start("a", "", 30 * MIN, c)
        c.advance(10 * MIN)
        s = TimerMath.pause(s, c)
        c.reboot(offMs = 60 * MIN)
        assertEquals(20 * MIN, TimerMath.remainingMs(s, c))
    }

    @Test fun `reboot with an absurd wall clock gap does not count it`() {
        val c = FakeClocks()
        var s = TimerMath.start("a", "", 30 * MIN, c)
        c.advance(10 * MIN)
        s = TimerMath.checkpoint(s, c)
        c.boot += 1; c.elapsed = 1_000; c.wall -= 5 * 60 * MIN // clock went backwards across reboot
        assertEquals(20 * MIN, TimerMath.remainingMs(s, c))
    }

    @Test fun `worked time never exceeds planned`() {
        val c = FakeClocks()
        val s = TimerMath.start("a", "", 15 * MIN, c)
        c.advance(3 * 60 * MIN)
        assertEquals(15 * MIN, TimerMath.workedMs(s, c))
        assertEquals(1f, TimerMath.progress(s, c))
    }

    @Test fun `format rounds seconds up and supports hours`() {
        assertEquals("01:30:00", formatHms(90 * MIN))
        assertEquals("00:00:01", formatHms(1))
        assertEquals("00:00:00", formatHms(0))
        assertEquals("00:00:00", formatHms(-500))
    }
}
