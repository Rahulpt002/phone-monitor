package com.hotspotmonitor.agent.telemetry

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.hotspotmonitor.agent.protocol.TetherClient
import com.hotspotmonitor.agent.protocol.TetherInfo
import com.hotspotmonitor.agent.protocol.UsbFunction
import com.hotspotmonitor.agent.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/** The USB_STATE sticky broadcast, decoded. */
internal data class UsbState(val connected: Boolean, val configured: Boolean, val functions: Set<String>)

/**
 * USB tethering and hotspot state (plan 6.2 "Tethering"). Pure: everything Android-specific is
 * read by [TetherCollector] and handed in, so the rules are unit-tested.
 */
internal object TetherAssembler {
    private val USB_IFACE = Regex("^(rndis|ncm|usb)\\d+$")
    private val WIFI_TETHER_IFACE = Regex("^(wlan|swlan|ap|softap|wifi|wigig)\\d*$")
    private val AP_ONLY_IFACE = Regex("^(swlan|ap|softap)\\d*$")
    private val USB_FLAGS = setOf("connected", "configured", "host_connected", "unlocked", "data_unlocked", "adb", "none")

    fun usbState(extras: Map<String, Boolean>): UsbState = UsbState(
        connected = extras["connected"] == true,
        configured = extras["configured"] == true,
        functions = extras.filter { (k, v) -> v && k !in USB_FLAGS }.keys,
    )

    fun usbFunction(usb: UsbState?): UsbFunction? {
        usb ?: return null
        return when {
            "rndis" in usb.functions -> UsbFunction.RNDIS
            "ncm" in usb.functions -> UsbFunction.NCM
            "mtp" in usb.functions -> UsbFunction.MTP
            usb.functions.isNotEmpty() -> UsbFunction.OTHER
            else -> UsbFunction.NONE
        }
    }

    fun isUsbIface(name: String) = USB_IFACE.matches(name)

    fun assemble(
        usb: UsbState?,
        /** Interfaces currently tethered (TETHER_STATE_CHANGED "tetherArray"); null when unknown. */
        activeTether: List<String>?,
        addrs: List<IfAddr>?,
        neighbours: List<Neighbour>?,
        /** WifiManager.isWifiApEnabled() via reflection; null when unavailable. */
        hotspotFlag: Boolean?,
        hotspotSsid: String?,
    ): TetherInfo {
        val v4 = addrs.orEmpty().filter { it.family == 4 }
        val usbAddr = v4.firstOrNull { isUsbIface(it.iface) && (activeTether == null || it.iface in activeTether) }
            ?: v4.firstOrNull { isUsbIface(it.iface) }
        val tetherIf = activeTether?.firstOrNull { isUsbIface(it) } ?: usbAddr?.iface
        val tetherIp = v4.firstOrNull { it.iface == tetherIf }?.address
        val usbTetherOn: Boolean? = when {
            activeTether != null -> activeTether.any { isUsbIface(it) }
            usbAddr != null -> true
            usb != null || addrs != null -> false
            else -> null
        }
        val wifiTetherIfs = activeTether.orEmpty().filter { WIFI_TETHER_IFACE.matches(it) }
        val apAddrIfs = v4.filter { AP_ONLY_IFACE.matches(it.iface) }.map { it.iface }
        val hotspotOn: Boolean? = hotspotFlag
            ?: when {
                wifiTetherIfs.isNotEmpty() || apAddrIfs.isNotEmpty() -> true
                activeTether != null -> false
                else -> null
            }
        val clientIfs = buildSet {
            tetherIf?.let { add(it) }
            addAll(wifiTetherIfs)
            addAll(apAddrIfs)
        }
        val clients = neighbours?.let { clientsOn(it, clientIfs) }
        return TetherInfo(
            usbConnected = usb?.connected,
            usbFunction = usbFunction(usb),
            usbTetherOn = usbTetherOn,
            tetherIf = tetherIf,
            tetherIp = tetherIp,
            hotspotOn = hotspotOn,
            hotspotSsid = hotspotSsid?.takeIf { hotspotOn == true },
            clients = clients,
        )
    }

    /** Devices seen recently on the tether / hotspot interfaces: IPv4 entries, plus IPv6 for MACs without one. */
    fun clientsOn(neighbours: List<Neighbour>, ifaces: Set<String>): List<TetherClient> {
        val live = neighbours.filter { it.iface in ifaces && it.state in LIVE_NEIGH_STATES && it.mac != null }
        val out = LinkedHashMap<String, TetherClient>()
        for (n in live.filter { !it.ip.contains(':') }) {
            out.putIfAbsent(n.mac!!, TetherClient(ip = n.ip, mac = n.mac, iface = n.iface))
        }
        for (n in live.filter { it.ip.contains(':') && !it.ip.lowercase().startsWith("fe80") }) {
            out.putIfAbsent(n.mac!!, TetherClient(ip = n.ip, mac = n.mac, iface = n.iface))
        }
        return out.values.take(MAX_CLIENTS)
    }

