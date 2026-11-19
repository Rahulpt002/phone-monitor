package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.BatteryHealth
import com.hotspotmonitor.agent.protocol.BatteryStatus
import com.hotspotmonitor.agent.protocol.IfCounters
import com.hotspotmonitor.agent.protocol.PlugType
import com.hotspotmonitor.agent.root.CurrentNowSpec
import com.hotspotmonitor.agent.root.DeviceProfile
import com.hotspotmonitor.agent.root.ThermalSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TelemetryMathTest {
    // --- connectivity -----------------------------------------------------------------------

    @Test
    fun connectivityDownNeedsTwoFailuresAndUpReportsDuration() {
        val t = ConnectivityTracker(confirmDownChecks = 2)
        assertNull(t.onCheck(ok = true, nowMs = 0)) // first state is not an event
        assertNull(t.onCheck(ok = false, nowMs = 15_000))
        assertEquals(15_000L, t.downSinceMs)
        assertEquals(ConnectivityTracker.Transition(false, null), t.onCheck(ok = false, nowMs = 30_000))
        assertNull(t.onCheck(ok = false, nowMs = 45_000)) // reported once
        assertEquals(ConnectivityTracker.Transition(true, 75L), t.onCheck(ok = true, nowMs = 90_000))
        assertNull(t.downSinceMs)
        // A single failed check is not an outage and the recovery is silent.
        assertNull(t.onCheck(ok = false, nowMs = 100_000))
        assertNull(t.onCheck(ok = true, nowMs = 115_000))
    }

    @Test
    fun connectivityReportsDownAtStartup() {
        val t = ConnectivityTracker()
        assertNull(t.onCheck(false, 0))
        assertEquals(false, t.onCheck(false, 15_000)!!.online)
    }

    // --- traffic ----------------------------------------------------------------------------

    @Test
    fun cycleStartComputation() {
        assertEquals(LocalDate.of(2026, 11, 1), cycleStart(LocalDate.of(2026, 11, 19), 1))
        assertEquals(LocalDate.of(2026, 11, 15), cycleStart(LocalDate.of(2026, 11, 19), 15))
        assertEquals(LocalDate.of(2026, 10, 20), cycleStart(LocalDate.of(2026, 11, 19), 20))
        assertEquals(LocalDate.of(2026, 11, 19), cycleStart(LocalDate.of(2026, 11, 19), 19))
        assertEquals(LocalDate.of(2025, 12, 5), cycleStart(LocalDate.of(2026, 1, 3), 5))
        // Clamped to 1..28 so every month has the day.
        assertEquals(LocalDate.of(2026, 2, 28), cycleStart(LocalDate.of(2026, 3, 10), 31))
        assertEquals(LocalDate.of(2026, 3, 1), cycleStart(LocalDate.of(2026, 3, 10), 0))
    }

    @Test
    fun accumulatorBaselineThenDeltas() {
        val day = LocalDate.of(2026, 11, 19)
        val cycle = LocalDate.of(2026, 11, 1)
        val s0 = TrafficAccumulator.update(null, "count:5", day, cycle, mapOf("rmnet_data2" to IfCounters(1_000, 500)))
        assertEquals(0L, s0.dayRx)
        assertEquals(0L, s0.cycleTx)
        val s1 = TrafficAccumulator.update(s0, "count:5", day, cycle, mapOf("rmnet_data2" to IfCounters(1_600, 700)))
        assertEquals(600L, s1.dayRx)
        assertEquals(200L, s1.dayTx)
        assertEquals(600L, s1.cycleRx)
        // A new interface counts from zero; a re-created one (counter went down) too.
        val s2 = TrafficAccumulator.update(
            s1, "count:5", day, cycle,
            mapOf("rmnet_data2" to IfCounters(100, 50), "rmnet_data3" to IfCounters(40, 10)),
        )
        assertEquals(600L + 100 + 40, s2.dayRx)
        assertEquals(200L + 50 + 10, s2.dayTx)
    }

    @Test
    fun accumulatorHandlesRebootDayAndCycleRollover() {
        val cycle = LocalDate.of(2026, 11, 1)
        val s0 = TrafficAccState("count:5", mapOf("rmnet_data2" to IfCounters(9_000, 9_000)), "2026-11-19", 100, 100, "2026-11-01", 1_000, 1_000)
        // After a reboot the counters restart; even a value above the old one is all new bytes.
        val rebooted = TrafficAccumulator.update(s0, "count:6", LocalDate.of(2026, 11, 19), cycle, mapOf("rmnet_data2" to IfCounters(10_000, 20)))
        assertEquals(100L + 10_000, rebooted.dayRx)
        assertEquals(1_000L + 20, rebooted.cycleTx)
        // Next day: day totals restart, cycle continues.
        val nextDay = TrafficAccumulator.update(rebooted, "count:6", LocalDate.of(2026, 11, 20), cycle, mapOf("rmnet_data2" to IfCounters(10_500, 120)))
        assertEquals("2026-11-20", nextDay.day)
        assertEquals(500L, nextDay.dayRx)
        assertEquals(1_000L + 10_000 + 500, nextDay.cycleRx)
        // New cycle: cycle totals restart.
        val newCycle = TrafficAccumulator.update(nextDay, "count:6", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), mapOf("rmnet_data2" to IfCounters(10_600, 120)))
        assertEquals(100L, newCycle.cycleRx)
        assertEquals("2026-12-01", newCycle.cycleStart)
    }

    @Test
    fun mobileInterfaceNames() {
        assertTrue(isMobileInterfaceName("rmnet_data0"))
        assertTrue(isMobileInterfaceName("ccmni1"))
        assertFalse(isMobileInterfaceName("rmnet_ipa0"))
        assertFalse(isMobileInterfaceName("v4-rmnet_data0"))
        assertFalse(isMobileInterfaceName("wlan0"))
        assertFalse(isMobileInterfaceName("rndis0"))
    }

    // --- power ------------------------------------------------------------------------------

    @Test
    fun currentConversionUsesProfileUnitAndSign() {
        assertEquals(-850, PowerMath.currentMa(-850_000, CurrentNowSpec(unit = "uA", sign = 1)))
        assertEquals(850, PowerMath.currentMa(-850_000, CurrentNowSpec(unit = "uA", sign = -1)))
        assertEquals(1200, PowerMath.currentMa(1200, CurrentNowSpec(unit = "mA", sign = 1)))
        assertEquals(-1200, PowerMath.currentMa(1200, CurrentNowSpec(unit = "mA", sign = -1)))
        assertNull(PowerMath.currentMa(Int.MIN_VALUE.toLong(), CurrentNowSpec()))
        assertNull(PowerMath.currentMa(Long.MIN_VALUE, CurrentNowSpec()))
        assertNull(PowerMath.currentMa(null, CurrentNowSpec()))
        // A unit mistake (uA read as mA) is implausible and dropped.
        assertNull(PowerMath.currentMa(900_000, CurrentNowSpec(unit = "mA")))
    }

    @Test
    fun batteryFieldConversions() {
        assertEquals(57, PowerMath.percent(57, 100))
        assertEquals(50, PowerMath.percent(128, 256))
        assertNull(PowerMath.percent(-1, 100))
        assertEquals(4123, PowerMath.voltageMv(4123))
        assertEquals(4000, PowerMath.voltageMv(4))
        assertEquals(4123, PowerMath.voltageMv(4_123_000))
        assertNull(PowerMath.voltageMv(0))
        assertEquals(36.5, PowerMath.batteryTempC(365)!!, 0.0)
        assertNull(PowerMath.batteryTempC(Int.MIN_VALUE))
        assertEquals(BatteryStatus.CHARGING, PowerMath.status(2))
        assertEquals(BatteryStatus.UNKNOWN, PowerMath.status(99))
        assertEquals(BatteryHealth.OVERHEAT, PowerMath.health(3))
        assertEquals(PlugType.USB, PowerMath.plug(2))
        assertEquals(PlugType.AC, PowerMath.plug(1))
        assertEquals(PlugType.WIRELESS, PowerMath.plug(4))
        assertEquals(PlugType.DOCK, PowerMath.plug(8))
        assertEquals(PlugType.NONE, PowerMath.plug(0))
    }

    // --- heat -------------------------------------------------------------------------------

    @Test
    fun thermalUnitsAreNormalised() {
        assertEquals(45.3, ThermalMath.celsius(45_300)!!, 0.0)
        assertEquals(35.0, ThermalMath.celsius(350)!!, 0.0)
        assertEquals(41.0, ThermalMath.celsius(41)!!, 0.0)
        assertNull(ThermalMath.celsius(-273_000))
        assertNull(ThermalMath.celsius(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun thermalZonesMapToGroupsByKeyword() {
        val zones = ThermalMath.parseZones(
            """
            cpu-0-0-usr|45300
            cpu-1-2-usr|51200
            modem-0-usr|39000
            pa_therm0|41500
            xo_therm|36000
            battery|33000
            gpu-usr|47000
            |40000
            broken|abc
            disabled_sensor|-273000
            """.trimIndent(),
        )
        assertEquals(7, zones.size)
        val s = ThermalMath.summarize(zones, ThermalSpec())
        assertEquals(51.2, s.cpu!!, 0.0)
        assertEquals(41.5, s.modem!!, 0.0)
        assertEquals(36.0, s.skin!!, 0.0)
        assertEquals(33.0, s.battery!!, 0.0)
        assertEquals(7, s.zones.size)
        assertEquals(47.0, s.zones["gpu-usr"]!!, 0.0)

        val custom = ThermalMath.summarize(zones, ThermalSpec(cpu = listOf("gpu"), modem = emptyList(), skin = listOf("nothing")))
        assertEquals(47.0, custom.cpu!!, 0.0)
        assertNull(custom.modem)
        assertNull(custom.skin)
    }

    @Test
    fun thermalKeywordsMatchUpperCaseZoneTypes() {
        // Exynos / Tensor zone types are upper case; profile keywords are lower case.
        val zones = listOf(
            ThermalZone("BIG", 61.0),
            ThermalZone("MID", 55.0),
            ThermalZone("LITTLE", 48.0),
            ThermalZone("G3D", 50.0),
            ThermalZone("Battery", 34.5),
            ThermalZone("SKIN_THERM", 38.0),
        )
        val s = ThermalMath.summarize(zones, ThermalSpec(cpu = listOf("big", "mid", "little"), modem = emptyList(), skin = listOf("skin"), battery = listOf("battery")))
        assertEquals(61.0, s.cpu!!, 0.0)
        assertEquals(38.0, s.skin!!, 0.0)
        assertEquals(34.5, s.battery!!, 0.0)
        // Keywords in upper case work too.
        assertEquals(48.0, ThermalMath.summarize(zones, ThermalSpec(cpu = listOf("LITTLE"))).cpu!!, 0.0)
        // The built-in defaults cover them as well.
        assertEquals(61.0, ThermalMath.summarize(zones, ThermalSpec()).cpu!!, 0.0)
    }

    @Test
    fun qualcommSocChargeLevelZoneIsNotACpuTemperature() {
        // On Qualcomm the "soc" zone is the battery current limiter's state of charge (here 95 %),
        // which would read as 95 °C. Neither the defaults nor the built-in profile may match it.
        val zones = listOf(ThermalZone("soc", 95.0), ThermalZone("cpu-0-0-usr", 41.0), ThermalZone("tsens_tz_sensor1", 39.0))
        assertFalse(ThermalSpec().cpu.any { it.equals("soc", ignoreCase = true) })
        assertEquals(41.0, ThermalMath.summarize(zones, ThermalSpec()).cpu!!, 0.0)
        assertEquals(41.0, ThermalMath.summarize(zones, DeviceProfile.GENERIC.thermal).cpu!!, 0.0)
    }

    @Test
    fun thermalZoneMapIsTrimmedAndKeysStayUnique() {
        val many = (1..50).map { ThermalZone("tsens_tz_sensor", 30.0 + it * 0.1) }
        val s = ThermalMath.summarize(many, ThermalSpec(), maxZones = 30)
        assertEquals(30, s.zones.size)
        assertTrue(s.zones.containsKey("tsens_tz_sensor"))
        assertTrue(s.zones.containsKey("tsens_tz_sensor#2"))
        assertEquals(35.0, s.cpu!!, 1e-9)
    }

    // --- position ---------------------------------------------------------------------------

    @Test
    fun distanceBetweenCoordinates() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111_195.0, PositionMath.distanceM(10.0, 76.0, 11.0, 76.0), 100.0)
        assertEquals(0.0, PositionMath.distanceM(10.0, 76.0, 10.0, 76.0), 1e-9)
        // About 100 m east at latitude 10.
        val d = PositionMath.distanceM(10.0, 76.0, 10.0, 76.0 + 100.0 / (111_320.0 * Math.cos(Math.toRadians(10.0))))
        assertEquals(100.0, d, 0.5)
    }

    @Test
    fun tiltAndOrientationMath() {
        val flat = Vec3(0.0, 0.0, 9.81)
        val upright = Vec3(0.0, 9.81, 0.0)
        assertEquals(90.0, PositionMath.angleDeg(flat, upright)!!, 1e-9)
        assertEquals(0.0, PositionMath.angleDeg(flat, Vec3(0.0, 0.0, 20.0))!!, 1e-6)
        val tilted = Vec3(0.0, 9.81 * Math.sin(Math.toRadians(20.0)), 9.81 * Math.cos(Math.toRadians(20.0)))
        assertEquals(20.0, PositionMath.angleDeg(flat, tilted)!!, 1e-9)
        assertNull(PositionMath.angleDeg(flat, Vec3(0.0, 0.0, 0.0)))

        val (pitchFlat, rollFlat) = PositionMath.pitchRollDeg(flat)!!
        assertEquals(0.0, pitchFlat, 1e-9)
        assertEquals(0.0, rollFlat, 1e-9)
        assertEquals(-90.0, PositionMath.pitchRollDeg(upright)!!.first, 1e-9)
        assertEquals(-90.0, PositionMath.pitchRollDeg(Vec3(9.81, 0.0, 0.0))!!.second, 1e-9)
        assertEquals(270.0, PositionMath.azimuthDeg((-Math.PI / 2).toFloat()), 1e-4)
        assertEquals(Vec3(1.0, -2.5, 3.0), Vec3.decode(Vec3(1.0, -2.5, 3.0).encode()))
        assertNull(Vec3.decode("1,2"))
    }

    @Test
    fun movedFiresOnceAfterTwoReadingsAndRearms() {
        val m = MoveDetector(confirmReadings = 2)
        assertFalse(m.evaluate(3.0, 10.0, 20.0, 15.0, 100.0))
        assertFalse(m.evaluate(20.0, 10.0, 20.0, 15.0, 100.0)) // first tilted reading
        assertTrue(m.evaluate(21.0, 10.0, 20.0, 15.0, 100.0)) // confirmed -> fire
        assertFalse(m.evaluate(22.0, 10.0, 20.0, 15.0, 100.0)) // same displacement: once only
        assertFalse(m.evaluate(10.0, 10.0, 20.0, 15.0, 100.0)) // below threshold but not back home
        assertTrue(m.alerted)
        assertFalse(m.evaluate(2.0, 10.0, 20.0, 15.0, 100.0)) // back near the reference: re-armed
        assertFalse(m.alerted)
        assertFalse(m.evaluate(30.0, null, null, 15.0, 100.0))
        assertTrue(m.evaluate(30.0, null, null, 15.0, 100.0))
    }

    @Test
    fun movedDistanceMustExceedFixAccuracy() {
        val m = MoveDetector(confirmReadings = 1)
        // 800 m away but the network fix is only good to 1500 m: no alarm.
        assertFalse(m.evaluate(null, 800.0, 1_510.0, 15.0, 100.0))
        assertTrue(m.evaluate(null, 800.0, 25.0, 15.0, 100.0))
        m.reset()
        assertFalse(m.alerted)
    }

    @Test
    fun referenceIsAdoptedOnlyAfterStablePeriod() {
        val gate = StabilityGate<Double>(holdMs = 600_000, tolerance = 3.0) { a, b -> kotlin.math.abs(a - b) }
        assertNull(gate.offer(10.0, 0))
        assertNull(gate.offer(11.0, 300_000))
        assertNull(gate.offer(20.0, 400_000)) // moved: the hold restarts from here
        assertNull(gate.offer(21.0, 900_000))
        assertEquals(20.0, gate.offer(19.5, 1_000_000)!!, 0.0)
    }
}
