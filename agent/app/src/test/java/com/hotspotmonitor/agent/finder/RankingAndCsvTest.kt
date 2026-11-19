package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.FinderSpot
import com.hotspotmonitor.agent.protocol.FinderSpotMsg
import com.hotspotmonitor.agent.protocol.MetricStats
import com.hotspotmonitor.agent.protocol.NetType
import com.hotspotmonitor.agent.protocol.ServingCell
import com.hotspotmonitor.agent.protocol.SpeedSession
import com.hotspotmonitor.agent.protocol.SpotMetrics
import com.hotspotmonitor.agent.protocol.Wire
import com.hotspotmonitor.agent.protocol.rankSpots
import com.hotspotmonitor.agent.testutil.Fixtures
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

class RankingAndCsvTest {
    private fun stats(avg: Double) = MetricStats(avg, avg - 2, avg + 2, 1.0, 30)

    private fun spot(
        id: String,
        score: Double,
        vararg downs: Double?,
        name: String = id,
        switches: Int = 0,
        peak: Boolean = false,
    ) = FinderSpot(
        id = id,
        name = name,
        createdAt = "2026-11-19T09:00:00.000Z",
        durationS = 30,
        serving = ServingCell(NetType.LTE, band = 40, pci = 211, cellId = 123456789L, earfcn = 39150, ta = 3),
        cellSwitches = switches,
        metrics = SpotMetrics(score = stats(score), rsrp = stats(-90.0)),
        speed = downs.map { SpeedSession("2026-11-19T09:05:00.000Z", peak, emptyList(), downMbps = it, upMbps = 8.0, pingMs = 40.0, jitterMs = 5.0) },
    )

    /** finder.ts rankSpots, transcribed literally (sort with this comparator). */
    private fun sharedComparator(a: FinderSpot, b: FinderSpot): Int {
        fun best(s: FinderSpot): Double? = s.speed.mapNotNull { it.downMbps }.maxOrNull()
        val sa = best(a)
        val sb = best(b)
        if (sa != null && sb != null && sa != sb) return sb.compareTo(sa)
        if (sa != null && sb == null) return -1
        if (sa == null && sb != null) return 1
        return b.metrics.score.avg.compareTo(a.metrics.score.avg)
    }

    @Test
    fun rankingMatchesTheSharedRule() {
        val spots = listOf(
            spot("a", 90.0),
            spot("b", 40.0, 20.0),
            spot("c", 50.0, 30.0, 12.0),
            spot("d", 95.0),
            spot("e", 70.0, 30.0),
            spot("f", 10.0, null),
            spot("g", 60.0, 5.0, null),
        )
        val ranked = rankSpots(spots).map { it.id }
        // Speed first (c and e tie on 30 -> higher score first), then spots without speed by score.
        assertEquals(listOf("e", "c", "b", "g", "d", "a", "f"), ranked)
        val random = Random(7)
        repeat(50) {
            val shuffled = spots.shuffled(random)
            val expected = shuffled.sortedWith(Comparator { a, b -> sharedComparator(a, b) }).map { it.id }
            assertEquals(expected, rankSpots(shuffled).map { it.id })
        }
    }

