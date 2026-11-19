package com.hotspotmonitor.agent.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationMathTest {
    private val g = 9.81

    // Earth field in world axes (east, north, up) for a northern-hemisphere site: 30 uT north, 35 uT down.
    private val north = 30.0
    private val down = 35.0

    @Test
    fun attitudeOfTheThreeSurveyOrientations() {
        val upright = OrientationMath.attitude(0.0, g, 0.0)!!
        assertEquals(OrientationMath.UPRIGHT, upright.orientation)
        assertEquals(90.0, upright.pitchDeg, 1e-9)
        assertEquals(0.0, upright.rollDeg, 1e-9)

        val flat = OrientationMath.attitude(0.0, 0.0, g)!!
        assertEquals(OrientationMath.FLAT, flat.orientation)
        assertEquals(0.0, flat.pitchDeg, 1e-9)
        assertEquals(0.0, flat.rollDeg, 1e-9)

        val landscape = OrientationMath.attitude(g, 0.0, 0.0)!!
        assertEquals(OrientationMath.ROT90, landscape.orientation)
        assertEquals(90.0, landscape.rollDeg, 1e-9)

        // Face down counts as flat; slightly tilted upright stays upright.
        assertEquals(OrientationMath.FLAT, OrientationMath.attitude(0.0, 0.0, -g)!!.orientation)
        assertEquals(OrientationMath.UPRIGHT, OrientationMath.attitude(1.0, 9.0, 3.0)!!.orientation)
        assertNull(OrientationMath.attitude(0.0, 0.0, 0.1))
    }

    @Test
    fun uprightPhoneReportsTheDirectionItsBackFaces() {
        // Upright, back facing north: device x = east, y = up, z = south.
        val gravity = doubleArrayOf(0.0, g, 0.0)
        val field = doubleArrayOf(0.0, -down, -north)
        assertEquals(0.0, OrientationMath.headingDeg(gravity, field)!!, 1e-6)
        // Upright, back facing east: device x = south, y = up, z = west.
        assertEquals(90.0, OrientationMath.headingDeg(gravity, doubleArrayOf(-north, -down, 0.0))!!, 1e-6)
        // Back facing west: x = north, z = east.
        assertEquals(270.0, OrientationMath.headingDeg(gravity, doubleArrayOf(north, -down, 0.0))!!, 1e-6)
        // Back facing south: x = west, z = north.
        assertEquals(180.0, OrientationMath.headingDeg(gravity, doubleArrayOf(0.0, -down, north))!!, 1e-6)
    }

    @Test
    fun flatPhoneReportsTheDirectionItsTopPoints() {
        // Flat face up, top pointing north: x = east, y = north, z = up.
        val gravity = doubleArrayOf(0.0, 0.0, g)
        assertEquals(0.0, OrientationMath.headingDeg(gravity, doubleArrayOf(0.0, north, -down))!!, 1e-6)
        // Top pointing east: x = south, y = east.
        assertEquals(90.0, OrientationMath.headingDeg(gravity, doubleArrayOf(-north, 0.0, -down))!!, 1e-6)
    }

    @Test
    fun degenerateFieldsGiveNoHeading() {
        assertNull(OrientationMath.headingDeg(doubleArrayOf(0.0, g, 0.0), doubleArrayOf(0.0, 50.0, 0.0)))
        assertNull(OrientationMath.headingDeg(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0, 30.0, -35.0)))
    }

    @Test
    fun normalizesDegrees() {
        assertEquals(350.0, OrientationMath.normalizeDeg(-10.0), 1e-9)
        assertEquals(0.0, OrientationMath.normalizeDeg(360.0), 1e-9)
        assertEquals(45.0, OrientationMath.normalizeDeg(765.0), 1e-9)
    }
}
