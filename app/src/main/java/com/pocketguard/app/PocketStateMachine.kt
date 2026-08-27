package com.pocketguard.app

/**
 * Conservative pocket detection state machine.
 *
 * Proximity is the hard gate. Darkness is confirmed using both an absolute
 * threshold and a baseline-relative drop. While the screen is already in use,
 * recent movement and a recent light drop are also required. This avoids
 * locking the phone merely because it is being used in a dark room.
 */
enum class PocketState {
    NORMAL,
    PROBING,
    POCKETED,
    LOCK_SCREEN
}

data class PocketSensors(
    val proximityBlocked: Boolean = false,
    val lightLux: Float = Float.MAX_VALUE,
    val isStill: Boolean = true,
    val motionMagnitude: Float = 0f,
    val isFlatFaceDown: Boolean = false,
    /** A landscape device is much more likely to be in active use, such as gaming. */
    val isLandscape: Boolean = false,
    val touchDetected: Boolean = false
) {
    companion object {
        const val ENTER_DARK_LUX = 3f
        const val EXIT_DARK_LUX = 8f
        const val MIN_BASELINE_FOR_RELATIVE_DROP = 10f
        const val RELATIVE_DARK_RATIO = 0.10f
        const val MOTION_THRESHOLD = 0.8f
    }
}

