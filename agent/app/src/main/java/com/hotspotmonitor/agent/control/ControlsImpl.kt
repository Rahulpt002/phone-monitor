package com.hotspotmonitor.agent.control

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.hotspotmonitor.agent.core.ActionResult
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Controls
import com.hotspotmonitor.agent.core.Ids
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CommandNames
import com.hotspotmonitor.agent.protocol.CommandSource
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.NetworkMode
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.SmsDirection
import com.hotspotmonitor.agent.protocol.SmsMsg
import com.hotspotmonitor.agent.protocol.SmsRecord
import com.hotspotmonitor.agent.protocol.UsbFunction
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import com.hotspotmonitor.agent.root.DeviceProfile
import com.hotspotmonitor.agent.root.RootShell
import com.hotspotmonitor.agent.root.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.provider.Settings as AndroidSettings

/**
 * Root actions of plan 6.3. Every action tries the device profile's command templates first (in
 * order, first success wins) and then the built-in commands below. Arguments are validated by
 * [ArgRules] and shell-quoted; secrets never reach the log (RootShell `logAs`).
 *
 * Single radio switches are serialized by [radioMutex]; cycles hold it only for each switch, so an
 * auto-revert undo never waits for a long cycle.
 */
class ControlsImpl(private val graph: AgentGraph) : Controls {
    private val radioMutex = Mutex()
    private val rebootMutex = Mutex()
    private val helpMutex = Mutex()
    private val helpCache = HashMap<String, Pair<Long, String>>()
    private val smsSender by lazy { SmsSender(graph.app, graph.log) }
    private val ussdRunner by lazy { UssdRunner(graph.app) }

    private val shell: RootShell get() = graph.shell
    private val profile: DeviceProfile get() = graph.profiles.current

    private data class Candidate(val cmd: String, val label: String = cmd)

    // --- radios ---------------------------------------------------------------------------------

    override suspend fun setMobileData(enabled: Boolean): ActionResult = radioMutex.withLock {
        attempt(
            name = CommandNames.MOBILE_DATA,
            values = switchValues(enabled),
            defaults = listOf(Candidate(ControlCommands.mobileData(enabled))),
            verify = { awaitState(enabled, STATE_WAIT_MS) { mobileDataEnabled() } },
        ).withSummary("Mobile data ${onOff(enabled)}")
    }

    override suspend fun cycleData(offSeconds: Int): ActionResult {
        val seconds = offSeconds.coerceIn(ArgRules.OFF_SECONDS)
        markRestore(KEY_RESTORE_DATA, true)
        val off = setMobileData(false)
        if (!off.ok) {
            withContext(NonCancellable) { restoreData() }
            return ActionResult.failure("Could not turn mobile data off: ${off.error}", method = off.method)
        }
        val on = restoreAfter(seconds) { restoreData() }
        return if (on.ok) {
            ActionResult.success("Mobile data off for $seconds s, then on", off.method)
        } else {
            ActionResult.failure("Mobile data did not come back on: ${on.error}", method = on.method)
        }
    }

    override suspend fun setAirplane(enabled: Boolean): ActionResult = radioMutex.withLock {
        attempt(
            name = CommandNames.AIRPLANE_MODE,
            values = switchValues(enabled),
            defaults = listOf(
                Candidate(ControlCommands.airplaneCmd(enabled)),
                Candidate(ControlCommands.airplaneLegacy(enabled)),
            ),
            verify = { awaitState(enabled, STATE_WAIT_MS) { airplaneOn() } },
        ).withSummary("Airplane mode ${onOff(enabled)}")
    }

    override suspend fun airplaneCycle(offSeconds: Int): ActionResult {
        val seconds = offSeconds.coerceIn(ArgRules.OFF_SECONDS)
        markRestore(KEY_RESTORE_AIRPLANE, true)
        val on = setAirplane(true)
        if (!on.ok) {
            withContext(NonCancellable) { restoreAirplane() }
            return ActionResult.failure("Could not switch airplane mode on: ${on.error}", method = on.method)
        }
        val off = restoreAfter(seconds) { restoreAirplane() }
        return if (off.ok) {
            ActionResult.success("Airplane mode on for $seconds s, then off", on.method)
        } else {
            ActionResult.failure("Airplane mode did not switch off again: ${off.error}", method = off.method)
        }
    }

    override suspend fun restRadio(seconds: Int): ActionResult {
        val restS = seconds.coerceAtLeast(1)
        markRestore(KEY_RESTORE_AIRPLANE, true)
        val on = setAirplane(true)
        if (!on.ok) {
            withContext(NonCancellable) { restoreAirplane() }
            return ActionResult.failure("Could not switch the radio off: ${on.error}", method = on.method)
        }
        val off = restoreAfter(restS) { restoreAirplane() }
        return if (off.ok) {
            ActionResult.success("Radio rested for $restS s", on.method)
        } else {
            ActionResult.failure("Airplane mode did not switch off after the rest: ${off.error}", method = off.method)
        }
    }

