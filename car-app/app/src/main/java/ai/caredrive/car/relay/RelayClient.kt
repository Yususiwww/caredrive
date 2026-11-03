package ai.caredrive.car.relay

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * WebSocket link to the relay server (relay/server.js). Reconnects every 3 s while started.
 * Messages are delivered on the main thread. Protocol: docs/protocol.md.
 */
class RelayClient(
    private val url: String,
    private val onMessage: (JsonObject) -> Unit,
    private val onConnection: (Boolean) -> Unit,
) {
    private val http = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var running = false

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
    }

    fun send(json: String): Boolean = socket?.send(json) ?: false

    private fun connect() {
        if (!running) return
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "relay connected: $url")
                main.post { onConnection(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                main.post { onMessage(obj) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "relay unavailable: ${t.message}")
                retry()
            }
        })
    }

    private fun retry() {
        main.post { onConnection(false) }
        main.postDelayed({ connect() }, 3_000)
    }

    companion object { private const val TAG = "CareDrive" }
}
