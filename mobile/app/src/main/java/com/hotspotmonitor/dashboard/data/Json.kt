package com.hotspotmonitor.dashboard.data

import kotlinx.serialization.json.Json

/** Lenient JSON for everything the Server sends: unknown keys and values are ignored. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
    isLenient = true
}