    override suspend fun setUsbTethering(enabled: Boolean, function: String?): ActionResult = radioMutex.withLock {
        if (!enabled) {
            return@withLock attempt(
                name = CommandNames.USB_TETHERING,
                values = switchValues(false) + ("function" to USB_FUNCTION_NONE),
                defaults = listOf(Candidate(ControlCommands.usbFunctions(null))),
                verify = { awaitState(false, STATE_WAIT_MS) { tetherIfaceUp() } },
            ).withSummary("USB tethering off")
        }
        val fn = resolveUsbFunction(function)
        val set = attempt(
            name = CommandNames.USB_TETHERING,
            values = switchValues(true) + ("function" to fn),
            defaults = listOf(Candidate(ControlCommands.usbFunctions(fn))),
        )
        if (!set.ok) return@withLock set
        awaitTetherIface(TETHER_WAIT_MS)?.let { iface ->
            return@withLock ActionResult.success("USB tethering on: $fn, ${iface.name} ${iface.ip}", set.method)
        }
        if (graph.telemetry.tether.value?.usbConnected == false) {
            return@withLock ActionResult.success(
                "USB function set to $fn; tethering starts when a USB host is connected", set.method,
            )
        }
        // The host did not pick up the new function: re-enumerate USB once.
        shell.run(ControlCommands.usbFunctions(null))
        delay(USB_REENUMERATE_MS)
        shell.run(ControlCommands.usbFunctions(fn))
        val iface = awaitTetherIface(TETHER_WAIT_MS)
        if (iface != null) {
            ActionResult.success("USB tethering on after re-enumeration: $fn, ${iface.name} ${iface.ip}", set.method)
        } else {
            ActionResult.failure("USB function set to $fn, but no tethering interface came up", method = set.method)
        }
    }

    override suspend fun setHotspot(enabled: Boolean, ssid: String?, password: String?): ActionResult = radioMutex.withLock {
        if (!enabled) {
            return@withLock attempt(
                name = CommandNames.HOTSPOT,
                values = switchValues(false),
                defaults = listOf(Candidate(ControlCommands.STOP_SOFTAP)),
            ).withSummary("Hotspot off")
        }
        val rawSsid = ssid ?: graph.prefs.getString(KEY_HOTSPOT_SSID)
        val rawPassword = password ?: graph.secureStore.getString(KEY_HOTSPOT_PASSWORD)
        if (rawSsid == null || rawPassword == null) {
            return@withLock ActionResult.failure("Send the hotspot name (ssid) and password the first time")
        }
        val (name, pass) = runCatching { ArgRules.ssid(rawSsid) to ArgRules.wpa2Password(rawPassword) }
            .getOrElse { return@withLock ActionResult.failure(it.message ?: "Invalid hotspot name or password") }
        val result = attempt(
            name = CommandNames.HOTSPOT,
            values = switchValues(true) + mapOf("ssid" to name, "password" to pass),
            defaults = listOf(Candidate(ControlCommands.startSoftAp(name, pass), ControlCommands.START_SOFTAP_LOG)),
            timeoutMs = SOFTAP_TIMEOUT_MS,
            redactProfile = true,
        )
        if (result.ok) {
            graph.prefs.putString(KEY_HOTSPOT_SSID, name)
            graph.secureStore.putString(KEY_HOTSPOT_PASSWORD, pass)
            ActionResult.success("Hotspot '$name' on (WPA2)", result.method)
        } else {
            result.copy(output = "")
        }
    }

    override suspend fun setWifi(enabled: Boolean): ActionResult = radioMutex.withLock {
        attempt(
            name = CommandNames.WIFI,
            values = switchValues(enabled),
            defaults = listOf(Candidate(ControlCommands.wifiSvc(enabled)), Candidate(ControlCommands.wifiCmd(enabled))),
            verify = { awaitState(enabled, RADIO_WAIT_MS) { wifiEnabled() } },
        ).withSummary("Wi-Fi ${onOff(enabled)}")
    }

    override suspend fun setBluetooth(enabled: Boolean): ActionResult = radioMutex.withLock {
        attempt(
            name = CommandNames.BLUETOOTH,
            values = switchValues(enabled),
            defaults = listOf(
                Candidate(ControlCommands.bluetoothSvc(enabled)),
                Candidate(ControlCommands.bluetoothCmd(enabled)),
            ),
            verify = { awaitState(enabled, RADIO_WAIT_MS) { bluetoothOn() } },
        ).withSummary("Bluetooth ${onOff(enabled)}")
    }