    @Test
    fun csvEscapingFollowsRfc4180() {
        assertEquals("plain", SpotCsv.escape("plain"))
        assertEquals("\"a,b\"", SpotCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SpotCsv.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", SpotCsv.escape("two\nlines"))
        assertEquals("\"cr\rhere\"", SpotCsv.escape("cr\rhere"))
        assertEquals("", SpotCsv.text(null))
        // Spreadsheet formula injection is neutralised in free text.
        assertEquals("'=HYPERLINK(1)", SpotCsv.text("=HYPERLINK(1)"))
        assertEquals("\"'=SUM(A1,B1)\"", SpotCsv.text("=SUM(A1,B1)"))
        assertEquals("'+1", SpotCsv.text("+1"))
        assertEquals("'@x", SpotCsv.text("@x"))
    }

    @Test
    fun csvHasOneRowPerSpotInRankOrder() {
        val spots = listOf(
            spot("s_1", 60.0, name = "Roof, NE \"corner\""),
            spot("s_2", 40.0, 25.0, 31.5, switches = 2),
            spot("s_3", 50.0, 18.0, peak = true),
        )
        val csv = SpotCsv.build(spots, ZoneId.of("Asia/Kolkata"))
        assertTrue(csv.endsWith("\r\n"))
        val lines = csv.removeSuffix("\r\n").split("\r\n")
        assertEquals(4, lines.size)
        assertEquals(SpotCsv.HEADER.joinToString(","), lines[0])
        val rows = lines.drop(1).map(::parseCsvLine)
        rows.forEach { assertEquals(SpotCsv.HEADER.size, it.size) }
        val col = SpotCsv.HEADER.withIndex().associate { (i, h) -> h to i }
        assertEquals(listOf("s_2", "s_3", "s_1"), rows.map { it[col.getValue("id")] })
        assertEquals(listOf("1", "2", "3"), rows.map { it[col.getValue("rank")] })

        val s2 = rows[0]
        assertEquals("31.5", s2[col.getValue("best_down_mbps")])
        assertEquals("2", s2[col.getValue("cell_switches")])
        assertEquals("yes", s2[col.getValue("unstable")])
        assertEquals("", s2[col.getValue("peak_hour_down_mbps")])
        assertEquals("2", s2[col.getValue("speed_sessions")])
        assertEquals("2026-11-19T14:30:00+05:30", s2[col.getValue("time")])
        assertEquals("LTE", s2[col.getValue("net_type")])
        assertEquals("123456789", s2[col.getValue("cell_id")])
        assertEquals("40", s2[col.getValue("score_avg")])

        val s3 = rows[1]
        assertEquals("18", s3[col.getValue("peak_hour_down_mbps")])
        assertEquals("no", s3[col.getValue("unstable")])

        val s1 = rows[2]
        assertEquals("Roof, NE \"corner\"", s1[col.getValue("name")])
        assertEquals("", s1[col.getValue("best_down_mbps")])
        assertEquals("", s1[col.getValue("sinr_avg")])
        assertEquals("no", s1[col.getValue("baseline")])
    }

    @Test
    fun csvRowOfTheSharedFixtureSpot() {
        val message = Fixtures.objects("agent-to-server.json").first { it["t"]?.jsonPrimitive?.content == "finder_spot" }
        val spot = (Wire.decodeAgentMessage(message.toString()) as FinderSpotMsg).spot
        val row = SpotCsv.row(1, spot, ZoneId.of("UTC"))
        val col = SpotCsv.HEADER.withIndex().associate { (i, h) -> h to i }
        assertEquals(SpotCsv.HEADER.size, row.size)
        assertEquals("Roof NE corner", row[col.getValue("name")])
        assertEquals("2026-11-19T09:00:00Z", row[col.getValue("time")])
        assertEquals("12.9716", row[col.getValue("lat")])
        assertEquals("77.5946", row[col.getValue("lon")])
        assertEquals("upright", row[col.getValue("orientation")])
        assertEquals("66.4", row[col.getValue("score_avg")])
        assertEquals("15.2", row[col.getValue("sinr_avg")])
        assertEquals("42.1", row[col.getValue("best_down_mbps")])
        assertEquals("8.2", row[col.getValue("best_up_mbps")])
        assertEquals("41", row[col.getValue("best_ping_ms")])
        assertEquals("no", row[col.getValue("unstable")])
        // The fixture decodes and re-encodes through AgentJson without losing the speed runs.
        val again = AgentJson.parseToJsonElement(AgentJson.encodeToString(FinderSpot.serializer(), spot)).jsonObject
        assertEquals(3, again["speed"]!!.jsonArray[0].jsonObject["runs"]!!.jsonArray.size)
    }

    /** Minimal RFC 4180 reader for one line (no embedded line breaks in these tests). */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quoted = false
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
