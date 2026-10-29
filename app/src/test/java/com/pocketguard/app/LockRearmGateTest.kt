package com.pocketguard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockRearmGateTest {
    @Test fun `lock stays inhibited until sensor clears`() {
        val gate = LockRearmGate()
        assertFalse(gate.waitingForClear)
        gate.onLockRequested()
        assertTrue(gate.waitingForClear)
        gate.onSensorClear()
        assertFalse(gate.waitingForClear)
    }

    @Test fun `waiting for clear survives service restart`() {
        val gate = LockRearmGate(initiallyWaitingForClear = true)
        assertTrue(gate.waitingForClear)
        gate.onSensorClear()
        assertFalse(gate.waitingForClear)
    }
}