    override suspend fun setNetworkMode(mode: NetworkMode): ActionResult = radioMutex.withLock {
        val mask = NetworkTypes.bitmaskFor(mode)
        val slot = defaultDataSlot()
        val profileValue = profile.networkModeValue(mode)
        val value = profileValue ?: NetworkTypes.toBinary(mask)
        val defaults = if (profileValue == null || ArgRules.isSafeProfileToken(profileValue)) {
            listOf(Candidate(ControlCommands.setAllowedNetworkTypes(slot, value)))
        } else {
            graph.log.w(TAG, "Profile network_mode value for ${mode.wire} is not a plain token; only profile templates are used")
            emptyList()
        }
        val values = mapOf(
            "mode" to mode.wire,
            "value" to value,
            "bitmask" to mask.toString(),
            "bitmask_bin" to NetworkTypes.toBinary(mask),
            "slot" to slot.toString(),
            "sub_id" to defaultDataSubId().toString(),
        )
        val result = attempt(
            name = CommandNames.NETWORK_MODE,
            values = values,
            defaults = defaults,
            verify = { if (profileValue == null) verifyNetworkMode(slot, mode, mask) else null },
        )
        if (result.ok) graph.prefs.putString(KEY_LAST_NETWORK_MODE, mode.wire)
        result.withSummary("Network mode ${mode.wire}")
    }

    // --- power and screen -----------------------------------------------------------------------

    override suspend fun reboot(source: CommandSource, reason: String): ActionResult =
        reboot(source, reason) { storeRebootEvent(source, reason) }

    /**
     * Reboot within the limit. [beforeReboot] runs after the reboot was recorded and before the
     * command: it must store whatever has to survive the reboot (the event explaining it).
     */
    internal suspend fun reboot(
        source: CommandSource,
        reason: String,
        beforeReboot: suspend () -> Unit,
    ): ActionResult = rebootMutex.withLock {
        if (!rooted()) return@withLock noRoot()
        val limiter = graph.rebootLimiter
        val now = graph.clock.systemNowMs()
        if (!limiter.canReboot(now)) {
            val s = graph.settings.current.watchdog
            val count = limiter.countInWindow(now)
            val window = formatHours(s.rebootWindowH)
            graph.events.emit(
                EventKind.REBOOT_BLOCKED, Severity.WARNING,
                "Reboot blocked: $count reboots in the last $window h (limit ${s.maxReboots})",
                jsonObjectOf(
                    "source" to source.wire, "reason" to reason, "reboots_in_window" to count,
                    "max_reboots" to s.maxReboots, "window_h" to s.rebootWindowH,
                ),
            )
            return@withLock ActionResult.failure(
                "Reboot limit reached: $count of ${s.maxReboots} in $window h", method = ControlCommands.REBOOT.first(),
            )
        }
        limiter.record(source, now)
        beforeReboot()
        graph.sync.kick()
        delay(PRE_REBOOT_FLUSH_MS)
        graph.log.flush()
        attempt(
            name = CommandNames.REBOOT,
            values = emptyMap(),
            defaults = ControlCommands.REBOOT.map { Candidate(it) },
            timeoutMs = POWER_TIMEOUT_MS,
        ).withSummary("Rebooting ($reason)")
    }

    override suspend fun powerOff(): ActionResult = rebootMutex.withLock {
        if (!graph.settings.current.commands.allowPowerOff) {
            return@withLock ActionResult.failure("Power off is blocked (settings.commands.allow_power_off is false)")
        }
        if (!rooted()) return@withLock noRoot()
        graph.events.emitAndStore(
            EventKind.COMMAND, Severity.CRITICAL, "Powering off: the phone needs a hand to switch it on again",
            jsonObjectOf("name" to CommandNames.POWER_OFF, "status" to "running"),
        )
        graph.sync.kick()
        delay(PRE_REBOOT_FLUSH_MS)
        graph.log.flush()
        attempt(
            name = CommandNames.POWER_OFF,
            values = emptyMap(),
            defaults = ControlCommands.SHUTDOWN.map { Candidate(it) },
            timeoutMs = POWER_TIMEOUT_MS,
        ).withSummary("Shutting down")
    }

    override suspend fun setScreen(on: Boolean): ActionResult = attempt(
        name = CommandNames.SCREEN,
        values = mapOf("on" to on.toString(), "keycode" to (if (on) "224" else "223")),
        defaults = listOf(Candidate(ControlCommands.screen(on))),
    ).withSummary(if (on) "Screen woken" else "Screen off")

    override suspend fun setBrightness(level: Int): ActionResult {
        val value = level.coerceIn(ArgRules.BRIGHTNESS)
        return attempt(
            name = CommandNames.BRIGHTNESS,
            values = mapOf("level" to value.toString()),
            defaults = listOf(Candidate(ControlCommands.brightness(value))),
        ).withSummary("Brightness $value")
    }

    // --- apps and processes ---------------------------------------------------------------------

    override suspend fun listApps(): ActionResult {
        if (!rooted()) return noRoot()
        val all = shell.run("pm list packages", LIST_TIMEOUT_MS)
        if (!all.ok) return ActionResult.failure("pm list packages failed: ${failureText(all)}", method = METHOD_PM_LIST)
        val disabled = shell.run("pm list packages -d", LIST_TIMEOUT_MS)
        val system = shell.run("pm list packages -s", LIST_TIMEOUT_MS)
        val json = AppListParser.toJson(
            all = AppListParser.packages(all.out),
            disabled = if (disabled.ok) AppListParser.packages(disabled.out) else emptySet(),
            system = if (system.ok) AppListParser.packages(system.out) else emptySet(),
        )
        return ActionResult.success(json, METHOD_PM_LIST)
    }

