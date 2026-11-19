package com.hotspotmonitor.agent.finder

import com.hotspotmonitor.agent.protocol.FinderMode

/** Local-UI state of the Finder that is not part of FinderLive (owned by FinderEngineImpl). */
data class FinderUiState(
    val walking: Boolean = false,
    val muted: Boolean = false,
    /** The headless toggle (persisted, default on). */
    val headlessEnabled: Boolean = true,
    /** The volume-key reader is running. */
    val headlessListening: Boolean = false,
    val headlessDetail: String? = null,
    /** A Dashboard has the Finder open (rates.mirror). */
    val mirror: Boolean = false,
    /** SPOT or SPEED while a test runs. */
    val runningTest: FinderMode? = null,
    /** Last result line ("Saved ..."), cleared by the next error. */
    val message: String? = null,
    val error: String? = null,
    /** The spot saved by the last spot test (the screen offers "Add photo"). */
    val lastSpotId: String? = null,
)

/** Another test is running, or the watchdog is recovering the connection. */
class FinderBusyException(message: String) : IllegalStateException(message)

/** The test was stopped (stopFinder, headless stop gesture, Cancel button). */
class FinderCancelledException(message: String = "Cancelled") : IllegalStateException(message)

/** A spot test got no usable radio reading (no RSRP). */
class NoSignalException(message: String) : IllegalStateException(message)
