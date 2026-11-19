@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.hotspotmonitor.agent.root

import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.testutil.testLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepoTest {
    private val generic = ProfileFile(
        "generic.json",
        """{"id":"generic","name":"Generic","match":{},"usb_function":"rndis","notes":["fallback"]}""",
    )
    private val samsung = ProfileFile(
        "samsung_galaxy_s.json",
        """{
          "id":"samsung_galaxy_s","name":"Samsung Galaxy S10/S20/S21",
          "match":{"manufacturer":["samsung"],"model_regex":"^SM-G9[789]"},
          "charge_control":[{"path":"/sys/class/power_supply/battery/batt_slate_mode","on":"0","off":"1","note":"slate"}],
          "current_now":{"unit":"uA","sign":1},
          "usb_function":"rndis",
          "autoboot":{"method":"magisk_autoboot","note":"x"},
          "thermal":{"cpu":["cpu"],"modem":["modem"],"skin":["skin"],"battery":["battery"]},
          "commands":{"network_mode":["cmd phone set-allowed-network-types-for-users -s 0 {value}"]},
          "network_mode":{"lte_only":"lte"},
          "notes":["n"],
          "future_field":{"ignored":true}
        }""",
    )
    private val samsungAny = ProfileFile("samsung_any.json", """{"id":"samsung_any","match":{"manufacturer":["SAMSUNG"]}}""")
    private val broken = ProfileFile("broken.json", "{not json")
    private val badRegex = ProfileFile("bad_regex.json", """{"id":"bad","match":{"manufacturer":["samsung"],"model_regex":"(["}}""")

    private fun repo(
        files: List<ProfileFile>,
        manufacturer: String = "samsung",
        model: String = "SM-G991B",
        settings: MutableStateFlow<Settings> = MutableStateFlow(Settings.DEFAULT),
    ) = ProfileRepo({ files }, settings, testLog(), manufacturer, model)

    @Test
    fun noProfileFilesFallsBackToTheBuiltInGeneric() {
        assertEquals(DeviceProfile.GENERIC, repo(emptyList()).current)
        assertEquals(DeviceProfile.GENERIC, ProfileRepo({ error("no assets") }, MutableStateFlow(Settings.DEFAULT), testLog(), "x", "y").current)
    }

    @Test
    fun mostSpecificMatchingProfileWins() {
        val r = repo(listOf(generic, samsungAny, samsung, broken, badRegex))
        assertEquals("samsung_galaxy_s", r.current.id)
        assertEquals("magisk_autoboot", r.current.autoboot.method)
        assertEquals("lte", r.current.networkModeValue(NetworkMode.LTE_ONLY))
        assertEquals(1, r.current.chargeControl.size)
        // broken.json is skipped; bad_regex.json loads but never matches.
        assertEquals(listOf("generic", "samsung_any", "samsung_galaxy_s", "bad"), r.available.map { it.id })

        assertEquals("samsung_any", repo(listOf(generic, samsungAny, samsung), model = "SM-A525F").current.id)
        assertEquals("generic", repo(listOf(generic, samsung), manufacturer = "Google", model = "Pixel 7").current.id)
    }

    @Test
    fun profileOverrideIsDeepMergedAndFollowsSettings() = runTest {
        val settings = MutableStateFlow(Settings.DEFAULT)
        val r = repo(listOf(samsung), settings = settings)
        r.start(backgroundScope)
        runCurrent()
        assertEquals("rndis", r.current.usbFunction)

        settings.value = Settings.DEFAULT.copy(
            profileOverride = jsonObjectOf("usb_function" to "ncm", "current_now" to mapOf("sign" to -1)),
        )
        runCurrent()
        assertEquals("ncm", r.profile.value.usbFunction)
        assertEquals(CurrentNowSpec(unit = "uA", sign = -1), r.current.currentNow)
        assertEquals("samsung_galaxy_s", r.current.id)
        assertEquals("rndis", r.matched.usbFunction)

        settings.value = Settings.DEFAULT.copy(profileOverride = jsonObjectOf("charge_control" to "not a list"))
        runCurrent()
        assertEquals("invalid override is ignored", r.matched, r.current)
    }

    @Test
    fun templatesRenderWithShellQuoting() {
        assertEquals(
            "cmd wifi start-softap 'My Net' wpa2 'it'\\''s'",
            DeviceProfile.render("cmd wifi start-softap {ssid} wpa2 {password}", mapOf("ssid" to "My Net", "password" to "it's")),
        )
        assertNull(DeviceProfile.render("svc usb setFunctions {function}", emptyMap()))
        assertEquals("svc data enable", DeviceProfile.render("svc data enable", emptyMap()))
    }

    @Test
    fun matchAndCurrentConversion() {
        assertTrue(ProfileMatch().isEmpty)
        assertEquals(2, ProfileMatch(listOf("samsung"), "^SM").specificity)
        assertEquals(-1500, CurrentNowSpec("uA", -1).toMilliamps(1_500_000))
        assertEquals(250, CurrentNowSpec("mA", 1).toMilliamps(250))
    }
}