class PocketStateMachine(
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        private const val WAKE_ENTRY_DELAY_MS = 300L
        // Keep the active-screen confirmation deliberately longer than a wake-up
        // confirmation. This avoids treating a hand briefly covering the top
        // sensor during landscape gaming as a pocket insertion.
        private const val ACTIVE_ENTRY_DELAY_MS = 1_000L
        private const val WAKE_CONTEXT_WINDOW_MS = 1_000L
        private const val RECENT_MOTION_WINDOW_MS = 1_800L
        private const val RECENT_DARK_DROP_WINDOW_MS = 1_800L
        private const val PROBING_TIMEOUT_MS = 3_000L
        private const val RELEASE_DELAY_MS = 150L
        private const val LOCK_COOLDOWN_MS = 3_000L
        private const val BASELINE_ALPHA = 0.12f
    }

    var state: PocketState = PocketState.NORMAL
        private set

    val probeConfirmationDelayMs: Long
        get() = if (candidateStartedAfterWake) WAKE_ENTRY_DELAY_MS else ACTIVE_ENTRY_DELAY_MS

    private var screenInteractive = false
    private var screenOnTime = Long.MIN_VALUE
    private var probingStartTime = 0L
    private var candidateStartedAfterWake = false
    private var suppressedUntilProximityClears = false
    private var releaseStartTime: Long? = null
    private var lockCooldownUntil = 0L

    private var ambientBaselineLux: Float? = null
    private var wasDark = false
    private var lastDarkDropTime = Long.MIN_VALUE
    private var lastSignificantMotionTime = Long.MIN_VALUE

    var onStateChanged: ((PocketState) -> Unit)? = null
    var onShouldLockScreen: (() -> Boolean)? = null

    fun onScreenTurnedOn(treatAsActualWake: Boolean = true) {
        screenInteractive = true
        screenOnTime = if (treatAsActualWake) clock() else Long.MIN_VALUE
        suppressedUntilProximityClears = false
        transitionTo(PocketState.NORMAL)
    }

    fun onScreenTurnedOff() {
        screenInteractive = false
        screenOnTime = Long.MIN_VALUE
        resetDetectionHistory()
        transitionTo(PocketState.NORMAL)
    }

    fun processSensors(sensors: PocketSensors): PocketState {
        if (!screenInteractive) return state

        val now = clock()
        observeEvidence(sensors, now)

        when (state) {
            PocketState.NORMAL -> processNormal(sensors, now)
            PocketState.PROBING -> processProbing(sensors, now)
            PocketState.POCKETED -> processPocketed(sensors, now)
            PocketState.LOCK_SCREEN -> processLocked(sensors, now)
        }

        return state
    }

    private fun processNormal(sensors: PocketSensors, now: Long) {
        if (!sensors.proximityBlocked) {
            suppressedUntilProximityClears = false
            updateAmbientBaseline(sensors.lightLux)
            return
        }

        if (suppressedUntilProximityClears || sensors.isFlatFaceDown || sensors.isLandscape) return

        probingStartTime = now
        candidateStartedAfterWake = elapsedSince(screenOnTime, now) <= WAKE_CONTEXT_WINDOW_MS
        transitionTo(PocketState.PROBING)
    }

    private fun processProbing(sensors: PocketSensors, now: Long) {
        if (!sensors.proximityBlocked) {
            transitionTo(PocketState.NORMAL)
            return
        }

        if (sensors.isFlatFaceDown || sensors.isLandscape) {
            rejectCandidateUntilClear()
            return
        }

        val dark = isDark(sensors.lightLux)
        val stableFor = now - probingStartTime
        val requiredDelay = if (candidateStartedAfterWake) {
            WAKE_ENTRY_DELAY_MS
        } else {
            ACTIVE_ENTRY_DELAY_MS
        }

        val hasWakeContext = candidateStartedAfterWake
        val hasInsertionSequence =
            elapsedSince(lastSignificantMotionTime, now) <= RECENT_MOTION_WINDOW_MS &&
                elapsedSince(lastDarkDropTime, now) <= RECENT_DARK_DROP_WINDOW_MS

        if (dark && stableFor >= requiredDelay && (hasWakeContext || hasInsertionSequence)) {
            releaseStartTime = null
            transitionTo(PocketState.POCKETED)
            return
        }

        if (stableFor >= PROBING_TIMEOUT_MS) {
            rejectCandidateUntilClear()
        }
    }

    private fun processPocketed(sensors: PocketSensors, now: Long) {
        if (shouldRelease(sensors, now)) {
            transitionTo(PocketState.NORMAL)
            return
        }

        if (sensors.touchDetected && now > lockCooldownUntil) {
            val locked = onShouldLockScreen?.invoke() == true
            if (locked) {
                lockCooldownUntil = now + LOCK_COOLDOWN_MS
                transitionTo(PocketState.LOCK_SCREEN)
            }
        }
    }

    private fun processLocked(sensors: PocketSensors, now: Long) {
        if (shouldRelease(sensors, now)) {
            transitionTo(PocketState.NORMAL)
        }
    }

    private fun observeEvidence(sensors: PocketSensors, now: Long) {
        if (sensors.motionMagnitude >= PocketSensors.MOTION_THRESHOLD) {
            lastSignificantMotionTime = now
        }

        val dark = isDark(sensors.lightLux)
        if (dark && !wasDark && isRelativeDarkDrop(sensors.lightLux)) {
            lastDarkDropTime = now
        }
        wasDark = dark
    }

    private fun updateAmbientBaseline(lux: Float) {
        if (!lux.isFinite() || lux == Float.MAX_VALUE || lux < 0f) return

        ambientBaselineLux = ambientBaselineLux?.let { baseline ->
            baseline + BASELINE_ALPHA * (lux - baseline)
        } ?: lux
    }

    private fun isDark(lux: Float): Boolean {
        if (!lux.isFinite() || lux == Float.MAX_VALUE || lux < 0f) return false
        return lux <= PocketSensors.ENTER_DARK_LUX || isRelativeDarkDrop(lux)
    }

    private fun isRelativeDarkDrop(lux: Float): Boolean {
        val baseline = ambientBaselineLux ?: return false
        return baseline >= PocketSensors.MIN_BASELINE_FOR_RELATIVE_DROP &&
            lux <= baseline * PocketSensors.RELATIVE_DARK_RATIO
    }

    private fun shouldRelease(sensors: PocketSensors, now: Long): Boolean {
        val releaseCondition = !sensors.proximityBlocked ||
            isBrightEnoughToRelease(sensors.lightLux)

        if (!releaseCondition) {
            releaseStartTime = null
            return false
        }

        val started = releaseStartTime
        if (started == null) {
            releaseStartTime = now
            return false
        }

        if (now - started < RELEASE_DELAY_MS) return false

        releaseStartTime = null
        suppressedUntilProximityClears = sensors.proximityBlocked
        if (!sensors.proximityBlocked) updateAmbientBaseline(sensors.lightLux)
        return true
    }

    private fun isBrightEnoughToRelease(lux: Float): Boolean {
        if (!lux.isFinite() || lux == Float.MAX_VALUE || lux < 0f) return false
        val baseline = ambientBaselineLux
        val exitThreshold = if (
            baseline != null && baseline >= PocketSensors.MIN_BASELINE_FOR_RELATIVE_DROP
        ) {
            maxOf(PocketSensors.EXIT_DARK_LUX, baseline * 0.20f)
        } else {
            PocketSensors.EXIT_DARK_LUX
        }
        return lux >= exitThreshold
    }

    private fun rejectCandidateUntilClear() {
        suppressedUntilProximityClears = true
        transitionTo(PocketState.NORMAL)
    }

    private fun transitionTo(next: PocketState) {
        if (state == next) return
        state = next
        onStateChanged?.invoke(next)
    }

    private fun resetDetectionHistory() {
        probingStartTime = 0L
        candidateStartedAfterWake = false
        suppressedUntilProximityClears = false
        releaseStartTime = null
        wasDark = false
        lastDarkDropTime = Long.MIN_VALUE
        lastSignificantMotionTime = Long.MIN_VALUE
    }

    private fun elapsedSince(timestamp: Long, now: Long): Long {
        if (timestamp == Long.MIN_VALUE) return Long.MAX_VALUE
        return now - timestamp
    }

    fun reset() {
        resetDetectionHistory()
        transitionTo(PocketState.NORMAL)
    }
}
