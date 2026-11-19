package com.hotspotmonitor.agent.core

import java.security.SecureRandom

/** Random ids with the prefixes the protocol uses ("e_" event, "c_" command, ...). */
object Ids {
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun event(): String = "e_" + random(12)

    /** Agent-local command (local UI, watchdog); Server commands keep the Server's id. */
    fun command(): String = "c_" + random(12)

    fun spot(): String = "s_" + random(10)

    fun sms(): String = "m_" + random(12)

    fun probe(): String = "p_" + random(10)

    fun random(length: Int): String {
        require(length > 0) { "length must be positive" }
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
