package com.hotspotmonitor.agent.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import com.hotspotmonitor.agent.core.Clock
import com.hotspotmonitor.agent.core.KeyValueStore
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.PositionInfo
import com.hotspotmonitor.agent.protocol.WatchdogSettings
import com.hotspotmonitor.agent.protocol.round1
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One averaged accelerometer (+ magnetometer) sample. */
internal data class OrientationSample(
    val gravity: Vec3,
    val pitchDeg: Double?,
    val rollDeg: Double?,
    val azimuthDeg: Double?,
    /** Low motion during the sample (not being carried or shaken). */
    val steady: Boolean,
)

/** A location fix reduced to what we store and compare. */
internal data class Fix(val lat: Double, val lon: Double, val accM: Double?, val timeMs: Long, val elapsedNs: Long)

/** What the hub does with a moved detection. */
internal data class MovedEvent(val tiltChangeDeg: Double?, val movedM: Double?, val lat: Double?, val lon: Double?)

/**
 * Location (GPS + network providers, no Play services), tilt from the accelerometer and compass
 * from the magnetometer (plan 6.2 "Position"), compared with a stored reference to detect that the
 * installed phone was tilted or moved (plan 6.5).
 *
 * The reference is set when the Finder baseline changes (location from the baseline spot when it
 * has an accurate fix) or, when none is stored, from the first reading that stays stable for
 * [STABLE_HOLD_MS] after install. [resetReference] clears it.
 */
