package com.hotspotmonitor.agent.core

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Small persistent key-value storage. Abstracted from SharedPreferences so SettingsRepo,
 * PairingState and SecureStore can be unit-tested with [MemoryStore].
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(vararg keys: String)
    fun contains(key: String): Boolean
}

/**
 * SharedPreferences-backed store. Writes use commit() so a value is on disk before an action that
 * may kill the process (a reboot command, a pairing switch). Call it from a background thread when
 * that is convenient; the files are tiny.
 */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.commit()
    }

    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).commit()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.commit()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** In-memory store for unit tests. */
class MemoryStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, Any>()

    override fun getString(key: String): String? = map[key] as? String
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun remove(vararg keys: String) {
        keys.forEach { map.remove(it) }
    }

    override fun contains(key: String): Boolean = map.containsKey(key)
}
