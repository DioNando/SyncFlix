package com.syncflix.app.data.remote

import com.syncflix.app.data.model.VideoState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Client WebSocket de synchro, parlant le **protocole Pusher** (implémenté par Laravel Reverb).
 *
 * Cycle : connexion → `pusher:connection_established` → on s'abonne au canal public → on reçoit les
 * événements `VideoStateUpdated`. Répond aux `pusher:ping` par un `pusher:pong` (keep-alive applicatif).
 *
 * Volontairement minimal (OkHttp + `org.json`, pas de SDK Pusher). Pas d'auth : canal **public**
 * `movie-session.{code}` (le code fait office de secret — cf. ARCHITECTURE.md).
 */
class SyncSocket(
    private val wsUrl: String,       // ex. wss://xxxx.ngrok-free.app/app/{clé}
    private val channel: String,     // ex. movie-session.ABC123
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Événements remontés à l'UI / au gestionnaire de synchro. */
    sealed interface Event {
        data object Connected : Event       // socket établi (avant abonnement)
        data object Subscribed : Event      // abonné au canal, prêt à recevoir
        data class State(val state: VideoState) : Event
        data class Failure(val message: String) : Event
        data object Closed : Event
    }

    private var socket: WebSocket? = null

    fun connect(onEvent: (Event) -> Unit) {
        val request = Request.Builder()
            .url(wsUrl)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(webSocket, text, onEvent)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onEvent(Event.Failure(t.message ?: "WebSocket error"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onEvent(Event.Closed)
            }
        })
    }

    /** Ferme proprement la connexion (1000 = normal closure). */
    fun close() {
        socket?.close(1000, null)
        socket = null
    }

    private fun handle(webSocket: WebSocket, text: String, onEvent: (Event) -> Unit) {
        val message = JSONObject(text)
        when (message.optString("event")) {
            "pusher:connection_established" -> {
                // Abonnement au canal public (data = { channel }).
                val subscribe = JSONObject().apply {
                    put("event", "pusher:subscribe")
                    put("data", JSONObject().put("channel", channel))
                }
                webSocket.send(subscribe.toString())
                onEvent(Event.Connected)
            }

            "pusher_internal:subscription_succeeded" -> onEvent(Event.Subscribed)

            // Keep-alive applicatif Pusher : on répond au ping par un pong.
            "pusher:ping" -> webSocket.send(JSONObject().put("event", "pusher:pong").toString())

            "pusher:error" -> onEvent(Event.Failure(message.optString("data")))

            "VideoStateUpdated" -> {
                // `data` est une chaîne JSON encodée (convention Pusher).
                val data = JSONObject(message.getString("data"))
                onEvent(
                    Event.State(
                        VideoState(
                            isPlaying = data.getBoolean("is_playing"),
                            positionMs = data.getLong("position_ms"),
                            seq = data.getLong("seq"),
                            serverTimestampMs = data.getLong("server_timestamp_ms"),
                            triggeredBy = data.optString("triggered_by").ifEmpty { null },
                        ),
                    ),
                )
            }
        }
    }
}
