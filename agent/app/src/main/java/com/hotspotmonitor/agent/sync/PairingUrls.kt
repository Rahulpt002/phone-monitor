package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.protocol.ApiPaths
import java.net.URI
import java.net.URLEncoder

/** Pure URL rules for pairing (docs/protocol.md section 2). No Android dependencies. */
internal object PairingUrls {
    /**
     * Normalises a Server base URL typed by a person or carried by a deep link: adds `https://`
     * when no scheme is given, drops query, fragment and trailing slashes. Null when it is not a
     * usable absolute URL, or when it is plain http and [allowHttp] is false (release builds).
     */
    fun normalizeBaseUrl(raw: String?, allowHttp: Boolean): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && !(scheme == "http" && allowHttp)) return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        if (uri.rawUserInfo != null) return null
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://${host.lowercase()}$port$path"
    }

    /** True for an absolute `wss://` URL (or `ws://` when [allowHttp]). */
    fun isValidWsUrl(raw: String?, allowHttp: Boolean): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "wss" && !(scheme == "ws" && allowHttp)) return false
        return !uri.host.isNullOrBlank()
    }

    /** `https://host/base` -> `wss://host/base/ws/agent` (and http -> ws). */
    fun agentWsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring("https://".length)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring("http://".length)
            else -> trimmed
        }
        return ws + ApiPaths.WS_AGENT
    }

    /** Pairing codes are 8 characters; the Server ignores case, spaces and dashes. */
    fun normalizeCode(raw: String?): String = raw.orEmpty().uppercase().filterNot { it.isWhitespace() || it == '-' }

    /** `hsm://pair?server=<urlencoded base url>&code=<code>` */
    fun deepLink(serverUrl: String, code: String): String =
        "hsm://pair?server=${URLEncoder.encode(serverUrl, "UTF-8")}&code=${URLEncoder.encode(code, "UTF-8")}"

    /**
     * The ADB command for a phone with a dead screen (PairCodeResponse.adb_command). It starts the
     * activity-alias `.ui.AdbPair`, which only adb (shell uid) or root may start, so only this entry
     * honours [auto] (pair without a tap) and [replace] (replace an existing pairing). Double quotes
     * on the host keep the single quotes for the phone's shell, which would otherwise split the
     * link at '&'.
     */
    fun adbCommand(deepLink: String, auto: Boolean = true, replace: Boolean = false): String {
        val extras = buildString {
            if (auto) append(" --ez ${PairLink.EXTRA_AUTO} true")
            if (auto && replace) append(" --ez ${PairLink.EXTRA_REPLACE} true")
        }
        return "adb shell \"am start -n $ADB_COMPONENT -a android.intent.action.VIEW -d '$deepLink'$extras\""
    }

    /** `am start -n` target of [adbCommand]. */
    const val ADB_COMPONENT = "com.hotspotmonitor.agent/.ui.AdbPair"
}