    override suspend fun forceStopApp(pkg: String): ActionResult {
        val name = runCatching { ArgRules.packageName(pkg) }.getOrElse { return ActionResult.failure(it.message ?: "Invalid package") }
        if (name == graph.app.packageName) return ActionResult.failure("Refusing to stop the Agent itself")
        return attempt(
            name = CommandNames.APP_FORCE_STOP,
            values = mapOf("package" to name),
            defaults = listOf(Candidate(ControlCommands.forceStop(name))),
        ).withSummary("Force-stopped $name")
    }

    override suspend fun setAppEnabled(pkg: String, enabled: Boolean): ActionResult {
        val name = runCatching { ArgRules.packageName(pkg) }.getOrElse { return ActionResult.failure(it.message ?: "Invalid package") }
        if (!enabled && (name == graph.app.packageName || PROTECTED_PACKAGES.any { name == it || name.startsWith("$it.") })) {
            return ActionResult.failure("Refusing to disable $name: the phone needs it to stay reachable")
        }
        return attempt(
            name = if (enabled) CommandNames.APP_ENABLE else CommandNames.APP_DISABLE,
            values = mapOf("package" to name),
            defaults = listOf(Candidate(if (enabled) ControlCommands.enableApp(name) else ControlCommands.disableApp(name))),
        ).withSummary(if (enabled) "Enabled $name" else "Disabled $name")
    }

    override suspend fun processList(): ActionResult {
        val result = attempt(
            name = CommandNames.PROCESS_LIST,
            values = emptyMap(),
            defaults = ControlCommands.PROCESS_LIST.map { Candidate(it) },
            timeoutMs = LIST_TIMEOUT_MS,
        )
        return if (result.ok) result.copy(output = OutputText.truncate(result.output)) else result
    }

    // --- messaging ------------------------------------------------------------------------------

    override suspend fun sendSms(to: String, body: String): ActionResult {
        val (number, text) = runCatching { ArgRules.phoneNumber(to) to ArgRules.smsBody(body) }
            .getOrElse { return ActionResult.failure(it.message ?: "Invalid SMS") }
        val result = smsSender.send(number, text)
        if (result.ok) {
            graph.outbox.enqueue(
                SmsMsg(
                    sms = SmsRecord(
                        id = Ids.sms(), direction = SmsDirection.OUT, peer = number, body = text,
                        at = graph.clock.isoNow(),
                    ),
                ),
            )
            graph.sync.kick()
        }
        graph.log.i(TAG, "SMS to $number (${text.length} chars): ${if (result.ok) "sent" else result.error}")
        return result
    }

    override suspend fun runUssd(code: String): ActionResult {
        val ussd = runCatching { ArgRules.ussdCode(code) }.getOrElse { return ActionResult.failure(it.message ?: "Invalid USSD code") }
        return ussdRunner.run(ussd)
    }

    // --- admin ----------------------------------------------------------------------------------

    override suspend fun shell(cmd: String, timeoutS: Int): ActionResult {
        val command = runCatching { ArgRules.shellCommand(cmd) }.getOrElse { return ActionResult.failure(it.message ?: "Invalid command") }
        if (!rooted()) return noRoot()
        val seconds = timeoutS.coerceIn(ArgRules.SHELL_TIMEOUT_S)
        val r = shell.run(command, seconds * 1000L, logAs = "shell: ${command.take(SHELL_LOG_CHARS)}")
        val output = OutputText.truncate(OutputText.combine(r.out, r.err))
        return when {
            r.ok -> ActionResult.success(output, METHOD_SU)
            r.timedOut -> ActionResult.failure("Timed out after $seconds s", output, METHOD_SU)
            else -> ActionResult.failure("Exit code ${r.code}", output, METHOD_SU)
        }
    }

    // --- interrupted cycles ---------------------------------------------------------------------

    /**
     * A data cycle, airplane cycle or radio rest that was cut short by a process death leaves the
     * phone offline. Each persists a flag before switching off; this puts the radio back on at
     * start (CommandExecutorImpl.start) when a flag is still set.
     */
    internal suspend fun restoreInterruptedCycles() {
        if (graph.prefs.getBoolean(KEY_RESTORE_DATA, false)) {
            graph.log.w(TAG, "A mobile data cycle was interrupted; turning mobile data back on")
            restoreData()
        }
        if (graph.prefs.getBoolean(KEY_RESTORE_AIRPLANE, false)) {
            graph.log.w(TAG, "An airplane cycle or radio rest was interrupted; switching airplane mode off")
            restoreAirplane()
        }
    }

    private suspend fun restoreData(): ActionResult =
        setMobileData(true).also { if (it.ok) markRestore(KEY_RESTORE_DATA, false) }

