package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.protocol.IfCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun parsesIputilsPing() {
        val out = """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=23.4 ms
            64 bytes from 1.1.1.1: icmp_seq=2 ttl=57 time=25.1 ms
            64 bytes from 1.1.1.1: icmp_seq=3 ttl=57 time=21.9 ms
            64 bytes from 1.1.1.1: icmp_seq=5 ttl=57 time=30.2 ms

            --- 1.1.1.1 ping statistics ---
            5 packets transmitted, 4 received, 20% packet loss, time 806ms
            rtt min/avg/max/mdev = 21.912/25.150/30.215/3.123 ms
        """.trimIndent()
        val p = parsePing(out)!!
        assertTrue(p.ran)
        assertEquals(5, p.transmitted)
        assertEquals(4, p.received)
        assertEquals(20.0, p.lossPct!!, 0.0)
        assertEquals(25.2, p.avgMs!!, 1e-9)
        assertEquals(3.1, p.jitterMs!!, 1e-9)
        assertEquals(listOf(23.4, 25.1, 21.9, 30.2), p.timesMs)
    }

    @Test
    fun parsesTotalLossAndErrors() {
        val out = """
            PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
            From 10.0.0.1 icmp_seq=1 Destination Net Unreachable

            --- 1.1.1.1 ping statistics ---
            5 packets transmitted, 0 received, +5 errors, 100% packet loss, time 4005ms
        """.trimIndent()
        val p = parsePing(out)!!
        assertTrue(p.ran)
        assertEquals(100.0, p.lossPct!!, 0.0)
        assertNull(p.avgMs)
        assertNull(p.jitterMs)
    }

    @Test
    fun parsesToyboxPingWithoutMdev() {
        val out = """
            Ping 8.8.8.8 (8.8.8.8): 56(84) bytes.
            64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=40 ms
            64 bytes from 8.8.8.8: icmp_seq=2 ttl=117 time=44 ms
            64 bytes from 8.8.8.8: icmp_seq=3 ttl=117 time=42 ms
            --- 8.8.8.8 ping statistics ---
            3 packets transmitted, 3 received, 0% packet loss
            round-trip min/avg/max = 40/42/44 ms
        """.trimIndent()
        val p = parsePing(out)!!
        assertEquals(42.0, p.avgMs!!, 0.0)
        assertEquals(0.0, p.lossPct!!, 0.0)
        // Jitter from consecutive differences: |44-40| = 4, |42-44| = 2 -> 3
        assertEquals(3.0, p.jitterMs!!, 1e-9)
    }

    @Test
    fun pingWithoutStatisticsIsNotARun() {
        assertNull(parsePing("ping: socket: Operation not permitted"))
        assertNull(parsePing(""))
        assertNull(parsePing(null))
        val partial = parsePing("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.0 ms")!!
        assertFalse(partial.ran)
    }

    @Test
    fun safeHostsOnly() {
        assertTrue(isSafeHost("1.1.1.1"))
        assertTrue(isSafeHost("google.com"))
        assertTrue(isSafeHost("2606:4700:4700::1111"))
        assertFalse(isSafeHost("-f 1.1.1.1"))
        assertFalse(isSafeHost("1.1.1.1;reboot"))
        assertFalse(isSafeHost(""))
    }

    @Test
    fun parsesToyboxTop() {
        val out = """
            Tasks: 512 total,   1 running, 511 sleeping,   0 stopped,   0 zombie
              Mem:  5675340K total,  5428644K used,   246696K free,    52476K buffers
             Swap:  2621436K total,   587520K used,  2033916K free,  2512060K cached
            800%cpu   9%user   0%nice  17%sys 773%idle   0%iow   0%irq   1%sirq   0%host
              PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
             6789 shell        20   0  10G 4.1M 3.2M R 22.2   0.0   0:00.06 top -b -n 1 -m 10
              789 system       18  -2  12G 245M 189M S  3.7   4.4 102:34.07 system_server
             1234 radio        20   0  14G 1.5G  95M S  1.8   1.6  12:01.00 com.android.phone
              321 root         20   0  12M 2048K 1.1M S  0.0   0.0   0:01.00 /system/bin/netd --flag
        """.trimIndent()
        val top = parseTop(out)
        assertEquals(listOf("system_server", "com.android.phone", "netd"), top.map { it.name })
        assertEquals(789, top[0].pid)
        assertEquals(3.7, top[0].cpuPct!!, 0.0)
        assertEquals(245.0, top[0].memMb!!, 0.0)
        assertEquals(1536.0, top[1].memMb!!, 0.0)
        assertEquals(2.0, top[2].memMb!!, 0.0)
        assertTrue(parseTop("no header here").isEmpty())
        assertEquals(1, parseTop(out, max = 1).size)
    }

    @Test
    fun sizesToMegabytes() {
        assertEquals(4.1, parseSizeMb("4.1M")!!, 0.0)
        assertEquals(2048.0, parseSizeMb("2G")!!, 0.0)
        assertEquals(4.0, parseSizeMb("4096K")!!, 0.0)
        assertEquals(0.0, parseSizeMb("0")!!, 0.0)
        assertNull(parseSizeMb("abc"))
    }

    @Test
    fun parsesIpAddr() {
        val out = """
            1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
            25: rndis0    inet 192.168.42.129/24 brd 192.168.42.255 scope global rndis0\       valid_lft forever preferred_lft forever
            25: rndis0    inet6 fe80::1c2d:3eff:fe4f:5a6b/64 scope link \       valid_lft forever preferred_lft forever
            30: rmnet_data2    inet6 2405:200:1:2::5/64 scope global dynamic noprefixroute \       valid_lft 7199sec
            31: v4-rmnet_data2    inet 192.0.0.4/32 scope global v4-rmnet_data2\       valid_lft forever
        """.trimIndent()
        val addrs = parseIpAddr(out)
        assertEquals(5, addrs.size)
        val rndis = addrs.first { it.iface == "rndis0" && it.family == 4 }
        assertEquals("192.168.42.129", rndis.address)
        assertEquals(24, rndis.prefix)
        assertEquals("global", rndis.scope)
        assertEquals("link", addrs.first { it.iface == "rndis0" && it.family == 6 }.scope)
        assertEquals("2405:200:1:2::5", addrs.first { it.iface == "rmnet_data2" }.address)
        assertTrue(parseIpAddr("garbage\n").isEmpty())
    }

    @Test
    fun parsesIpNeigh() {
        val out = """
            192.168.42.200 dev rndis0 lladdr AA:BB:CC:DD:EE:01 REACHABLE
            192.168.42.201 dev rndis0 lladdr aa:bb:cc:dd:ee:02 STALE
            192.168.42.202 dev rndis0  FAILED
            fe80::1 dev rndis0 lladdr aa:bb:cc:dd:ee:01 router STALE
            10.0.0.1 dev rmnet_data2 lladdr 00:00:00:00:00:00 PERMANENT
        """.trimIndent()
        val n = parseIpNeigh(out)
        assertEquals(5, n.size)
        assertEquals(Neighbour("192.168.42.200", "rndis0", "aa:bb:cc:dd:ee:01", "REACHABLE"), n[0])
        assertNull(n[2].mac)
        assertEquals("FAILED", n[2].state)
        assertEquals("STALE", n[3].state)

        val withDev = parseIpNeigh("192.168.43.5 lladdr 11:22:33:44:55:66 DELAY", defaultIface = "swlan0")
        assertEquals("swlan0", withDev.single().iface)
        assertEquals("DELAY", withDev.single().state)
    }

    @Test
    fun parsesLoadAvg() {
        assertEquals(listOf(0.52, 0.58, 0.59), parseLoadAvg("0.52 0.58 0.59 1/1234 5678\n"))
        assertNull(parseLoadAvg("0.5 x 0.1"))
        assertNull(parseLoadAvg(""))
        assertNull(parseLoadAvg(null))
    }

    @Test
    fun parsesInterfaceCounters() {
        val out = "lo 100 100\nrmnet_data2 123456 7890\nwlan0  \nbroken x 1\n"
        assertEquals(
            mapOf("lo" to IfCounters(100, 100), "rmnet_data2" to IfCounters(123456, 7890)),
            parseIfCounters(out),
        )
        assertFalse(isReportableInterface("lo"))
        assertFalse(isReportableInterface("dummy0"))
        assertTrue(isReportableInterface("rmnet_data2"))
        assertTrue(isReportableInterface("rndis0"))
    }

    @Test
    fun parsesGetprop() {
        assertEquals("reboot,userrequested", parseGetprop("\nreboot,userrequested\n"))
        assertNull(parseGetprop("   \n"))
    }

    @Test
    fun parsesSoftApSsid() {
        assertEquals("Roof AP", parseSoftApSsid("""<string name="WifiSsid">&quot;Roof AP&quot;</string>"""))
        assertEquals("Old\"Name", parseSoftApSsid("""<string name="SSID">"Old&quot;Name"</string>"""))
        assertEquals("Hi", parseSoftApSsid("""<string name="WifiSsid">4869</string>"""))
        assertEquals("A&B", parseSoftApSsid("""<string name="WifiSsid">&quot;A&amp;B&quot;</string>"""))
        assertNull(parseSoftApSsid("""<string name="Passphrase">secret</string>"""))
        assertNull(parseSoftApSsid(null))
    }

    @Test
    fun luhnValidatesImeis() {
        assertTrue(luhnValid("490154203237518"))
        assertFalse(luhnValid("490154203237519"))
        assertFalse(luhnValid("49015420323751x"))
        assertFalse(luhnValid(""))
    }

    @Test
    fun parsesPublicIp() {
        assertEquals("203.0.113.7", parsePublicIp("203.0.113.7\n"))
        assertEquals("2405:200:1:2::5", parsePublicIp("2405:200:1:2::5"))
        assertNull(parsePublicIp("300.1.1.1"))
        assertNull(parsePublicIp("<html>captive portal</html>"))
        assertNull(parsePublicIp(null))
    }
}
