package com.hotspotmonitor.agent.finder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Averaged phone attitude over a spot test. */
data class OrientationReading(
    val pitchDeg: Double?,
    val rollDeg: Double?,
    /** Degrees from magnetic north (see OrientationMath.headingDeg), null without a magnetometer. */
    val magneticHeadingDeg: Double?,
    val orientation: String?,
)

/**
 * Samples the accelerometer and magnetometer while the phone is held still (spot test) and
 * averages them. Callbacks arrive on the main looper; the sums are guarded by this object.
 */
internal class OrientationSampler(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accel = DoubleArray(3)
    private val magnetic = DoubleArray(3)
    private var accelCount = 0
    private var magneticCount = 0

    /** Returns false when the phone has no accelerometer. */
    fun start(): Boolean {
        val sm = sensorManager ?: return false
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        return true
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.values.size < 3) return
        synchronized(this) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    for (i in 0..2) accel[i] += e.values[i].toDouble()
                    accelCount++
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    for (i in 0..2) magnetic[i] += e.values[i].toDouble()
                    magneticCount++
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Synchronized
    fun result(): OrientationReading? {
        if (accelCount == 0) return null
        val g = DoubleArray(3) { accel[it] / accelCount }
        val attitude = OrientationMath.attitude(g[0], g[1], g[2]) ?: return null
        val heading = if (magneticCount > 0) {
            OrientationMath.headingDeg(g, DoubleArray(3) { magnetic[it] / magneticCount })
        } else {
            null
        }
        return OrientationReading(attitude.pitchDeg, attitude.rollDeg, heading, attitude.orientation)
    }

    companion object {
        /** Magnetic -> true north using the World Magnetic Model at [lat]/[lon]. */
        fun trueHeading(magneticDeg: Double, lat: Double?, lon: Double?, atMs: Long): Double {
            if (lat == null || lon == null) return magneticDeg
            val declination = runCatching {
                GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, atMs).declination.toDouble()
            }.getOrDefault(0.0)
            return OrientationMath.normalizeDeg(magneticDeg + declination)
        }
    }
}

/** One fresh location fix for a spot test (the telemetry position may be minutes old). */
internal object LocationFix {
    private const val LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fix within [timeoutMs] (GPS first, then network), else a recent last-known fix, else null. */
    @SuppressLint("MissingPermission")
    suspend fun fresh(context: Context, timeoutMs: Long): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val provider = providers.firstOrNull() ?: return null
        val fix = try {
            withTimeoutOrNull(timeoutMs) { currentLocation(context, lm, provider) }
        } catch (e: SecurityException) {
            null
        }
        if (fix != null) return fix
        return providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { ageMs(it) <= LAST_KNOWN_MAX_AGE_MS }
            .minByOrNull { it.accuracy }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context, lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(location: Location?) {
                if (done.compareAndSet(false, true)) cont.resume(location)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                try {
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { location -> finish(location) }
                } catch (e: Exception) {
                    finish(null)
                }
            } else {
                // API 29: LocationListener has no default methods there, so all of them are implemented.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        runCatching { lm.removeUpdates(this) }
                        finish(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) {
                        runCatching { lm.removeUpdates(this) }
                        finish(null)
                    }
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                try {
                    lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    finish(null)
                }
            }
        }
}