    private suspend fun restoreAirplane(): ActionResult =
        setAirplane(false).also { if (it.ok) markRestore(KEY_RESTORE_AIRPLANE, false) }

    private fun markRestore(key: String, pending: Boolean) {
        if (graph.prefs.getBoolean(key, false) != pending) graph.prefs.putBoolean(key, pending)
    }

    // --- state readers used by the executor (undo actions) --------------------------------------

    /** "rndis" / "ncm" while USB tethering runs, "none" when it is known to be off, null when unknown. */
    internal suspend fun currentUsbFunction(): String? {
        val tether = graph.telemetry.tether.value
        when (tether?.usbFunction) {
            UsbFunction.RNDIS -> return "rndis"
            UsbFunction.NCM -> return "ncm"
            else -> Unit
        }
        if (tether?.usbTetherOn == false) return USB_FUNCTION_NONE
        if (!rooted()) return null
        val r = shell.run(ControlCommands.USB_GET_FUNCTIONS, PROBE_TIMEOUT_MS)
        if (!r.ok) return null
        val text = (r.out + " " + r.err).lowercase()
        return when {
            "ncm" in text -> "ncm"
            "rndis" in text -> "rndis"
            else -> USB_FUNCTION_NONE
        }
    }

    /** The current network mode (allowed types), else the last one the Agent set, else null. */
    internal suspend fun currentNetworkMode(): NetworkMode? {
        if (profile.networkMode.isEmpty() && rooted()) {
            val r = shell.run(ControlCommands.getAllowedNetworkTypes(defaultDataSlot()), PROBE_TIMEOUT_MS)
            if (r.ok) {
                NetworkTypes.parseAllowed(r.out)?.let(NetworkTypes::modeOf)?.let { return it }
            }
        }
        return NetworkMode.fromWire(graph.prefs.getString(KEY_LAST_NETWORK_MODE))
    }

    // --- capability probe -----------------------------------------------------------------------

