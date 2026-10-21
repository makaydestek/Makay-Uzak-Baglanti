package com.makay.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {
    @Test
    fun rateLimitsBadPinAttempts() {
        val gate = RemoteGuard.PinGate(windowMs = 60_000L, maxAttempts = 3)
        assertTrue(gate.allow("R:badpin"))
        assertTrue(gate.allow("R:badpin"))
        assertTrue(gate.allow("R:badpin"))
        assertFalse(gate.allow("R:badpin"))
        gate.reset("R:badpin")
        assertTrue(gate.allow("R:badpin"))
    }

    @Test
    fun openAttemptsLimitedLikeWindowsBridge() {
        val gate = RemoteGuard.PinGate(windowMs = 60_000L, maxAttempts = 10)
        repeat(10) { assertTrue(gate.allow("ROOM:open")) }
        assertFalse(gate.allow("ROOM:open"))
    }
}