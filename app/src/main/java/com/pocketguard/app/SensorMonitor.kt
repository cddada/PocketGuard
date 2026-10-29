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
    private var isStill = false

    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private var gravityInitialized = false

    var onSensorUpdate: ((PocketSensors) -> Unit)? = null

    fun start() {
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
        sensorManager.unregisterListener(this)
        gravityInitialized = false
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
            return
        }

        for (i in 0..2) {
            gravity[i] = alpha * gravity[i] + (1 - alpha) * values[i]
            linearAccel[i] = values[i] - gravity[i]
        }

        val magnitude = sqrt(
            linearAccel[0] * linearAccel[0] +
            linearAccel[1] * linearAccel[1] +
            linearAccel[2] * linearAccel[2]
        )

        isStill = magnitude < PocketSensors.STILL_THRESHOLD
        emitUpdate()
    }

    private fun emitUpdate() {
        onSensorUpdate?.invoke(
            PocketSensors(
                proximityBlocked = proximityBlocked,
                lightLux = lightLux,
                isStill = isStill,
                touchDetected = false
            )
        )
    }
}