    override suspend fun probe(name: String): CapabilityEntry = try {
        probeCommand(name)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        graph.log.w(TAG, "Capability probe of $name failed", e)
        unsupported("probe failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun probeCommand(name: String): CapabilityEntry {
        when (name) {
            CommandNames.REFRESH, CommandNames.CAPABILITY_PROBE -> return supported("agent")
            CommandNames.WATCHDOG_SIMULATE -> return supported("watchdog")
            CommandNames.SPEED_TEST, CommandNames.FINDER_SPOT_TEST,
            CommandNames.FINDER_SET_BASELINE, CommandNames.FINDER_DELETE_SPOT,
            -> return supported("finder")
            CommandNames.SEND_SMS -> return smsCapability()
            CommandNames.USSD -> return ussdCapability()
            CommandNames.CHARGING, CommandNames.CHARGE_LIMIT -> return graph.charge.probe()
        }
        if (!COMMANDS.containsKey(name)) return unsupported("unknown command")
        if (!rooted()) return unsupported("root not available")
        return when (name) {
            CommandNames.MOBILE_DATA, CommandNames.DATA_CYCLE -> viaProfileOr(CommandNames.MOBILE_DATA) {
                if (svcHas("data")) supported("svc data") else unsupported("'svc data' not found")
            }
            CommandNames.AIRPLANE_MODE, CommandNames.AIRPLANE_CYCLE -> viaProfileOr(CommandNames.AIRPLANE_MODE) {
                if (helpText("cmd connectivity help").contains("airplane-mode")) {
                    supported("cmd connectivity airplane-mode")
                } else {
                    supported("settings airplane_mode_on + AIRPLANE_MODE broadcast", "fallback (no 'cmd connectivity airplane-mode')")
                }
            }
            CommandNames.USB_TETHERING -> viaProfileOr(CommandNames.USB_TETHERING) {
                if (svcUsbHasSetFunctions()) {
                    supported("svc usb setFunctions ${resolveUsbFunction(null)}")
                } else {
                    unsupported("'svc usb setFunctions' not found")
                }
            }
            CommandNames.HOTSPOT -> viaProfileOr(CommandNames.HOTSPOT) {
                if (helpText("cmd wifi help").contains("start-softap")) {
                    supported("cmd wifi start-softap")
                } else {
                    unsupported("'cmd wifi start-softap' not found (Android 11+); add a profile override")
                }
            }
            CommandNames.WIFI -> viaProfileOr(CommandNames.WIFI) {
                when {
                    svcHas("wifi") -> supported("svc wifi")
                    helpText("cmd wifi help").contains("set-wifi-enabled") -> supported("cmd wifi set-wifi-enabled")
                    else -> unsupported("no 'svc wifi' or 'cmd wifi set-wifi-enabled'")
                }
            }
            CommandNames.BLUETOOTH -> {
                if (!hasFeature(PackageManager.FEATURE_BLUETOOTH)) {
                    unsupported("no Bluetooth hardware")
                } else {
                    viaProfileOr(CommandNames.BLUETOOTH) {
                        when {
                            svcHas("bluetooth") -> supported("svc bluetooth")
                            helpText("cmd bluetooth_manager help").contains("enable") -> supported("cmd bluetooth_manager")
                            else -> unsupported("no 'svc bluetooth' or 'cmd bluetooth_manager'")
                        }
                    }
                }
            }
            CommandNames.NETWORK_MODE -> viaProfileOr(CommandNames.NETWORK_MODE) {
                if (helpText("cmd phone help").contains("set-allowed-network-types-for-users")) {
                    supported("cmd phone set-allowed-network-types-for-users")
                } else {
                    unsupported("'cmd phone set-allowed-network-types-for-users' not found (Android 12+); add a profile override")
                }
            }
            CommandNames.REBOOT -> viaProfileOr(CommandNames.REBOOT) {
                if (svcHas("power") || binaryExists("reboot")) {
                    val s = graph.settings.current.watchdog
                    supported("svc power reboot", "at most ${s.maxReboots} per ${formatHours(s.rebootWindowH)} h")
                } else {
                    unsupported("'svc power' not found")
                }
            }
            CommandNames.POWER_OFF -> {
                val mechanism = profile.commandTemplates(CommandNames.POWER_OFF).isNotEmpty() || svcHas("power")
                val allowed = graph.settings.current.commands.allowPowerOff
                CapabilityEntry(
                    supported = mechanism && allowed,
                    method = if (mechanism) ControlCommands.SHUTDOWN.first() else null,
                    note = when {
                        !mechanism -> "'svc power' not found"
                        !allowed -> "blocked: settings.commands.allow_power_off is false"
                        else -> null
                    },
                )
            }
            CommandNames.SCREEN -> viaProfileOr(CommandNames.SCREEN) {
                if (binaryExists("input")) supported("input keyevent 223/224") else unsupported("'input' not found")
            }
            CommandNames.BRIGHTNESS -> viaProfileOr(CommandNames.BRIGHTNESS) {
                if (binaryExists("settings")) supported("settings put system screen_brightness") else unsupported("'settings' not found")
            }
            CommandNames.APP_LIST -> if (binaryExists("pm")) supported(METHOD_PM_LIST) else unsupported("'pm' not found")
            CommandNames.APP_DISABLE, CommandNames.APP_ENABLE -> viaProfileOr(name) {
                if (binaryExists("pm")) supported("pm enable / pm disable-user") else unsupported("'pm' not found")
            }
            CommandNames.APP_FORCE_STOP -> viaProfileOr(name) {
                if (binaryExists("am")) supported("am force-stop") else unsupported("'am' not found")
            }
            CommandNames.PROCESS_LIST -> viaProfileOr(name) {
                when {
                    binaryExists("top") -> supported("top -b -n 1")
                    binaryExists("ps") -> supported("ps -A")
                    else -> unsupported("neither 'top' nor 'ps' found")
                }
            }
            CommandNames.SHELL -> supported(graph.shell.rootImpl() ?: METHOD_SU)
            else -> unsupported("unknown command")
        }
    }

    private inline fun viaProfileOr(name: String, builtIn: () -> CapabilityEntry): CapabilityEntry {
        val templates = profile.commandTemplates(name)
        if (templates.isNotEmpty()) return supported("profile: ${templates.first()}", "device profile ${profile.id} override")
        return builtIn()
    }

    private fun smsCapability(): CapabilityEntry {
        val messaging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else {
            hasFeature(PackageManager.FEATURE_TELEPHONY)
        }
        if (!messaging) return unsupported("no telephony messaging on this phone")
        if (!granted(Manifest.permission.SEND_SMS)) return unsupported("$PERMISSION_NOTE SEND_SMS not granted yet")
        return supported("SmsManager")
    }

    private fun ussdCapability(): CapabilityEntry {
        val calling = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
        } else {
            hasFeature(PackageManager.FEATURE_TELEPHONY)
        }
        if (!calling) return unsupported("no telephony on this phone")
        if (!granted(Manifest.permission.CALL_PHONE)) return unsupported("$PERMISSION_NOTE CALL_PHONE not granted yet")
        return supported("TelephonyManager.sendUssdRequest")
    }

    private suspend fun svcHas(sub: String): Boolean {
        val text = helpText("svc help")
        if (!text.contains("Available commands", ignoreCase = true)) return binaryExists("svc")
        return Regex("(?m)^\\s*${Regex.escape(sub)}\\s").containsMatchIn(text)
    }

    private suspend fun svcUsbHasSetFunctions(): Boolean =
        helpText("svc help usb").contains("setFunctions") || helpText("svc usb").contains("setFunctions")

    private suspend fun binaryExists(name: String): Boolean = shell.run("command -v $name", PROBE_TIMEOUT_MS).ok

    /** stdout + stderr of a help command, cached for [HELP_CACHE_MS] (one probe run asks many times). */
    private suspend fun helpText(cmd: String): String = helpMutex.withLock {
        val now = graph.clock.elapsedMs()
        helpCache[cmd]?.let { (at, text) -> if (now - at < HELP_CACHE_MS) return@withLock text }
        val r = shell.run(cmd, PROBE_TIMEOUT_MS)
        val text = r.out + "\n" + r.err
        helpCache[cmd] = now to text
        text
    }

