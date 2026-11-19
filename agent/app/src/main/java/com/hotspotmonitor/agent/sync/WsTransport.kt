package com.hotspotmonitor.agent.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/*
 * The WebSocket behind an interface so the sync logic (SyncEngine) can be unit-tested with a fake
 * socket. Production uses OkHttp.
 */

/** Socket callbacks. Called on transport threads; implementations must only hand them over. */
internal interface WsListener {
    fun onOpen()
    fun onMessage(text: String)

    /** The peer started the close handshake with [code]. */
    fun onClosing(code: Int, reason: String)
    fun onClosed(code: Int, reason: String)

    /** Transport failure; [httpStatus] is set when the HTTP upgrade itself was refused. */
    fun onFailure(error: Throwable, httpStatus: Int?)
}

internal interface WsConnection {
    /** Queues a text frame. False when the socket is closing, closed or its queue is full. */
    fun send(text: String): Boolean

    /** Starts a graceful close. False when the socket is already closing or failed. */
    fun close(code: Int, reason: String?): Boolean

    /** Drops the socket immediately (dead or unresponsive peer). */
    fun cancel()
}

internal interface WsTransport {
    /**
     * Opens a socket to [url] with `Authorization: Bearer <token>`. Returns at once; the outcome
     * arrives through [listener]. Throws IllegalArgumentException for a URL it cannot use.
     */
    fun connect(url: String, token: String, listener: WsListener): WsConnection
}

/**
 * OkHttp implementation. [clientProvider] is called once, on the first connect, so building the
 * client (and touching the graph) never happens in a constructor.
 */
internal class OkHttpWsTransport(
    private val userAgent: String,
    clientProvider: () -> OkHttpClient,
) : WsTransport {
    private val client: OkHttpClient by lazy(clientProvider)

    override fun connect(url: String, token: String, listener: WsListener): WsConnection {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .build()
        val socket = client.newWebSocket(request, Adapter(listener))
        return object : WsConnection {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?): Boolean = socket.close(code, reason)
            override fun cancel() = socket.cancel()
        }
    }

    private class Adapter(private val listener: WsListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

        // The protocol is text-only (docs/protocol.md 1); binary frames are ignored.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = listener.onClosing(code, reason)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            listener.onFailure(t, response?.code)
    }
}
