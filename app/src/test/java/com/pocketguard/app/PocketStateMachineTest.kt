package com.pocketguard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketStateMachineTest {

    private var now = 0L

    @Test
    fun `screen waking inside pocket enables protection quickly`() {
        val machine = createScreenOnMachine()
        val covered = sensors(near = true, lux = 1f)

        assertEquals(PocketState.PROBING, machine.processSensors(covered))
        advanceTo(301L)
        assertEquals(PocketState.POCKETED, machine.processSensors(covered))
    }

    @Test
    fun `covering proximity while using phone in dark room is rejected`() {
        val machine = createScreenOnMachine()
        advanceTo(3_000L)
        machine.processSensors(sensors(near = false, lux = 1f))

        assertEquals(
            PocketState.PROBING,
            machine.processSensors(sensors(near = true, lux = 1f, motion = 1.2f))
        )
        advanceTo(6_001L)
        assertEquals(
            PocketState.NORMAL,
            machine.processSensors(sensors(near = true, lux = 1f))
        )
    }

    @Test
    fun `active screen insertion sequence enables protection`() {
        val machine = createActiveBrightMachine()

        advanceTo(3_100L)
        machine.processSensors(sensors(near = false, lux = 100f, motion = 1.2f))
        advanceTo(3_200L)
        machine.processSensors(sensors(near = false, lux = 2f))
        advanceTo(3_300L)
        assertEquals(
            PocketState.PROBING,
            machine.processSensors(sensors(near = true, lux = 2f))
        )
        advanceTo(3_901L)
        assertEquals(
            PocketState.POCKETED,
            machine.processSensors(sensors(near = true, lux = 2f))
        )
    }

    @Test
    fun `relative light drop can confirm pocket before lux reaches zero`() {
        val machine = createScreenOnMachine()
        advanceTo(3_000L)
        machine.processSensors(sensors(near = false, lux = 1_000f))
        advanceTo(3_100L)
        machine.processSensors(sensors(near = false, lux = 1_000f, motion = 1.2f))
        advanceTo(3_200L)
        machine.processSensors(sensors(near = false, lux = 50f))
        advanceTo(3_300L)
        machine.processSensors(sensors(near = true, lux = 50f))
        advanceTo(3_901L)

        assertEquals(
            PocketState.POCKETED,
            machine.processSensors(sensors(near = true, lux = 50f))
        )
    }

    @Test
    fun `placing phone face down on table does not enable protection`() {
        val machine = createActiveBrightMachine()
        advanceTo(3_100L)
        machine.processSensors(sensors(near = false, lux = 100f, motion = 1.2f))
        advanceTo(3_200L)
        machine.processSensors(sensors(near = false, lux = 1f))
        advanceTo(3_300L)

        assertEquals(
            PocketState.NORMAL,
            machine.processSensors(
                sensors(near = true, lux = 1f, faceDown = true)
            )
        )
    }

    @Test
    fun `short proximity glitch cannot enter pocket state`() {
        val machine = createScreenOnMachine()

        assertEquals(
            PocketState.PROBING,
            machine.processSensors(sensors(near = true, lux = 1f))
        )
        advanceTo(100L)
        assertEquals(
            PocketState.NORMAL,
            machine.processSensors(sensors(near = false, lux = 1f))
        )
    }

    @Test
    fun `uncovering exits protection after release debounce`() {
        val machine = createPocketedMachine()

        advanceTo(400L)
        assertEquals(
            PocketState.POCKETED,
            machine.processSensors(sensors(near = false, lux = 20f))
        )
        advanceTo(551L)
        assertEquals(
            PocketState.NORMAL,
            machine.processSensors(sensors(near = false, lux = 20f))
        )
    }

    @Test
    fun `screen off always removes protection`() {
        val machine = createPocketedMachine()

        machine.onScreenTurnedOff()

        assertEquals(PocketState.NORMAL, machine.state)
        assertEquals(
            PocketState.NORMAL,
            machine.processSensors(sensors(near = true, lux = 1f))
        )
    }

    @Test
    fun `real touch locks when device admin lock succeeds`() {
        val machine = createPocketedMachine()
        machine.onShouldLockScreen = { true }

        val state = machine.processSensors(
            sensors(near = true, lux = 1f).copy(touchDetected = true)
        )

        assertEquals(PocketState.LOCK_SCREEN, state)
    }

    @Test
    fun `failed device admin lock keeps touch protection active`() {
        val machine = createPocketedMachine()
        machine.onShouldLockScreen = { false }

        val state = machine.processSensors(
            sensors(near = true, lux = 1f).copy(touchDetected = true)
        )

        assertEquals(PocketState.POCKETED, state)
    }

    private fun createScreenOnMachine(): PocketStateMachine {
        now = 0L
        return PocketStateMachine { now }.also { it.onScreenTurnedOn() }
    }

    private fun createActiveBrightMachine(): PocketStateMachine {
        val machine = createScreenOnMachine()
        advanceTo(3_000L)
        machine.processSensors(sensors(near = false, lux = 100f))
        return machine
    }

    private fun createPocketedMachine(): PocketStateMachine {
        val machine = createScreenOnMachine()
        val covered = sensors(near = true, lux = 1f)
        assertEquals(PocketState.PROBING, machine.processSensors(covered))
        advanceTo(301L)
        assertEquals(PocketState.POCKETED, machine.processSensors(covered))
        return machine
    }

    private fun sensors(
        near: Boolean,
        lux: Float,
        motion: Float = 0f,
        faceDown: Boolean = false
    ) = PocketSensors(
        proximityBlocked = near,
        lightLux = lux,
        isStill = motion < PocketSensors.MOTION_THRESHOLD,
        motionMagnitude = motion,
        isFlatFaceDown = faceDown
    )

    private fun advanceTo(time: Long) {
        now = time
    }
}