    private fun hasFeature(feature: String): Boolean =
        runCatching { graph.app.packageManager.hasSystemFeature(feature) }.getOrDefault(false)

    private fun granted(permission: String): Boolean =
        graph.app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Runs the profile templates for [name] (rendered with [values]) and then [defaults], in order,
     * until one exits 0 and, when [verify] is given, the phone reports the new state. [verify]
     * returning null means "cannot tell" and counts as success.
     */
    private suspend fun attempt(
        name: String,
        values: Map<String, String>,
        defaults: List<Candidate>,
        timeoutMs: Long = RootShell.DEFAULT_TIMEOUT_MS,
        redactProfile: Boolean = false,
        verify: (suspend () -> Boolean?)? = null,
    ): ActionResult {
        if (!rooted()) return noRoot()
        val candidates = profileCandidates(name, values, redactProfile) + defaults
        if (candidates.isEmpty()) return ActionResult.failure("No method to run $name on this phone")
        val errors = ArrayList<String>()
        for (candidate in candidates) {
            val r = shell.run(candidate.cmd, timeoutMs, logAs = candidate.label)
            if (!r.ok) {
                errors += "${candidate.label}: ${failureText(r)}"
                continue
            }
            if (verify != null && verify() == false) {
                errors += "${candidate.label}: ran, but the phone did not change state"
                continue
            }
            return ActionResult.success(r.out.trim(), candidate.label)
        }
        return ActionResult.failure(errors.joinToString("; ").take(MAX_ERROR_CHARS), method = candidates.last().label)
    }

    private fun profileCandidates(name: String, values: Map<String, String>, redact: Boolean): List<Candidate> =
        profile.commandTemplates(name).mapNotNull { template ->
            val rendered = DeviceProfile.render(template, values)
            if (rendered == null) {
                graph.log.w(TAG, "Profile ${profile.id}: template for $name uses an unknown placeholder: $template")
                null
            } else {
                Candidate(rendered, if (redact) "profile: $template" else rendered)
            }
        }

    /** Waits [seconds] (in the coroutine), then runs [restore]; the restore also runs when cancelled. */
    private suspend fun restoreAfter(seconds: Int, restore: suspend () -> ActionResult): ActionResult {
        try {
            delay(seconds * 1000L)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { restore() }
            throw e
        }
        return withContext(NonCancellable) { restore() }
    }

    /** Polls [read] until it reports [expected] or [timeoutMs] passes. Null when the state is unreadable. */
    private suspend fun awaitState(expected: Boolean, timeoutMs: Long, read: suspend () -> Boolean?): Boolean? {
        val deadline = graph.clock.elapsedMs() + timeoutMs
        var value = read() ?: return null
        while (value != expected) {
            if (graph.clock.elapsedMs() >= deadline) return false
            delay(STATE_POLL_MS)
            value = read() ?: return null
        }
        return true
    }

    private suspend fun awaitTetherIface(timeoutMs: Long): TetherIface? {
        val deadline = graph.clock.elapsedMs() + timeoutMs
        var iface = readTetherIface()
        while (iface == null && graph.clock.elapsedMs() < deadline) {
            delay(TETHER_POLL_MS)
            iface = readTetherIface()
        }
        return iface
    }

    private suspend fun readTetherIface(): TetherIface? {
        val r = shell.run(ControlCommands.IP_ADDR, PROBE_TIMEOUT_MS)
        return if (r.ok) TetherIfaces.find(r.out) else null
    }

    private suspend fun tetherIfaceUp(): Boolean? {
        val r = shell.run(ControlCommands.IP_ADDR, PROBE_TIMEOUT_MS)
        return if (r.ok) TetherIfaces.find(r.out) != null else null
    }

    private fun formatHours(hours: Double): String =
        if (hours == Math.floor(hours)) hours.toLong().toString() else hours.toString()

