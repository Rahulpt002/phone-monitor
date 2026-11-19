package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.protocol.InvalidArgsException

/**
 * Validation for every command argument that ends up in a shell command or a telephony call.
 * Values that pass are still shell-quoted where they are used (defence in depth): nothing from a
 * command is ever interpolated into a shell line unquoted.
 */
internal object ArgRules {
    val USB_FUNCTIONS: Set<String> = setOf("rndis", "ncm")
    val OFF_SECONDS: IntRange = 1..600
    val BRIGHTNESS: IntRange = 0..255
    val CHARGE_LIMIT: IntRange = 20..100
    val SHELL_TIMEOUT_S: IntRange = 1..300
    const val SHELL_TIMEOUT_DEFAULT_S = 30
    const val SHELL_MAX_CHARS = 8_192
    val SIMULATE_S: IntRange = 10..86_400
    val SPOT_TEST_S: IntRange = 5..600
    const val SMS_BODY_MAX_CHARS = 1_600
    const val SSID_MAX_BYTES = 32
    const val PACKAGE_MAX_CHARS = 255
    const val SPOT_NAME_MAX_CHARS = 64
    const val ORIENTATION_MAX_CHARS = 32

    /** Android package names: dot-separated segments of letters, digits and underscores. */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val PHONE = Regex("^\\+?[0-9]{2,20}$")
    private val PHONE_SEPARATORS = Regex("[\\s().-]")
    private val USSD = Regex("^[*#][0-9*#+]{1,63}$")

    /** WPA2-PSK passphrase: 8..63 printable ASCII characters. */
    private val WPA2_PASSPHRASE = Regex("^[\\x20-\\x7E]{8,63}$")
    private val SPOT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val CONTROL_CHARS = Regex("\\p{Cc}")

    /** Values accepted from a device profile's network_mode map for the default mechanism. */
    private val PROFILE_TOKEN = Regex("^[A-Za-z0-9_|,.:+-]{1,64}$")

    fun packageName(value: String): String {
        val v = value.trim()
        if (v.length > PACKAGE_MAX_CHARS || !PACKAGE.matches(v)) {
            throw InvalidArgsException("'package' is not a valid Android package name")
        }
        return v
    }

    fun ssid(value: String): String {
        if (value.isBlank()) throw InvalidArgsException("'ssid' must not be empty")
        if (CONTROL_CHARS.containsMatchIn(value)) throw InvalidArgsException("'ssid' must not contain control characters")
        if (value.toByteArray(Charsets.UTF_8).size > SSID_MAX_BYTES) {
            throw InvalidArgsException("'ssid' must be at most $SSID_MAX_BYTES bytes")
        }
        return value
    }

    fun wpa2Password(value: String): String {
        if (!WPA2_PASSPHRASE.matches(value)) {
            throw InvalidArgsException("'password' must be 8 to 63 printable ASCII characters")
        }
        return value
    }

    /** Strips spaces, dots, dashes and parentheses, then checks for an optional + and 2..20 digits. */
    fun phoneNumber(value: String): String {
        val v = value.replace(PHONE_SEPARATORS, "")
        if (!PHONE.matches(v)) throw InvalidArgsException("'to' must be a phone number (digits, optional leading +)")
        return v
    }

    fun ussdCode(value: String): String {
        val v = value.trim()
        if (!USSD.matches(v)) throw InvalidArgsException("'code' must be a USSD code such as *123#")
        return v
    }

    fun smsBody(value: String): String {
        if (value.isEmpty()) throw InvalidArgsException("'body' must not be empty")
        if (value.length > SMS_BODY_MAX_CHARS) throw InvalidArgsException("'body' must be at most $SMS_BODY_MAX_CHARS characters")
        return value
    }

    fun shellCommand(value: String): String {
        if (value.isBlank()) throw InvalidArgsException("'cmd' must not be empty")
        if (value.length > SHELL_MAX_CHARS) throw InvalidArgsException("'cmd' must be at most $SHELL_MAX_CHARS characters")
        if (value.indexOf('\u0000') >= 0) throw InvalidArgsException("'cmd' must not contain NUL characters")
        return value
    }

    fun usbFunction(value: String): String {
        val v = value.trim().lowercase()
        if (v !in USB_FUNCTIONS) throw InvalidArgsException("'function' must be one of ${USB_FUNCTIONS.joinToString()}")
        return v
    }

    fun spotId(value: String): String {
        if (!SPOT_ID.matches(value)) throw InvalidArgsException("'spot_id' is not a valid spot id")
        return value
    }

    fun spotName(value: String): String {
        val v = value.trim()
        if (v.isEmpty()) throw InvalidArgsException("'name' must not be empty")
        if (v.length > SPOT_NAME_MAX_CHARS) throw InvalidArgsException("'name' must be at most $SPOT_NAME_MAX_CHARS characters")
        if (CONTROL_CHARS.containsMatchIn(v)) throw InvalidArgsException("'name' must not contain control characters")
        return v
    }

    fun orientation(value: String): String {
        val v = value.trim()
        if (v.length > ORIENTATION_MAX_CHARS) {
            throw InvalidArgsException("'orientation' must be at most $ORIENTATION_MAX_CHARS characters")
        }
        if (CONTROL_CHARS.containsMatchIn(v)) throw InvalidArgsException("'orientation' must not contain control characters")
        return v
    }

    /** Whether a profile-supplied mechanism value may be passed to the built-in command (it is quoted anyway). */
    fun isSafeProfileToken(value: String): Boolean = PROFILE_TOKEN.matches(value)
}
