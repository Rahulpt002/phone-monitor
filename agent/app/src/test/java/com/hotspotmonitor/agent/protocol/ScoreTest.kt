package com.hotspotmonitor.agent.protocol

import com.hotspotmonitor.agent.testutil.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreTest {
    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull

    @Test
    fun scoreCasesFromTheSharedFixtureMatch() {
        val cases = Fixtures.objects("score-cases.json")
        check(cases.isNotEmpty())
        for (case in cases) {
            val actual = signalScore(case.num("rsrp"), case.num("sinr"), case.num("rsrq"))
            val expected = case.num("score")
            if (expected == null) {
                assertNull("case $case", actual)
            } else {
                assertEquals("case $case", expected, actual!!, 1e-9)
            }
        }
    }

    @Test
    fun round1MatchesJavaScriptMathRound() {
        assertEquals(1.3, round1(1.25), 0.0)
        assertEquals(-1.2, round1(-1.25), 0.0) // Math.round(-12.5) = -12
        assertEquals(33.3, round1(33.3333333), 1e-12)
        assertEquals(0.0, round1(-0.04), 0.0)
    }

    @Test
    fun scoreUsesCustomWeightsAndIgnoresNaN() {
        val sinrOnly = ScoreWeights(wSinr = 1.0, wRsrp = 0.0, wRsrq = 0.0)
        assertEquals(50.0, signalScore(-200, 10.0, null, sinrOnly)!!, 0.0)
        assertNull(signalScore(Double.NaN, 10.0, -10))
        assertEquals(signalScore(-90, null, -12), signalScore(-90, Double.NaN, -12))
    }

    @Test
    fun nrStandaloneScoresTheNrLeg() {
        val sa = RadioInfo(netType = NetType.NR_SA, rsrp = -120, nr = NrLeg(ssRsrp = -80, ssSinr = 25.0, ssRsrq = -8))
        assertEquals(100.0, sa.rawScore()!!, 0.0)
        val nsa = sa.copy(netType = NetType.NR_NSA, sinr = -5.0, rsrq = -20)
        assertEquals(0.0, nsa.rawScore()!!, 0.0)
        assertNull(RadioInfo(netType = NetType.LTE).rawScore())
    }

    @Test
    fun gradesFollowPlanSevenOneBoundaries() {
        assertEquals(Grade.EXCELLENT, gradeRsrp(-79.0))
        assertEquals(Grade.GOOD, gradeRsrp(-80.0))
        assertEquals(Grade.GOOD, gradeRsrp(-90.0))
        assertEquals(Grade.FAIR, gradeRsrp(-100.0))
        assertEquals(Grade.POOR, gradeRsrp(-100.5))
        assertEquals(Grade.EXCELLENT, gradeSinr(20.5))
        assertEquals(Grade.GOOD, gradeSinr(13.0))
        assertEquals(Grade.FAIR, gradeSinr(0.0))
        assertEquals(Grade.POOR, gradeSinr(-0.1))
        assertEquals(Grade.EXCELLENT, gradeRsrq(-9.0))
        assertEquals(Grade.GOOD, gradeRsrq(-15.0))
        assertEquals(Grade.FAIR, gradeRsrq(-20.0))
        assertEquals(Grade.POOR, gradeRsrq(-21.0))
        assertEquals(Grade.EXCELLENT, gradeScore(75.0))
        assertEquals(Grade.GOOD, gradeScore(55.0))
        assertEquals(Grade.FAIR, gradeScore(35.0))
        assertEquals(Grade.POOR, gradeScore(34.9))
    }

    @Test
    fun smootherAveragesOverTheTimeWindow() {
        val s = Smoother(windowMs = 5_000)
        assertNull(s.mean(0))
        assertEquals(10.0, s.add(10.0, 0)!!, 0.0)
        assertEquals(15.0, s.add(20.0, 1_000)!!, 0.0)
        assertEquals(20.0, s.add(30.0, 4_000)!!, 0.0)
        // At t = 5500 the sample from t = 0 is out of the 5 s window.
        assertEquals(25.0, s.mean(5_500)!!, 0.0)
        assertEquals(2, s.size)
        assertEquals(30.0, s.add(Double.NaN, 6_000)!!, 0.0)
        assertNull(s.mean(20_000))
        s.add(1.0, 20_000)
        s.clear()
        assertEquals(0, s.size)
    }

    @Test
    fun rankingPutsMeasuredSpeedFirst() {
        fun stats(avg: Double) = MetricStats(avg, avg, avg, 0.0, 1)
        fun spot(id: String, score: Double, down: Double?) = FinderSpot(
            id = id, name = id, createdAt = "2026-11-19T09:00:00.000Z", durationS = 30,
            serving = ServingCell(NetType.LTE), cellSwitches = 0,
            metrics = SpotMetrics(score = stats(score), rsrp = stats(-90.0)),
            speed = if (down == null) emptyList() else listOf(SpeedSession("2026-11-19T09:05:00.000Z", false, emptyList(), downMbps = down)),
        )
        val ranked = rankSpots(listOf(spot("a", 90.0, null), spot("b", 40.0, 20.0), spot("c", 50.0, 30.0), spot("d", 95.0, null)))
        assertEquals(listOf("c", "b", "d", "a"), ranked.map { it.id })
    }
}
