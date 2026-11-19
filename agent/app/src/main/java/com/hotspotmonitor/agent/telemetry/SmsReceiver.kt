package com.hotspotmonitor.agent.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.Ids
import com.hotspotmonitor.agent.protocol.EventKind
import com.hotspotmonitor.agent.protocol.Severity
import com.hotspotmonitor.agent.protocol.SmsDirection
import com.hotspotmonitor.agent.protocol.SmsMsg
import com.hotspotmonitor.agent.protocol.SmsRecord
import com.hotspotmonitor.agent.protocol.jsonObjectOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One received SMS part as the PDU parser returns it. */
internal data class SmsPart(val from: String?, val body: String?)

/** A complete incoming message (multipart bodies joined). */
internal data class IncomingSms(val from: String, val body: String)

internal object SmsAssembler {
    /** Longest body kept (carrier balance messages are short; a runaway multipart is cut). */
    const val MAX_BODY_CHARS = 10_000

    /**
     * Groups the parts of one SMS_RECEIVED broadcast by sender, in arrival order, and joins
     * multipart bodies. Parts without a body (data SMS) are dropped.
     */
    fun assemble(parts: List<SmsPart>): List<IncomingSms> {
        val bySender = LinkedHashMap<String, StringBuilder>()
        for (part in parts) {
            val body = part.body ?: continue
            val from = part.from?.trim()?.ifEmpty { null } ?: UNKNOWN_SENDER
            bySender.getOrPut(from) { StringBuilder() }.append(body)
        }
        return bySender.mapNotNull { (from, body) ->
            body.toString().take(MAX_BODY_CHARS).takeIf { it.isNotEmpty() }?.let { IncomingSms(from, it) }
        }
    }

    const val UNKNOWN_SENDER = "unknown"
}

/**
 * Receives android.provider.Telephony.SMS_RECEIVED (declared in the manifest with permission
 * BROADCAST_SMS) and records each message as a buffered `sms` message (direction in) plus an
 * `sms_received` event with {from, body, at} (plan 6.2 "Messages": carrier balance / plan SMS).
 * The body is never written to the Agent log.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val graph = AgentApp.graph(context)
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not parse an incoming SMS", e)
            return
        }
        val incoming = SmsAssembler.assemble(
            messages.map { m ->
                SmsPart(
                    from = m.displayOriginatingAddress ?: m.originatingAddress,
                    body = m.displayMessageBody ?: m.messageBody,
                )
            },
        )
        if (incoming.isEmpty()) return
        val pending = goAsync()
        graph.appScope.launch {
            try {
                incoming.forEach { record(graph, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.e(TAG, "Could not store an incoming SMS", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun record(graph: AgentGraph, sms: IncomingSms) {
        val at = graph.clock.isoNow()
        graph.log.i(TAG, "SMS received from ${sms.from} (${sms.body.length} chars)")
        graph.outbox.enqueue(
            SmsMsg(sms = SmsRecord(id = Ids.sms(), direction = SmsDirection.IN, peer = sms.from, body = sms.body, at = at)),
        )
        // The event message is logged by EventSink, so it names the sender only.
        graph.events.emit(
            EventKind.SMS_RECEIVED, Severity.INFO, "SMS from ${sms.from}",
            jsonObjectOf("from" to sms.from, "body" to sms.body, "at" to at),
        )
        try {
            graph.sync.kick()
        } catch (e: Exception) {
            graph.log.w(TAG, "Could not wake the sync client", e)
        }
    }

    private companion object {
        const val TAG = "sms"
    }
}
