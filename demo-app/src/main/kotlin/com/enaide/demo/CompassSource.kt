package com.enaide.demo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.roundToInt

/**
 * Sorgente della **bussola** del dispositivo: orientamento (azimuth) in gradi,
 * 0 = nord, in senso orario — come un vero navigatore.
 *
 * Usa il sensore virtuale [Sensor.TYPE_ROTATION_VECTOR] (fonde magnetometro,
 * accelerometro e giroscopio): più stabile del solo magnetometro e già filtrato
 * dal sistema. È quello consigliato dai navigatori open source (OsmAnd, Organic
 * Maps) per orientare la mappa quando il bearing GPS non è affidabile (da fermi
 * o a bassa velocità).
 *
 * Emette un [Flow] di gradi azimuth, smussato e arrotondato per ridurre il jitter.
 */
class CompassSource(context: Context) {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** True se il device ha un sensore di rotazione utilizzabile. */
    val isAvailable: Boolean get() = rotationSensor != null

    fun asFlow(): Flow<Double> = callbackFlow {
        val sensor = rotationSensor ?: run { close(); return@callbackFlow }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed = Double.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[0] = azimuth in radianti rispetto al nord magnetico.
                val deg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0)

                // Smussatura esponenziale circolare per togliere il tremolio.
                smoothed = if (smoothed.isNaN()) deg else smoothAngle(smoothed, deg, ALPHA)
                trySend(smoothed.roundToInt().toDouble())
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    /** Interpolazione angolare (gestisce il wrap 359→0). */
    private fun smoothAngle(prev: Double, next: Double, alpha: Double): Double {
        var diff = next - prev
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return ((prev + alpha * diff) + 360.0) % 360.0
    }

    private companion object {
        /** Fattore di smussatura (0..1): più basso = più stabile ma più lento. */
        const val ALPHA = 0.15
    }
}
