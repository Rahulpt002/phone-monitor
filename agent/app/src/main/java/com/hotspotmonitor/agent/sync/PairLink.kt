package com.hotspotmonitor.agent.sync

import java.net.URI
import java.net.URLDecoder

/**
 * A parsed pairing deep link `hsm://pair?server=<urlencoded base url>&code=<code>` (QR code from
 * the Dashboard, or `adb shell am start -a android.intent.action.VIEW -d '<link>'`).
 *
 * [server] is the decoded URL as given; PairingClient validates and normalises it.
 */
data class PairLink(val server: String, val code: String) {
    companion object {
        const val SCHEME = "hsm"
        const val HOST = "pair"

        /** `am start ... --ez auto true`: pair at once, without the Pair button (dead screen). */
        const val EXTRA_AUTO = "auto"

        /** `--ez replace true`: let an automatic pairing replace an existing one. */
        const val EXTRA_REPLACE = "replace"

        /** Parses [link]; null when it is not a well-formed pairing link. */
        fun parse(link: String?): PairLink? {
            val text = link?.trim().orEmpty()
            if (text.isEmpty()) return null
            val uri = try {
                URI(text)
            } catch (e: Exception) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            val host = uri.host ?: uri.rawAuthority
            if (!HOST.equals(host, ignoreCase = true)) return null
            val path = uri.rawPath.orEmpty()
            if (path.isNotEmpty() && path != "/") return null
            val params = parseQuery(uri.rawQuery) ?: return null
            val server = params["server"]?.trim().orEmpty()
            val code = params["code"]?.trim().orEmpty()
            if (server.isEmpty() || code.isEmpty()) return null
            return PairLink(server, code)
        }

        /** First value of each key; null when a value is not valid percent-encoding. */
        private fun parseQuery(rawQuery: String?): Map<String, String>? {
            if (rawQuery.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in rawQuery.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val rawKey = if (eq < 0) part else part.substring(0, eq)
                val rawValue = if (eq < 0) "" else part.substring(eq + 1)
                val key = decode(rawKey) ?: return null
                val value = decode(rawValue) ?: return null
                out.putIfAbsent(key, value)
            }
            return out
        }

        private fun decode(text: String): String? = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
