package com.pocketguard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketStateMachineTest {

    private var now = 0L
    private val pocketedSensors = PocketSensors(
        proximityBlocked = true,
        lightLux = 1f,
        isStill = true
    )

    @Test
    fun `pocket detection does not count overlay as a touch`() {
        val machine = createPocketedMachine()

        assertEquals(PocketState.POCKETED, machine.processSensors(pocketedSensors))
    }

    @Test
    fun `real touch locks when device admin lock succeeds`() {
        val machine = createPocketedMachine()
        machine.onShouldLockScreen = { true }

        val state = machine.processSensors(pocketedSensors.copy(touchDetected = true))

        assertEquals(PocketState.LOCK_SCREEN, state)
    }

    @Test
    fun `failed device admin lock keeps touch protection active`() {
        val machine = createPocketedMachine()
        machine.onShouldLockScreen = { false }

        val state = machine.processSensors(pocketedSensors.copy(touchDetected = true))

        assertEquals(PocketState.POCKETED, state)
    }

    @Test
    fun `clearing proximity returns to normal`() {
        val machine = createPocketedMachine()

        val state = machine.processSensors(pocketedSensors.copy(proximityBlocked = false))

        assertEquals(PocketState.NORMAL, state)
    }

    private fun createPocketedMachine(): PocketStateMachine {
        now = 0L
        val machine = PocketStateMachine { now }
        assertEquals(
            PocketState.PROBING,
            machine.processSensors(pocketedSensors.copy(lightLux = Float.MAX_VALUE, isStill = false))
        )
        now = 801L
        assertEquals(PocketState.POCKETED, machine.processSensors(pocketedSensors))
        return machine
    }
}