    private suspend fun verifyNetworkMode(slot: Int, mode: NetworkMode, mask: Long): Boolean? {
        val r = shell.run(ControlCommands.getAllowedNetworkTypes(slot), PROBE_TIMEOUT_MS)
        if (!r.ok) return null
        val current = NetworkTypes.parseAllowed(r.out) ?: return null
        return current == mask || NetworkTypes.modeOf(current) == mode
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE / ACCESS_NETWORK_STATE; failures read as "unknown"
    private fun mobileDataEnabled(): Boolean? = try {
        graph.app.getSystemService(TelephonyManager::class.java)?.isDataEnabled
    } catch (e: Exception) {
        null
    }

    private fun airplaneOn(): Boolean? = try {
        when (AndroidSettings.Global.getInt(graph.app.contentResolver, AndroidSettings.Global.AIRPLANE_MODE_ON, -1)) {
            1 -> true
            0 -> false
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun wifiEnabled(): Boolean? = try {
        graph.app.getSystemService(WifiManager::class.java)?.isWifiEnabled
    } catch (e: Exception) {
        null
    }

    private fun bluetoothOn(): Boolean? = try {
        when (AndroidSettings.Global.getInt(graph.app.contentResolver, AndroidSettings.Global.BLUETOOTH_ON, -1)) {
            -1 -> null
            0 -> false
            else -> true
        }
    } catch (e: Exception) {
        null
    }

    private fun defaultDataSubId(): Int = try {
        SubscriptionManager.getDefaultDataSubscriptionId()
    } catch (e: Exception) {
        SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /** SIM slot of the default data subscription (the `-s` argument of `cmd phone`), 0 when unknown. */
    private fun defaultDataSlot(): Int = try {
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        if (sub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) 0 else SubscriptionManager.getSlotIndex(sub).coerceAtLeast(0)
    } catch (e: Exception) {
        0
    }

    private fun resolveUsbFunction(function: String?): String =
        listOfNotNull(function, profile.usbFunction, graph.settings.current.watchdog.usbFunction)
            .map { it.trim().lowercase() }
            .firstOrNull { it in ArgRules.USB_FUNCTIONS }
            ?: DEFAULT_USB_FUNCTION

    private suspend fun storeRebootEvent(source: CommandSource, reason: String) {
        if (source == CommandSource.WATCHDOG || source == CommandSource.SCHEDULE) {
            graph.events.emitAndStore(
                EventKind.WATCHDOG_ACTION, Severity.WARNING, "Rebooting: $reason",
                jsonObjectOf(
                    "rule" to source.wire, "action" to CommandNames.REBOOT, "step" to 0,
                    "outcome" to "rebooting", "reason" to reason,
                ),
            )
        } else {
            graph.events.emitAndStore(
                EventKind.COMMAND, Severity.WARNING, "Rebooting: $reason",
                jsonObjectOf("name" to CommandNames.REBOOT, "source" to source.wire, "status" to "running", "reason" to reason),
            )
        }
    }

    private suspend fun rooted(): Boolean = shell.isRooted()

    private fun noRoot(): ActionResult = ActionResult.failure("Root is not available")

    private fun failureText(r: ShellResult): String {
        if (r.timedOut) return "timed out"
        val summary = r.summary()
        return if (summary.isBlank()) "exit ${r.code}" else "exit ${r.code}: $summary"
    }

    private fun ActionResult.withSummary(summary: String): ActionResult = if (ok) copy(output = summary) else this

    private fun switchValues(enabled: Boolean): Map<String, String> = mapOf(
        "enabled" to enabled.toString(),
        "state" to (if (enabled) "1" else "0"),
        "action" to (if (enabled) "enable" else "disable"),
    )

    private fun onOff(enabled: Boolean) = if (enabled) "on" else "off"

    private fun supported(method: String, note: String? = null) = CapabilityEntry(supported = true, method = method, note = note)

    private fun unsupported(note: String) = CapabilityEntry(supported = false, note = note)

    companion object {
        private const val TAG = "control"
        private const val KEY_HOTSPOT_SSID = "control.hotspot_ssid"
        private const val KEY_HOTSPOT_PASSWORD = "control.hotspot_password"
        private const val KEY_LAST_NETWORK_MODE = "control.network_mode"
        private const val KEY_RESTORE_DATA = "control.restore_mobile_data"
        private const val KEY_RESTORE_AIRPLANE = "control.restore_airplane_off"
        private const val METHOD_PM_LIST = "pm list packages"
        private const val METHOD_SU = "su"
        private const val USB_FUNCTION_NONE = "none"
        private const val DEFAULT_USB_FUNCTION = "rndis"

        /** Capability notes that start with this are re-probed once permissions may have arrived. */
        internal const val PERMISSION_NOTE = "permission"

        private const val STATE_WAIT_MS = 6_000L
        private const val RADIO_WAIT_MS = 10_000L
        private const val STATE_POLL_MS = 500L
        private const val TETHER_WAIT_MS = 10_000L
        private const val TETHER_POLL_MS = 1_000L
        private const val USB_REENUMERATE_MS = 2_000L
        private const val SOFTAP_TIMEOUT_MS = 30_000L
        private const val POWER_TIMEOUT_MS = 30_000L
        private const val LIST_TIMEOUT_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 10_000L
        private const val HELP_CACHE_MS = 60_000L
        private const val PRE_REBOOT_FLUSH_MS = 2_000L
        private const val MAX_ERROR_CHARS = 1_000
        private const val SHELL_LOG_CHARS = 120

        /** Packages whose loss would cut the phone off (telephony, tethering, root, system UI). */
        private val PROTECTED_PACKAGES = listOf(
            "android",
            "com.android.phone",
            "com.android.systemui",
            "com.android.shell",
            "com.android.providers.telephony",
            "com.android.server.telecom",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.networkstack.tethering",
            "com.google.android.networkstack.tethering",
            "com.android.connectivity.resources",
            "com.google.android.connectivity.resources",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
        )
    }
}
