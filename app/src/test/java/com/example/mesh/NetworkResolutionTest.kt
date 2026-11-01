package com.example.mesh

import org.junit.Assert.*
import org.junit.Test

class NetworkResolutionTest {
    @Test fun settingsReturnRetriesOnceOnlyAfterLeaving() {
        val retry = NetworkSettingsRetry()
        assertFalse(retry.resumed())
        retry.opened()
        assertFalse(retry.resumed())
        retry.paused()
        assertTrue(retry.resumed())
        assertFalse(retry.resumed())
        retry.paused()
        assertFalse(retry.resumed())
        retry.opened(); retry.paused()
        assertTrue(retry.resumed())
    }

    @Test fun cancellationDoesNotArmAnAutomaticRetry() {
        val retry = NetworkSettingsRetry()
        retry.paused()
        assertFalse(retry.resumed())
    }

    @Test fun actionableFailureDoesNotLeakRawReason() {
        val failure = NetworkResolutionException(NetworkResolution.HOTSPOT)
        assertEquals(NetworkResolution.HOTSPOT, failure.resolution)
        assertFalse(failure.message!!.contains("Android reason"))
        assertTrue(failure.message!!.contains("हॉटस्पॉट"))
    }
}
