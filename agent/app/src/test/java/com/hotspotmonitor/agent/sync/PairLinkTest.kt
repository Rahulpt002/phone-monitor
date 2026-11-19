package com.hotspotmonitor.agent.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLinkTest {
    @Test
    fun parsesTheDashboardDeepLink() {
        // Exactly what the Server builds: encodeURIComponent(baseUrl).
        val link = PairLink.parse("hsm://pair?server=https%3A%2F%2Fhsm.onrender.com&code=ABCD2345")
        assertEquals(PairLink("https://hsm.onrender.com", "ABCD2345"), link)
    }

    @Test
    fun acceptsVariantsScannersProduce() {
        assertEquals(
            PairLink("https://hsm.onrender.com", "ABCD2345"),
            PairLink.parse("hsm://pair?server=https://hsm.onrender.com&code=ABCD2345"),
        )
        assertEquals(
            PairLink("https://hsm.onrender.com/base", "abcd2345"),
            PairLink.parse("  HSM://PAIR/?code=abcd2345&server=https%3A%2F%2Fhsm.onrender.com%2Fbase  "),
        )
        assertEquals(
            "first value wins",
            PairLink("https://a.example", "AAAA2222"),
            PairLink.parse("hsm://pair?server=https%3A%2F%2Fa.example&code=AAAA2222&server=https%3A%2F%2Fb.example"),
        )
    }

    @Test
    fun rejectsOtherLinks() {
        listOf(
            null,
            "",
            "   ",
            "https://hsm.onrender.com/pair?server=x&code=y",
            "hsm://unpair?server=https%3A%2F%2Fx.example&code=ABCD2345",
            "hsm://pair/extra?server=https%3A%2F%2Fx.example&code=ABCD2345",
            "hsm://pair?server=https%3A%2F%2Fx.example",
            "hsm://pair?code=ABCD2345",
            "hsm://pair?server=&code=ABCD2345",
            "hsm://pair?server=%E0%A4%A&code=ABCD2345",
            "hsm://pair?server=https://x.example&code=AB CD",
        ).forEach { assertNull(it, PairLink.parse(it)) }
    }

    @Test
    fun buildsLinksAndAdbCommandsThatParseBack() {
        val link = PairingUrls.deepLink("https://hsm.onrender.com", "ABCD2345")
        assertEquals("hsm://pair?server=https%3A%2F%2Fhsm.onrender.com&code=ABCD2345", link)
        assertEquals(PairLink("https://hsm.onrender.com", "ABCD2345"), PairLink.parse(link))

        // docs/protocol.md 2 / shared PairCodeResponse.adb_command: the adb-only activity-alias.
        val adb = PairingUrls.adbCommand(link)
        assertEquals(
            "adb shell \"am start -n com.hotspotmonitor.agent/.ui.AdbPair -a android.intent.action.VIEW -d '$link' --ez auto true\"",
            adb,
        )
        assertTrue(PairingUrls.adbCommand(link, replace = true).endsWith("--ez auto true --ez replace true\""))
        assertFalse(PairingUrls.adbCommand(link, auto = false).contains("--ez"))
    }

    @Test
    fun autoAndReplaceCountOnlyThroughTheAdbEntry() {
        val adb = PairLaunch.ADB_ENTRY
        val exported = "com.hotspotmonitor.agent.ui.PairActivity"
        // Any app or web page (intent: URL with B.auto=true;B.replace=true) reaches only PairActivity.
        assertEquals(PairLaunch.TAP_AUTO_IGNORED, PairLaunch.decide(exported, auto = true, replace = true, paired = true))
        assertEquals(PairLaunch.TAP_AUTO_IGNORED, PairLaunch.decide(exported, auto = true, replace = false, paired = false))
        assertEquals(PairLaunch.TAP_AUTO_IGNORED, PairLaunch.decide(null, auto = true, replace = true, paired = false))
        assertEquals(PairLaunch.TAP, PairLaunch.decide(exported, auto = false, replace = true, paired = true))
        // adb (shell uid) through the DUMP-guarded alias.
        assertEquals(PairLaunch.AUTO, PairLaunch.decide(adb, auto = true, replace = false, paired = false))
        assertEquals(PairLaunch.TAP_ALREADY_PAIRED, PairLaunch.decide(adb, auto = true, replace = false, paired = true))
        assertEquals(PairLaunch.AUTO, PairLaunch.decide(adb, auto = true, replace = true, paired = true))
        assertEquals(PairLaunch.TAP, PairLaunch.decide(adb, auto = false, replace = true, paired = true))
    }

    @Test
    fun tryAgainReplacesAPairingOnlyForTheLinkThatWasApproved() {
        val a = PairLink("https://a.example.com", "AAAA-1111")
        val b = PairLink("https://b.example.com", "BBBB-2222")
        // Not paired: the tap on Try again is the approval.
        assertFalse(PairLaunch.retryNeedsConfirm(b, approved = a, paired = false))
        assertFalse(PairLaunch.retryNeedsConfirm(b, approved = null, paired = false))
        // Paired: retrying the link that was confirmed (or started through adb) needs nothing more.
        assertFalse(PairLaunch.retryNeedsConfirm(a, approved = a, paired = true))
        // A link delivered by onNewIntent while A was running was never confirmed: ask again.
        assertTrue(PairLaunch.retryNeedsConfirm(b, approved = a, paired = true))
        assertTrue(PairLaunch.retryNeedsConfirm(b, approved = null, paired = true))
    }

    @Test
    fun adbEntryIsTheManifestAliasGuardedByDump() {
        val manifest = java.io.File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml").readText()
        val alias = Regex("""<activity-alias[^>]*android:name="\.ui\.AdbPair"[^>]*/>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.value
        assertTrue("activity-alias .ui.AdbPair missing", alias != null)
        assertTrue(alias!!.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(alias.contains("android:targetActivity=\".ui.PairActivity\""))
        assertFalse("reachable by explicit component only", alias.contains("intent-filter"))
        assertEquals("com.hotspotmonitor.agent" + ".ui.AdbPair", PairLaunch.ADB_ENTRY)
    }

    @Test
    fun normalizesServerUrls() {
        assertEquals("https://hsm.onrender.com", PairingUrls.normalizeBaseUrl("hsm.onrender.com", allowHttp = false))
        assertEquals("https://hsm.onrender.com", PairingUrls.normalizeBaseUrl(" https://HSM.onrender.com/ ", allowHttp = false))
        assertEquals("https://example.com:8443/hsm", PairingUrls.normalizeBaseUrl("https://example.com:8443/hsm/?x=1#y", allowHttp = false))
        assertNull("plain http only in debug builds", PairingUrls.normalizeBaseUrl("http://192.168.1.5:8080", allowHttp = false))
        assertEquals("http://192.168.1.5:8080", PairingUrls.normalizeBaseUrl("http://192.168.1.5:8080", allowHttp = true))
        listOf(null, "", "https://", "ftp://example.com", "https://user:pw@example.com", "https://exa mple.com").forEach {
            assertNull(it, PairingUrls.normalizeBaseUrl(it, allowHttp = true))
        }
    }

    @Test
    fun derivesAndValidatesWebSocketUrls() {
        assertEquals("wss://hsm.onrender.com/ws/agent", PairingUrls.agentWsUrl("https://hsm.onrender.com"))
        assertEquals("ws://10.0.0.2:8080/ws/agent", PairingUrls.agentWsUrl("http://10.0.0.2:8080/"))
        assertTrue(PairingUrls.isValidWsUrl("wss://hsm.onrender.com/ws/agent", allowHttp = false))
        assertFalse(PairingUrls.isValidWsUrl("ws://hsm.onrender.com/ws/agent", allowHttp = false))
        assertTrue(PairingUrls.isValidWsUrl("ws://10.0.0.2:8080/ws/agent", allowHttp = true))
        assertFalse(PairingUrls.isValidWsUrl("https://hsm.onrender.com/ws/agent", allowHttp = true))
        assertFalse(PairingUrls.isValidWsUrl(null, allowHttp = true))
    }

    @Test
    fun normalizesCodes() {
        assertEquals("ABCD2345", PairingUrls.normalizeCode(" abcd-2345 "))
        assertEquals("ABCD2345", PairingUrls.normalizeCode("ab cd 23 45"))
        assertEquals("", PairingUrls.normalizeCode(null))
    }
}