    private const val MAX_CLIENTS = 32
}

/** Reads USB, tethering and hotspot state; client lists and SSID need root. */
internal class TetherCollector(
    private val app: Context,
    private val shell: () -> RootShell,
    private val failures: FailureLog,
    private val nowElapsedMs: () -> Long,
) {
    @Volatile
    private var ssidCache: Pair<Long, String?>? = null

    suspend fun collect(): TetherInfo {
        val usb = failures.soft("usb_state") { app.stickyIntent(ACTION_USB_STATE)?.let { usbStateOf(it) } }
        val activeTether = failures.soft("tether_state") {
            app.stickyIntent(ACTION_TETHER_STATE_CHANGED)?.getStringArrayListExtra(EXTRA_ACTIVE_TETHER)?.toList()
        }
        val addrs = readAddresses()
        val hotspotFlag = hotspotViaWifiManager()
        val wantNeighbours = addrs.orEmpty().any { TetherAssembler.isUsbIface(it.iface) || it.iface.startsWith("swlan") || it.iface.startsWith("ap") || it.iface.startsWith("softap") } ||
            !activeTether.isNullOrEmpty()
        val neighbours = if (wantNeighbours) readNeighbours() else emptyList()
        val provisional = TetherAssembler.assemble(usb, activeTether, addrs, neighbours, hotspotFlag, null)
        val ssid = if (provisional.hotspotOn == true) hotspotSsid() else null
        return if (ssid == null) provisional else provisional.copy(hotspotSsid = ssid)
    }

    private fun usbStateOf(intent: Intent): UsbState {
        val extras = intent.extras ?: return UsbState(connected = false, configured = false, functions = emptySet())
        val flags = extras.keySet().associateWith { key -> extras.getBoolean(key, false) }
        return TetherAssembler.usbState(flags)
    }

    /** `ip -o addr show` through the root shell; NetworkInterface when root is unavailable. */
    private suspend fun readAddresses(): List<IfAddr>? {
        val root = shell()
        if (root.rooted.value != false) {
            val r = root.run("ip -o addr show", 10_000, quiet = true)
            if (r.ok) {
                failures.ok("ip_addr")
                return parseIpAddr(r.out)
            }
            failures.fail("ip_addr", "ip -o addr failed (${r.summary()}); using NetworkInterface")
        }
        return withContext(Dispatchers.IO) {
            failures.soft("network_interfaces") {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { ni ->
                    ni.interfaceAddresses.mapNotNull { ia ->
                        when (val a = ia.address) {
                            is Inet4Address -> IfAddr(ni.name, 4, a.hostAddress ?: return@mapNotNull null, ia.networkPrefixLength.toInt(), null)
                            is Inet6Address -> IfAddr(ni.name, 6, a.hostAddress?.substringBefore('%') ?: return@mapNotNull null, ia.networkPrefixLength.toInt(), null)
                            else -> null
                        }
                    }
                }
            }
        }
    }

    private suspend fun readNeighbours(): List<Neighbour>? {
        val root = shell()
        if (root.rooted.value == false) return null
        val r = root.run("ip neigh show", 10_000, quiet = true)
        if (!r.ok) {
            failures.fail("ip_neigh", "ip neigh failed (${r.summary()})")
            return null
        }
        failures.ok("ip_neigh")
        return parseIpNeigh(r.out)
    }

    /** WifiManager.isWifiApEnabled() is hidden API; reflection works on many ROMs, null otherwise. */
    private fun hotspotViaWifiManager(): Boolean? {
        val wm = app.getSystemService(WifiManager::class.java) ?: return null
        return try {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
        } catch (e: Exception) {
            failures.fail("hotspot_reflection", "WifiManager.isWifiApEnabled is not accessible; using tethering state")
            null
        } catch (e: LinkageError) {
            null
        }
    }

    /** Hotspot name from the SoftAp config store (root). Only the SSID line is printed, never the passphrase. */
    private suspend fun hotspotSsid(): String? {
        val now = nowElapsedMs()
        ssidCache?.let { (at, value) -> if (now - at < SSID_CACHE_MS) return value }
        val root = shell()
        if (root.rooted.value == false) return null
        val r = root.run(SSID_CMD, 5_000, logAs = "grep SSID <softap config>", quiet = true)
        val ssid = parseSoftApSsid(r.out)
        ssidCache = now to ssid
        return ssid
    }

    companion object {
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val EXTRA_ACTIVE_TETHER = "tetherArray"
        private const val SSID_CACHE_MS = 10 * 60_000L
        private const val SSID_CMD = "for f in /data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml " +
            "/data/misc/wifi/WifiConfigStoreSoftAp.xml; do " +
            "[ -f \"\$f\" ] && grep -m 1 -E 'name=\"(WifiSsid|SSID)\"' \"\$f\" && break; done"
    }
}
