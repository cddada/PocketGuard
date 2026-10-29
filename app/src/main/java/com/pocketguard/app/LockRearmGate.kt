package com.pocketguard.app

/** A lock can be triggered again only after the sensor has reported clear. */
class LockRearmGate(initiallyWaitingForClear: Boolean = false) {
    var waitingForClear: Boolean = initiallyWaitingForClear
        private set

    fun onLockRequested() { waitingForClear = true }
    fun onSensorClear() { waitingForClear = false }
}
