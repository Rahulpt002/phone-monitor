package com.hotspotmonitor.agent.sync

import java.net.URI
import java.net.URLDecoder

/**
 * A parsed pairing deep link `hsm://pair?server=<urlencoded base url>&code=<code>` (QR code from
 * the Dashboard, or `adb shell "am start -n com.hotspotmonitor.agent/.ui.AdbPair -a
 * android.intent.action.VIEW -d '<link>' --ez auto true"`).
 *
 * [server] is the decoded URL as given; PairingClient validates and normalises it.
 */
data class PairLink(val server: String, val code: String) {
    companion object {
        const val SCHEME = "hsm"
        const val HOST = "pair"

        /**
         * `am start -n .../.ui.AdbPair ... --ez auto true`: pair at once, without the Pair button
         * (dead screen). Honoured only through the adb entry ([PairLaunch]).
         */
        const val EXTRA_AUTO = "auto"

        /** `--ez replace true`: let an automatic pairing replace an existing one (adb entry only). */
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

/**
 * How PairActivity treats one launch. The `auto` and `replace` extras count only when the launch
 * came through the activity-alias [ADB_ENTRY], which is guarded by android.permission.DUMP: only the
 * adb shell uid and root hold it, so no app, QR scanner or web page (intent: URL with B.auto=true)
 * can pair or re-pair the phone without a tap on its screen.
 */
enum class PairLaunch {
    /** Show the link and wait for a tap on Pair (plus a confirmation when it replaces a pairing). */
    TAP,

    /** The adb entry asked for `auto` but did not come through it: shown, and a tap is needed. */
    TAP_AUTO_IGNORED,

    /** adb entry with `auto`, but already paired and no `replace`: wait for a tap. */
    TAP_ALREADY_PAIRED,

    /** adb entry with `auto` (and `replace` when already paired): pair now. */
    AUTO,
    ;

    companion object {
        /** Class name of the activity-alias `.ui.AdbPair` (AndroidManifest.xml). */
        const val ADB_ENTRY = "com.hotspotmonitor.agent.ui.AdbPair"

        /** [componentClass] is the launch intent's component class (the alias name when started through it). */
        fun decide(componentClass: String?, auto: Boolean, replace: Boolean, paired: Boolean): PairLaunch = when {
            !auto -> TAP
            componentClass != ADB_ENTRY -> TAP_AUTO_IGNORED
            paired && !replace -> TAP_ALREADY_PAIRED
            else -> AUTO
        }

        /**
         * "Try again" after a failed pairing to [target]: true when it must first ask to confirm
         * replacing the current pairing, because [target] is not the link [approved] as a
         * replacement (the Replace confirmation, or the adb entry). A link delivered while a pairing
         * was running, or one tapped while the phone was still unpaired, was never approved.
         */
        fun retryNeedsConfirm(target: PairLink, approved: PairLink?, paired: Boolean): Boolean =
            paired && target != approved
    }
}
