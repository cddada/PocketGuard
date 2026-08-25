package com.pocketguard.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class SensorMonitor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var proximityBlocked = false
    private var lightLux = Float.MAX_VALUE
    private var isStill = true
    private var motionMagnitude = 0f
    private var isFlatFaceDown = false
    private var started = false

    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private var gravityInitialized = false

    var onSensorUpdate: ((PocketSensors) -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
        gravityInitialized = false
        proximityBlocked = false
        lightLux = Float.MAX_VALUE
        isStill = true
        motionMagnitude = 0f
        isFlatFaceDown = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                proximityBlocked = event.values[0] < (proximitySensor?.maximumRange ?: 5f)
                emitUpdate()
            }

            Sensor.TYPE_LIGHT -> {
                lightLux = event.values[0]
                emitUpdate()
            }

            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometer(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processAccelerometer(values: FloatArray) {
        val alpha = 0.8f

        if (!gravityInitialized) {
            System.arraycopy(values, 0, gravity, 0, 3)
            gravityInitialized = true
            updateOrientation()
            emitUpdate()
            return
        }

        for (i in 0..2) {
            gravity[i] = alpha * gravity[i] + (1 - alpha) * values[i]
            linearAccel[i] = values[i] - gravity[i]
        }

        motionMagnitude = sqrt(
            linearAccel[0] * linearAccel[0] +
            linearAccel[1] * linearAccel[1] +
            linearAccel[2] * linearAccel[2]
        )

        isStill = motionMagnitude < PocketSensors.MOTION_THRESHOLD
        updateOrientation()
        emitUpdate()
    }

    private fun updateOrientation() {
        val gravityMagnitude = sqrt(
            gravity[0] * gravity[0] +
                gravity[1] * gravity[1] +
                gravity[2] * gravity[2]
        )
        isFlatFaceDown = gravityMagnitude > 7f && gravity[2] / gravityMagnitude < -0.85f
    }

    private fun emitUpdate() {
        onSensorUpdate?.invoke(
            PocketSensors(
                proximityBlocked = proximityBlocked,
                lightLux = lightLux,
                isStill = isStill,
                motionMagnitude = motionMagnitude,
                isFlatFaceDown = isFlatFaceDown,
                touchDetected = false
            )
        )
    }
}
