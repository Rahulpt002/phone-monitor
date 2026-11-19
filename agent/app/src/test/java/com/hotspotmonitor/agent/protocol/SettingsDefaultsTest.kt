package com.hotspotmonitor.agent.protocol

import com.hotspotmonitor.agent.testutil.Fixtures
import com.hotspotmonitor.agent.testutil.canonical
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings.DEFAULT must equal shared/fixtures/settings-default.json (docs/protocol.md 3.4). */
class SettingsDefaultsTest {
    @Test
    fun defaultsEncodeToTheSharedFixture() {
        val encoded = AgentJson.encodeToJsonElement(Settings.serializer(), Settings.DEFAULT)
        assertEquals(canonical(Fixtures.element("settings-default.json")), canonical(encoded))
    }

    @Test
    fun sharedFixtureDecodesToTheDefaults() {
        assertEquals(Settings.DEFAULT, AgentJson.decodeFromString(Settings.serializer(), Fixtures.text("settings-default.json")))
    }

    @Test
    fun alertKindsMatchTheFixtureOrder() {
        val enabled = Fixtures.element("settings-default.json").jsonObject
            .getValue("alerts").jsonObject.getValue("enabled").jsonObject
        assertEquals(enabled.keys.toList(), ALERT_KINDS)
    }

    @Test
    fun emptyObjectDecodesToTheDefaults() {
        assertEquals(Settings.DEFAULT, AgentJson.decodeFromString(Settings.serializer(), "{}"))
    }

    @Test
    fun finderWeightsComeFromSettings() {
        assertEquals(ScoreWeights.DEFAULT, Settings.DEFAULT.finder.weights)
    }
}