internal class PositionCollector(
    private val app: Context,
    private val prefs: KeyValueStore,
    private val failures: FailureLog,
    private val nowElapsedMs: () -> Long,
) {
    private val locationManager: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val sensorManager: SensorManager? = app.getSystemService(SensorManager::class.java)

    @Volatile
    private var fix: Fix? = null

    @Volatile
    private var lastAttemptMs: Long? = null

    private val refreshLock = Mutex()
    private val evalLock = Any()
    private val moveDetector = MoveDetector().apply { alerted = prefs.getBoolean(KEY_ALERTED, false) }
    private val gravityGate = StabilityGate<Vec3>(STABLE_HOLD_MS, STABLE_TILT_DEG) { a, b -> PositionMath.angleDeg(a, b) }
    private val locationGate = StabilityGate<Fix>(STABLE_HOLD_MS, STABLE_DISTANCE_M) { a, b -> PositionMath.distanceM(a.lat, a.lon, b.lat, b.lon) }

    /** Age of a fix in ms (elapsed-realtime based, immune to clock changes). */
    private fun fixAgeMs(f: Fix): Long = (SystemClock.elapsedRealtimeNanos() - f.elapsedNs) / 1_000_000

    /** Fetches a new fix when the cached one is older than [LOCATION_MAX_AGE_MS]. Never throws. */
    @SuppressLint("MissingPermission") // Location permission is checked below; soft() catches SecurityException.
    suspend fun refreshLocationIfStale() = refreshLock.withLock {
        val current = fix
        if (current != null && fixAgeMs(current) < LOCATION_MAX_AGE_MS) return@withLock
        // Without a fix (indoors, no sky) do not keep the GPS busy: one attempt per max-age period.
        val now = nowElapsedMs()
        val lastAttempt = lastAttemptMs
        if (lastAttempt != null && now - lastAttempt < LOCATION_MAX_AGE_MS) return@withLock
        lastAttemptMs = now
        val lm = locationManager ?: return@withLock
        if (!app.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !app.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            failures.fail("location_permission", "Location permission missing")
            return@withLock
        }
        failures.ok("location_permission")
        val enabled = failures.soft("location_enabled") { lm.isLocationEnabled } ?: true
        if (!enabled) {
            failures.fail("location_off", "Location is switched off on the phone; no position")
            return@withLock
        }
        failures.ok("location_off")
        val providers = PROVIDERS.filter { p -> failures.soft("provider_$p") { lm.isProviderEnabled(p) } == true }
        val candidates = ArrayList<Location>()
        providers.forEach { p -> failures.soft("last_known_$p") { lm.getLastKnownLocation(p) }?.let(candidates::add) }
        val fresh = coroutineScope {
            providers.map { p -> async { currentLocation(lm, p) } }.mapNotNull { it.await() }
        }
        candidates += fresh
        val best = pickBest(candidates) ?: return@withLock
        fix = Fix(best.latitude, best.longitude, if (best.hasAccuracy()) best.accuracy.toDouble() else null, best.time, best.elapsedRealtimeNanos)
    }

    /** Best of several fixes: the newest within 2 minutes of the newest, then the most accurate. */
    private fun pickBest(locations: List<Location>): Location? {
        if (locations.isEmpty()) return null
        val newest = locations.maxOf { it.elapsedRealtimeNanos }
        return locations
            .filter { newest - it.elapsedRealtimeNanos <= 120_000_000_000L }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    }

    @SuppressLint("MissingPermission") // Only called from refreshLocationIfStale, after the permission check.
    private suspend fun currentLocation(lm: LocationManager, provider: String): Location? {
        val timeout = if (provider == LocationManager.GPS_PROVIDER) GPS_TIMEOUT_MS else NETWORK_TIMEOUT_MS
        return failures.soft("current_location_$provider") {
            withTimeoutOrNull(timeout) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        lm.getCurrentLocation(provider, signal, DirectExecutor) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                } else {
                    singleUpdate(lm, provider)
                }
            }
        }
    }

    /** API 29: requestSingleUpdate on the main looper; every LocationListener method is implemented (no defaults before API 30). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // Only called from refreshLocationIfStale, after the permission check.
    private suspend fun singleUpdate(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (cont.isActive) cont.resume(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation {
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
                // Already removed.
            }
        }
        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    }

    /** Averages accelerometer (and magnetometer) readings for [SAMPLE_MS]. Null without an accelerometer. */
    suspend fun sampleOrientation(): OrientationSample? {
        val sm = sensorManager ?: return null
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            failures.fail("accelerometer", "No accelerometer; tilt is not monitored")
            return null
        }
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = SensorAverager()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                when (e.sensor?.type) {
                    Sensor.TYPE_ACCELEROMETER -> acc.addAccel(e.values)
                    Sensor.TYPE_MAGNETIC_FIELD -> acc.addMagnet(e.values)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            if (magnet != null) sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_GAME)
            delay(SAMPLE_MS)
        } finally {
            sm.unregisterListener(listener)
        }
        return acc.result()
    }

    /**
     * Builds the position group from the cached fix and a fresh orientation sample, updates the
     * reference and returns the group plus a moved event when one fires.
     */
    fun evaluate(orientation: OrientationSample?, watchdog: WatchdogSettings): Pair<PositionInfo, MovedEvent?> = synchronized(evalLock) {
        val f = fix
        val now = nowElapsedMs()

        // Reference orientation / location: adopt the first reading that stayed stable long enough.
        var refGravity = Vec3.decode(prefs.getString(KEY_REF_GRAVITY))
        if (refGravity == null && orientation != null && orientation.steady) {
            gravityGate.offer(orientation.gravity, now)?.let {
                refGravity = it
                prefs.putString(KEY_REF_GRAVITY, it.encode())
            }
        }
        var refLoc = loadRefLocation()
        if (refLoc == null && f != null && (f.accM ?: Double.MAX_VALUE) <= REF_MAX_ACC_M) {
            locationGate.offer(f, now)?.let {
                refLoc = RefLocation(it.lat, it.lon, it.accM)
                saveRefLocation(it.lat, it.lon, it.accM)
            }
        }

        val tilt = refGravity?.let { r -> orientation?.let { PositionMath.angleDeg(r, it.gravity) } }?.let(::round1)
        val moved = refLoc?.let { r -> f?.let { PositionMath.distanceM(r.lat, r.lon, it.lat, it.lon) } }?.let(::round1)
        val accSum = if (refLoc != null && f != null) (refLoc?.accM ?: 0.0) + (f.accM ?: 0.0) else null

        val wasAlerted = moveDetector.alerted
        val fire = moveDetector.evaluate(tilt, moved, accSum, watchdog.moveTiltDeg, watchdog.moveDistanceM)
        if (moveDetector.alerted != wasAlerted) prefs.putBoolean(KEY_ALERTED, moveDetector.alerted)

        val info = PositionInfo(
            lat = f?.lat,
            lon = f?.lon,
            accM = f?.accM?.let(::round1),
            fixAt = f?.let { Clock.formatIso(it.timeMs) },
            pitchDeg = orientation?.pitchDeg?.let(::round1),
            rollDeg = orientation?.rollDeg?.let(::round1),
            azimuthDeg = orientation?.azimuthDeg?.let(::round1),
            tiltChangeDeg = tilt,
            movedM = moved,
        )
        info to if (fire) MovedEvent(tilt, moved, f?.lat, f?.lon) else null
    }

    /** Forgets the reference; the next stable reading becomes the new one. */
    fun resetReference() = synchronized(evalLock) {
        prefs.remove(KEY_REF_GRAVITY, KEY_REF_LAT, KEY_REF_LON, KEY_REF_ACC, KEY_ALERTED)
        moveDetector.reset()
        gravityGate.reset()
        locationGate.reset()
    }

    /** The Finder baseline changed (plan 7.3): re-reference, using the spot's own fix when it is accurate. */
    fun onBaselineSpot(spot: FinderSpot?) {
        spot ?: return
        val known = prefs.getString(KEY_BASELINE_ID)
        if (known == spot.id) return
        val hasReference = prefs.contains(KEY_REF_GRAVITY) || prefs.contains(KEY_REF_LAT)
        if (known != null || hasReference) resetReference()
        val lat = spot.lat
        val lon = spot.lon
        if (lat != null && lon != null && (spot.accM ?: Double.MAX_VALUE) <= REF_MAX_ACC_M) {
            synchronized(evalLock) { saveRefLocation(lat, lon, spot.accM) }
        }
        prefs.putString(KEY_BASELINE_ID, spot.id)
    }

    private data class RefLocation(val lat: Double, val lon: Double, val accM: Double?)

    private fun loadRefLocation(): RefLocation? {
        val lat = prefs.getString(KEY_REF_LAT)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_REF_LON)?.toDoubleOrNull() ?: return null
        return RefLocation(lat, lon, prefs.getString(KEY_REF_ACC)?.toDoubleOrNull())
    }

    private fun saveRefLocation(lat: Double, lon: Double, accM: Double?) {
        prefs.putString(KEY_REF_LAT, lat.toString())
        prefs.putString(KEY_REF_LON, lon.toString())
        prefs.putString(KEY_REF_ACC, accM?.toString())
    }

    /** Collects sensor values from the main thread and averages them. */
    private class SensorAverager {
        private var ax = 0.0
        private var ay = 0.0
        private var az = 0.0
        private var aCount = 0
        private var magSum = 0.0
        private var magSqSum = 0.0
        private var mx = 0.0
        private var my = 0.0
        private var mz = 0.0
        private var mCount = 0

        @Synchronized
        fun addAccel(v: FloatArray) {
            if (v.size < 3) return
            ax += v[0]
            ay += v[1]
            az += v[2]
            val m = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()).norm
            magSum += m
            magSqSum += m * m
            aCount++
        }

        @Synchronized
        fun addMagnet(v: FloatArray) {
            if (v.size < 3) return
            mx += v[0]
            my += v[1]
            mz += v[2]
            mCount++
        }

        @Synchronized
        fun result(): OrientationSample? {
            if (aCount < MIN_SAMPLES) return null
            val g = Vec3(ax / aCount, ay / aCount, az / aCount)
            val mean = magSum / aCount
            val variance = (magSqSum / aCount - mean * mean).coerceAtLeast(0.0)
            val steady = kotlin.math.sqrt(variance) < STEADY_STD_MS2
            val (pitch, roll) = PositionMath.pitchRollDeg(g) ?: (null to null)
            val azimuth = if (mCount >= MIN_SAMPLES) {
                val r = FloatArray(9)
                val gravity = floatArrayOf(g.x.toFloat(), g.y.toFloat(), g.z.toFloat())
                val geo = floatArrayOf((mx / mCount).toFloat(), (my / mCount).toFloat(), (mz / mCount).toFloat())
                if (SensorManager.getRotationMatrix(r, null, gravity, geo)) {
                    val values = FloatArray(3)
                    SensorManager.getOrientation(r, values)
                    PositionMath.azimuthDeg(values[0])
                } else {
                    null
                }
            } else {
                null
            }
            return OrientationSample(g, pitch, roll, azimuth, steady)
        }
    }

    companion object {
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val LOCATION_MAX_AGE_MS = 10 * 60_000L
        private const val GPS_TIMEOUT_MS = 60_000L
        private const val NETWORK_TIMEOUT_MS = 20_000L
        private const val SAMPLE_MS = 1_500L
        private const val MIN_SAMPLES = 5
        private const val STEADY_STD_MS2 = 0.6
        private const val STABLE_HOLD_MS = 10 * 60_000L
        private const val STABLE_TILT_DEG = 3.0
        private const val STABLE_DISTANCE_M = 30.0
        private const val REF_MAX_ACC_M = 100.0

        private const val KEY_REF_GRAVITY = "telemetry.ref.gravity"
        private const val KEY_REF_LAT = "telemetry.ref.lat"
        private const val KEY_REF_LON = "telemetry.ref.lon"
        private const val KEY_REF_ACC = "telemetry.ref.acc"
        private const val KEY_ALERTED = "telemetry.ref.moved_alerted"
        private const val KEY_BASELINE_ID = "telemetry.ref.baseline_spot"
    }
}
