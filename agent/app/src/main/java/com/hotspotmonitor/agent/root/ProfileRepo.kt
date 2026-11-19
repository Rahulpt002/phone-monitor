package com.hotspotmonitor.agent.root

import android.content.res.AssetManager
import com.hotspotmonitor.agent.core.AgentLog
import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.deepMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A profile file: its asset name and raw text. */
data class ProfileFile(val name: String, val text: String)

fun interface ProfileSource {
    fun loadAll(): List<ProfileFile>
}

/** Reads the .json files in `assets/<dir>`. A missing or empty directory yields no files. */
class AssetProfileSource(private val assets: AssetManager, private val dir: String = "profiles") : ProfileSource {
    override fun loadAll(): List<ProfileFile> {
        val names = runCatching { assets.list(dir) }.getOrNull().orEmpty()
        return names.filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            runCatching { assets.open("$dir/$name").bufferedReader().use { it.readText() } }
                .getOrNull()
                ?.let { ProfileFile(name, it) }
        }
    }
}

/**
 * Picks the device profile for this phone and applies the Server's override.
 *
 * Matching: among profiles with a non-empty `match`, those whose criteria all fit
 * Build.MANUFACTURER / Build.MODEL; the most specific wins, ties go to the first file name. With no
 * match: a file with an empty match (generic.json), else [DeviceProfile.GENERIC].
 * Then settings.profile_override is deep-merged over the matched profile's JSON.
 */
class ProfileRepo(
    private val source: ProfileSource,
    private val settings: StateFlow<Settings>,
    private val log: AgentLog,
    private val manufacturer: String,
    private val model: String,
) {
    private data class Loaded(val profile: DeviceProfile, val raw: JsonObject, val file: String)

    private val loaded: List<Loaded> = loadProfiles()

    /** Every profile shipped in the APK (for the Settings screen and diagnostics). */
    val available: List<DeviceProfile> get() = loaded.map { it.profile }

    private val base: Loaded = pickBase()

    /** The matched profile before the Server override. */
    val matched: DeviceProfile get() = base.profile

    private val _profile = MutableStateFlow(resolve(settings.value.profileOverride))

    /** The profile in use (matched + override). Changes when the override changes. */
    val profile: StateFlow<DeviceProfile> = _profile.asStateFlow()

    val current: DeviceProfile get() = _profile.value

    /** Follows settings.profile_override for the life of [scope]. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.map { it.profileOverride }.distinctUntilChanged().collect { override ->
                val next = resolve(override)
                if (next != _profile.value) {
                    log.i(TAG, "Device profile now ${next.id}" + if (override != null) " (with Server override)" else "")
                    _profile.value = next
                }
            }
        }
    }

    internal fun resolve(override: JsonObject?): DeviceProfile {
        if (override == null || override.isEmpty()) return base.profile
        return try {
            AgentJson.decodeFromJsonElement(DeviceProfile.serializer(), base.raw.deepMerge(override))
        } catch (e: Exception) {
            log.w(TAG, "Ignoring invalid profile_override", e)
            base.profile
        }
    }

    private fun loadProfiles(): List<Loaded> {
        val files = try {
            source.loadAll()
        } catch (e: Exception) {
            log.w(TAG, "Could not list device profiles", e)
            emptyList()
        }
        return files.mapNotNull { file ->
            try {
                val raw = AgentJson.parseToJsonElement(file.text).jsonObject
                Loaded(AgentJson.decodeFromJsonElement(DeviceProfile.serializer(), raw), raw, file.name)
            } catch (e: Exception) {
                log.w(TAG, "Skipping invalid profile ${file.name}", e)
                null
            }
        }
    }

    private fun pickBase(): Loaded {
        val specific = loaded.filter { !it.profile.match.isEmpty }.filter { candidate ->
            try {
                candidate.profile.match.matches(manufacturer, model)
            } catch (e: Exception) {
                log.w(TAG, "Profile ${candidate.file} has an invalid model_regex", e)
                false
            }
        }
        val chosen = specific.maxByOrNull { it.profile.match.specificity }
            ?: loaded.firstOrNull { it.profile.match.isEmpty && it.profile.id == DeviceProfile.GENERIC.id }
            ?: loaded.firstOrNull { it.profile.match.isEmpty }
        if (chosen != null) {
            log.i(TAG, "Device profile ${chosen.profile.id} (${chosen.file}) for $manufacturer $model")
            return chosen
        }
        log.i(TAG, "No profile file for $manufacturer $model; using the built-in generic profile")
        val raw = AgentJson.encodeToJsonElement(DeviceProfile.serializer(), DeviceProfile.GENERIC).jsonObject
        return Loaded(DeviceProfile.GENERIC, raw, "<built-in>")
    }

    private companion object {
        const val TAG = "profile"
    }
}
