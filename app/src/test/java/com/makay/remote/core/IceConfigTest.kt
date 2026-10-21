package com.makay.remote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceConfigTest {
    @Test
    fun includeDefaultTurnDoesNotAddEmptyServer() {
        val servers = IceConfig.parse(includeDefaultTurn = true)
        assertTrue(servers.all { it.urls.isNotEmpty() })
        assertFalse(IceConfig.hasBuiltInTurnFallback())
    }

    @Test
    fun customTurnIsKept() {
        val servers = IceConfig.parse(
            turnUrl = "turn:example.com:3478",
            turnUsername = "u",
            turnPassword = "p",
            includeDefaultTurn = true
        )
        assertTrue(servers.any { it.urls.any { u -> u.contains("example.com") } })
    }
}