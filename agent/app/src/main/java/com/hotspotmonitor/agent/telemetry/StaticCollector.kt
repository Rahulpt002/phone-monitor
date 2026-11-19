package com.hotspotmonitor.agent.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.root.RootShell

/** Inputs the hub reads from other modules for [StaticCollector.device]. */
internal data class DeviceExtras(
    val deviceId: String?,
    val agentVersion: String,
    val agentVersionCode: Int,
    val profileId: String,
)

/**
 * Device identity for the `static` tier (plan 6.2, protocol DeviceInfo). Root implementation and
 * IMEI tail are looked up once per process; the IMEI is only reported as its last 4 digits, and
 * only when it can be read and passes the Luhn check.
 */
internal class StaticCollector(
    private val app: Context,
    private val shell: () -> RootShell,
    private val failures: FailureLog,
) {
    @Volatile
    private var rootImpl: String? = null

    @Volatile
    private var imeiTail: String? = null

    @Volatile
    private var imeiChecked = false

    suspend fun device(extras: DeviceExtras): DeviceInfo {
        val root = shell()
        val rooted = failures.soft("root_check") { root.isRooted() } ?: false
        if (rooted && rootImpl == null) rootImpl = failures.soft("root_impl") { root.rootImpl() }
        return DeviceInfo(
            deviceId = extras.deviceId,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            buildId = Build.ID,
            agentVersion = extras.agentVersion,
            agentVersionCode = extras.agentVersionCode,
            profileId = extras.profileId,
            rooted = rooted,
            rootImpl = if (rooted) rootImpl else null,
            imeiTail = imeiTail(root, rooted),
        )
    }

    // getImei needs a privileged permission on Android 10+; the SecurityException is expected and caught.
    @SuppressLint("MissingPermission")
    private suspend fun imeiTail(root: RootShell, rooted: Boolean): String? {
        if (imeiChecked) return imeiTail
        // Apps cannot read the IMEI on Android 10+ without a privileged permission; try anyway
        // (some ROMs allow it), then the phone service through root.
        val viaApi = if (app.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            try {
                app.getSystemService(TelephonyManager::class.java)?.imei?.takeIf { it.length == 15 && luhnValid(it) }
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val tail = viaApi?.takeLast(4) ?: if (rooted) imeiViaRoot(root) else null
        imeiTail = tail
        // Without root the answer may change once root setup completes; retry on the next static.
        imeiChecked = tail != null || rooted
        return tail
    }

    /**
     * IMEI tail through the phone service (IPhoneSubInfo transaction 1 = getDeviceId on Android
     * 10..15). The shell extracts the digits, checks length and Luhn, and prints only the last 4,
     * so the full IMEI never reaches the Agent log (RootShell logs command output).
     */
    private suspend fun imeiViaRoot(root: RootShell): String? {
        val r = root.run(IMEI_TAIL_CMD, 5_000, logAs = "iphonesubinfo imei tail")
        return r.out.trim().takeIf { r.ok && it.length == 4 && it.all { c -> c.isDigit() } }
    }

    private companion object {
        // POSIX sh only (mksh on Android); no `exit`, the root shell may be the shared one.
        const val IMEI_TAIL_CMD = "d=\$(service call iphonesubinfo 1 s16 com.android.shell 2>/dev/null | grep -o \"'[^']*'\" | tr -cd '0-9'); " +
            "if [ \${#d} -eq 15 ]; then s=0; n=0; r=\$d; " +
            "while [ -n \"\$r\" ]; do c=\${r#\${r%?}}; r=\${r%?}; " +
            "if [ \$((n % 2)) -eq 1 ]; then c=\$((c * 2)); if [ \$c -gt 9 ]; then c=\$((c - 9)); fi; fi; " +
            "s=\$((s + c)); n=\$((n + 1)); done; " +
            "if [ \$((s % 10)) -eq 0 ]; then echo \"\${d#???????????}\"; fi; fi"
    }
}
