package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.root.ChargeControlPath
import com.hotspotmonitor.agent.root.RootShell
import com.hotspotmonitor.agent.root.ShellResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeProbeTest {
    /** A rooted phone's sysfs: path -> (mode output, value). Writes succeed unless listed in [rejectWrites]. */
    private class FakeSysfs(
        private val files: Map<String, Pair<String, String>>,
        private val rejectWrites: Set<String> = emptySet(),
    ) : RootShell {
        val commands = ArrayList<String>()
        val writes = ArrayList<Pair<String, String>>()
        override val rooted: StateFlow<Boolean?> = MutableStateFlow(true)
        override suspend fun isRooted() = true
        override suspend fun rootImpl() = "magisk 27.0"

        override suspend fun run(cmd: String, timeoutMs: Long, logAs: String?, quiet: Boolean): ShellResult {
            commands += cmd
            // Root's `test -w` is always true, so a correct probe must never depend on it.
            if (cmd.contains("test -w") || cmd.contains("[ -w")) return ShellResult(0, "", "", 1)
            val path = Regex("'([^']*)'\\s*$").find(cmd)?.groupValues?.get(1) ?: return ShellResult(1, "", "bad", 1)
            val file = files[path] ?: return ShellResult(1, "", "No such file or directory", 1)
            return when {
                cmd.startsWith("stat ") -> ShellResult(0, file.first, "", 1)
                cmd.startsWith("cat ") -> ShellResult(0, file.second + "\n", "", 1)
                cmd.startsWith("echo ") -> {
                    val value = Regex("^echo '([^']*)' >").find(cmd)!!.groupValues[1]
                    writes += path to value
                    if (path in rejectWrites) ShellResult(1, "", "write error: Invalid argument", 1) else ShellResult(0, "", "", 1)
                }
                else -> ShellResult(127, "", "unexpected", 1)
            }
        }
    }

    private val slate = ChargeControlPath("/sys/class/power_supply/battery/batt_slate_mode", on = "0", off = "1")
    private val inputSuspend = ChargeControlPath("/sys/class/power_supply/battery/input_suspend", on = "0", off = "1")
    private val stopLevel = ChargeControlPath("/sys/devices/platform/google,charger/charge_stop_level", on = "100", off = "5")

    @Test
    fun parsesStatAndLsModes() {
        assertEquals(0b110_100_100, ChargeProbe.parseMode("644"))
        assertEquals(0b100_100_100, ChargeProbe.parseMode("444\n"))
        assertEquals(0b110_110_100, ChargeProbe.parseMode("0664"))
        assertEquals(0b111_101_101, ChargeProbe.parseMode("4755"))
        assertEquals(0b110_100_100, ChargeProbe.parseMode("-rw-r--r-- 1 root root 4096 2026-11-19 10:00 /sys/x"))
        assertEquals(0b100_100_100, ChargeProbe.parseMode("-r--r--r-- 1 system system 4096 2026-11-19 10:00 /sys/x"))
        assertEquals(0b010_000_000, ChargeProbe.parseMode("--w------- 1 root root 0 /proc/x"))
        assertEquals(0b111_100_100, ChargeProbe.parseMode("-rwsr-Sr-- 1 root root 0 /x"))
        assertNull(ChargeProbe.parseMode(""))
        assertNull(ChargeProbe.parseMode("stat: unknown option"))
        assertTrue(ChargeProbe.ownerWritable(ChargeProbe.parseMode("644")!!))
        assertFalse(ChargeProbe.ownerWritable(ChargeProbe.parseMode("444")!!))
        assertFalse("group write alone is not the owner bit", ChargeProbe.ownerWritable(ChargeProbe.parseMode("464")!!))
        assertEquals("644", ChargeProbe.modeText(ChargeProbe.parseMode("644")!!))
    }

    @Test
    fun writeBackOnlyForTheSwitchOwnValues() {
        assertEquals("0", ChargeProbe.writeBackValue("0", slate))
        assertEquals("1", ChargeProbe.writeBackValue(" 1\n", slate))
        assertNull(ChargeProbe.writeBackValue("80", stopLevel))
        assertEquals("100", ChargeProbe.writeBackValue("100", stopLevel))
        assertNull(ChargeProbe.writeBackValue(null, slate))
    }

    @Test
    fun readOnlyAttributeIsSkippedEvenThoughRootCanOpenIt() = runBlocking {
        val shell = FakeSysfs(mapOf(slate.path to ("444" to "0"), inputSuspend.path to ("644" to "0")))
        val choice = ChargeProbe.choosePath(listOf(slate, inputSuspend), shell)
        assertEquals(inputSuspend, choice.path)
        assertTrue(shell.commands.none { it.contains("test -w") })
        assertEquals("only the chosen path gets the harmless write-back", listOf(inputSuspend.path to "0"), shell.writes)
    }

    @Test
    fun missingPathsAreSkippedAndLsOutputIsUnderstood() = runBlocking {
        val shell = FakeSysfs(mapOf(inputSuspend.path to ("-rw-r--r-- 1 root root 4096 2026-11-19 10:00 x" to "1")))
        val choice = ChargeProbe.choosePath(listOf(slate, inputSuspend), shell)
        assertEquals(inputSuspend, choice.path)
        assertEquals(listOf(inputSuspend.path to "1"), shell.writes)
    }

    @Test
    fun aSwitchThatRejectsWritesIsSkipped() = runBlocking {
        val shell = FakeSysfs(
            mapOf(slate.path to ("664" to "0"), inputSuspend.path to ("644" to "0")),
            rejectWrites = setOf(slate.path),
        )
        val choice = ChargeProbe.choosePath(listOf(slate, inputSuspend), shell)
        assertEquals(inputSuspend, choice.path)
    }

    @Test
    fun levelFileWithAnotherValueIsChosenWithoutWriting() = runBlocking {
        val shell = FakeSysfs(mapOf(stopLevel.path to ("644" to "80")))
        val choice = ChargeProbe.choosePath(listOf(stopLevel), shell)
        assertEquals(stopLevel, choice.path)
        assertTrue(shell.writes.isEmpty())
    }

    @Test
    fun ineffectivePathsAreSkippedAndTheReasonIsReported() = runBlocking {
        val shell = FakeSysfs(mapOf(slate.path to ("644" to "0"), inputSuspend.path to ("444" to "0")))
        val none = ChargeProbe.choosePath(listOf(slate, inputSuspend), shell, skip = setOf(slate.path))
        assertNull(none.path)
        assertTrue(none.note!!, none.note!!.contains("did not stop charging"))
        assertTrue(none.note!!, none.note!!.contains("read-only (mode 444)"))

        val empty = ChargeProbe.choosePath(emptyList(), shell)
        assertNull(empty.path)
        assertEquals("the device profile lists no charge_control path", empty.note)

        val missing = ChargeProbe.choosePath(listOf(stopLevel), shell)
        assertEquals("none of the 1 charge_control paths exists", missing.note)
    }

    @Test
    fun effectVerdicts() {
        val charging = BatterySample(plugged = true, status = 2, currentMa = 900)
        val idle = BatterySample(plugged = true, status = 4, currentMa = -20)
        val discharging = BatterySample(plugged = true, status = 3, currentMa = -400)
        val unplugged = BatterySample(plugged = false, status = 3, currentMa = -400)

        assertEquals(ChargeEffect.NO_EFFECT, ChargeProbe.judgeEffect(List(6) { charging }))
        assertEquals(ChargeEffect.STOPPED, ChargeProbe.judgeEffect(List(6) { idle }))
        assertEquals(ChargeEffect.STOPPED, ChargeProbe.judgeEffect(listOf(discharging, idle, discharging)))
        assertEquals("a switch that cuts USB input", ChargeEffect.STOPPED, ChargeProbe.judgeEffect(listOf(unplugged, unplugged)))
        assertEquals(ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(listOf(charging, idle, charging)))
        assertEquals(ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(emptyList()))
        assertEquals(
            "unknown current never condemns a switch",
            ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(List(6) { charging.copy(currentMa = null) }),
        )
        assertEquals(
            "a wrong current sign (charging reads negative) never condemns a switch",
            ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(List(6) { charging.copy(currentMa = -900) }),
        )
        assertEquals(ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(List(6) { charging.copy(currentMa = 10) }))
        assertEquals(ChargeEffect.UNKNOWN, ChargeProbe.judgeEffect(List(3) { BatterySample(null, null, null) }))

        assertTrue(ChargeProbe.worthChecking(charging))
        assertFalse(ChargeProbe.worthChecking(idle))
        assertFalse(ChargeProbe.worthChecking(BatterySample(plugged = false, status = 2, currentMa = 0)))
    }
}
