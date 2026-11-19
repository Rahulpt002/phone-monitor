package com.hotspotmonitor.agent.finder

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Phone attitude from an averaged accelerometer vector (gravity, device axes: x right, y top,
 * z out of the screen; a still phone reads +g on the axis pointing up) and the magnetic field.
 * Pure math, unit-tested in OrientationMathTest.
 *
 * Conventions (match the shared fixture: an upright phone has pitch ~90):
 * - pitch_deg: elevation of the top edge above the horizon (flat 0, upright +90, upside down -90).
 * - roll_deg: elevation of the right edge above the horizon (flat or upright 0, landscape +-90).
 * - compass: horizontal direction the back of the phone faces when it stands up (upright or
 *   landscape), or the direction its top points when it lies flat; degrees from magnetic north.
 */
object OrientationMath {
    const val UPRIGHT = "upright"
    const val FLAT = "flat"
    const val ROT90 = "rot90"

    /** Normalized gravity component above which the phone counts as lying flat (~41 degrees). */
    private const val FLAT_THRESHOLD = 0.75

    data class Attitude(val pitchDeg: Double, val rollDeg: Double, val orientation: String)

    fun attitude(ax: Double, ay: Double, az: Double): Attitude? {
        val g = sqrt(ax * ax + ay * ay + az * az)
        if (g < MIN_GRAVITY) return null
        val x = ax / g
        val y = ay / g
        val z = az / g
        return Attitude(
            pitchDeg = Math.toDegrees(asin(y.coerceIn(-1.0, 1.0))),
            rollDeg = Math.toDegrees(asin(x.coerceIn(-1.0, 1.0))),
            orientation = classify(x, y, z),
        )
    }

    /** "flat" when the screen faces up or down, else "upright" (portrait) or "rot90" (landscape). */
    fun classify(x: Double, y: Double, z: Double): String = when {
        abs(z) >= FLAT_THRESHOLD -> FLAT
        abs(y) >= abs(x) -> UPRIGHT
        else -> ROT90
    }

    /**
     * Heading in degrees [0, 360) from magnetic north, or null when the vectors are degenerate
     * (free fall, magnetic field parallel to gravity). Same construction as
     * SensorManager.getRotationMatrix: East = M x G, North = G x East.
     */
    fun headingDeg(gravity: DoubleArray, magnetic: DoubleArray): Double? {
        val (ax, ay, az) = Triple(gravity[0], gravity[1], gravity[2])
        val (ex, ey, ez) = Triple(magnetic[0], magnetic[1], magnetic[2])
        var hx = ey * az - ez * ay
        var hy = ez * ax - ex * az
        var hz = ex * ay - ey * ax
        val normH = sqrt(hx * hx + hy * hy + hz * hz)
        val g = sqrt(ax * ax + ay * ay + az * az)
        if (normH < MIN_FIELD || g < MIN_GRAVITY) return null
        hx /= normH
        hy /= normH
        hz /= normH
        val nax = ax / g
        val nay = ay / g
        val naz = az / g
        val mx = nay * hz - naz * hy
        val my = naz * hx - nax * hz
        val mz = nax * hy - nay * hx
        // Device vector whose horizontal direction we report: -z (the back) when standing, y when flat.
        val flat = abs(naz) >= FLAT_THRESHOLD
        val east = if (flat) hy else -hz
        val north = if (flat) my else -mz
        if (abs(east) < 1e-9 && abs(north) < 1e-9) return null
        return normalizeDeg(Math.toDegrees(atan2(east, north)))
    }

    fun normalizeDeg(deg: Double): Double {
        val d = deg % 360.0
        val positive = if (d < 0) d + 360.0 else d
        return if (positive >= 360.0) 0.0 else positive
    }

    private const val MIN_GRAVITY = 1.0
    private const val MIN_FIELD = 0.1
}
