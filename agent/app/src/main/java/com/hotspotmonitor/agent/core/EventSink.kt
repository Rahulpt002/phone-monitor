package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.AgentEvent
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.EventMsg
import com.hotspotmonitor.agent.protocol.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The one way to report an event (plan 6.2 "Events", protocol `event`).
 *
 * [emit] builds the AgentEvent (id "e_…", ts = Clock.isoNow), logs it, publishes it on [recent] for
 * the local UI and queues it for the outbox. Storage happens on one background consumer, so events
 * reach the outbox (and the Server) in emission order. [emit] never blocks and is safe from any
 * thread, including broadcast receivers.
 */
class EventSink(
    private val outbox: OutboxStore,
    private val clock: Clock,
    private val log: AgentLog,
    scope: CoroutineScope,
) {
    private val _recent = MutableSharedFlow<AgentEvent>(
        replay = RECENT_REPLAY,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The last [RECENT_REPLAY] events of this process, newest last (in-memory only). */
    val recent: SharedFlow<AgentEvent> = _recent.asSharedFlow()

    private val queue = Channel<AgentEvent>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in queue) outbox.enqueue(EventMsg(event = event))
        }
    }

    fun emit(kind: EventKind, severity: Severity, message: String, data: JsonObject? = null): AgentEvent {
        val event = AgentEvent(
            id = Ids.event(),
            ts = clock.isoNow(),
            kind = kind,
            severity = severity,
            message = message,
            data = data,
        )
        publishLocally(event)
        if (queue.trySend(event).isFailure) log.e(TAG, "Event queue closed; dropped ${kind.wire}")
        return event
    }

    /**
     * Like [emit], but returns only after the event is in the outbox. Use it right before an action
     * that may kill the process (reboot), so the event is not lost.
     */
    suspend fun emitAndStore(kind: EventKind, severity: Severity, message: String, data: JsonObject? = null): AgentEvent {
        val event = AgentEvent(Ids.event(), clock.isoNow(), kind, severity, message, data)
        publishLocally(event)
        outbox.enqueue(EventMsg(event = event))
        return event
    }

    private fun publishLocally(event: AgentEvent) {
        val level = when (event.severity) {
            Severity.INFO -> LogLevel.INFO
            Severity.WARNING -> LogLevel.WARN
            Severity.CRITICAL -> LogLevel.ERROR
        }
        log.log(level, TAG, "${event.kind.wire}: ${event.message}", null)
        _recent.tryEmit(event)
    }

    companion object {
        private const val TAG = "event"
        const val RECENT_REPLAY = 100
    }
}
