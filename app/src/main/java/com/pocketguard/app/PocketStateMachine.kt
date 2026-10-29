package com.pocketguard.app

/**
 * Pocket detection state machine:
 *
 *  NORMAL ──→ proximity blocked ──→ PROBING
 *     ↑                                  │
 *     │                          light < threshold
 *     │                                  │
 *     │                           (wait 800ms)
 *     │                                  │
 *     │                              POCKETED
 *     │                                  │
 *     │                          touch detected?
 *     │                            YES    │    NO
 *     │                             ↓     │
 *     │                        LOCK_SCREEN │
 *     │                                  │
 *     └──── proximity clear ─────────────┘
 */
enum class PocketState {
    NORMAL,       // Phone in normal use
    PROBING,      // Proximity triggered, waiting for confirmation
    POCKETED,     // Confirmed in pocket, monitoring touches
    LOCK_SCREEN   // Touch in pocket detected, screen locked
}

data class PocketSensors(
    val proximityBlocked: Boolean = false,   // distance == 0
    val lightLux: Float = Float.MAX_VALUE,   // ambient light
    val isStill: Boolean = false,            // phone not moving
    val touchDetected: Boolean = false       // touch in pocket mode
) {
    companion object {
        const val LIGHT_THRESHOLD_LUX = 10f
        const val STILL_THRESHOLD = 0.5f   // m/s² deviation from gravity
    }
}

class PocketStateMachine(
    private val clock: () -> Long = System::currentTimeMillis
) {

    var state: PocketState = PocketState.NORMAL
        private set

    private var probingStartTime = 0L
    private val probingDelayMs = 800L  // wait 800ms before confirming

    private var lockCooldownUntil = 0L
    private val lockCooldownMs = 3000L // prevent rapid re-lock

    var onStateChanged: ((PocketState) -> Unit)? = null
    var onShouldLockScreen: (() -> Boolean)? = null

    fun processSensors(sensors: PocketSensors): PocketState {
        val now = clock()
        val previousState = state

        state = when (state) {
            PocketState.NORMAL -> {
                if (sensors.proximityBlocked) {
                    probingStartTime = now
                    PocketState.PROBING
                } else {
                    PocketState.NORMAL
                }
            }

            PocketState.PROBING -> {
                if (!sensors.proximityBlocked) {
                    PocketState.NORMAL
                } else if (now - probingStartTime >= probingDelayMs
                    && sensors.lightLux < PocketSensors.LIGHT_THRESHOLD_LUX
                ) {
                    PocketState.POCKETED
                } else {
                    PocketState.PROBING
                }
            }

            PocketState.POCKETED -> {
                if (!sensors.proximityBlocked) {
                    PocketState.NORMAL
                } else if (sensors.touchDetected
                    && now > lockCooldownUntil
                ) {
                    val locked = onShouldLockScreen?.invoke() == true
                    if (locked) {
                        lockCooldownUntil = now + lockCooldownMs
                        PocketState.LOCK_SCREEN
                    } else {
                        PocketState.POCKETED
                    }
                } else {
                    PocketState.POCKETED
                }
            }

            PocketState.LOCK_SCREEN -> {
                if (!sensors.proximityBlocked) {
                    PocketState.NORMAL
                } else {
                    PocketState.LOCK_SCREEN
                }
            }
        }

        if (state != previousState) {
            onStateChanged?.invoke(state)
        }
        return state
    }

    fun reset() {
        state = PocketState.NORMAL
    }
}
