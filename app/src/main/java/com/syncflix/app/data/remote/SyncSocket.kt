package com.syncflix.app.data.remote

import com.syncflix.app.data.model.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Client WebSocket de synchro, parlant le **protocole Pusher** (implémenté par Laravel Reverb).
 *
 * Cycle : connexion → `pusher:connection_established` → abonnement au canal public → réception des
 * événements `VideoStateUpdated`. Répond aux `pusher:ping` par un `pusher:pong` (keep-alive applicatif).
 *
 * **Reconnexion automatique** : à toute coupure non volontaire (échec/fermeture), retente après un
 * court délai tant que la session est active. À chaque réabonnement, l'appelant resynchronise via
 * `getState` (l'état a pu changer pendant la coupure).
 *
 * Volontairement minimal (OkHttp + `org.json`, pas de SDK Pusher). Pas d'auth : canal **public**
 * `movie-session.{code}` (le code fait office de secret — cf. ARCHITECTURE.md).
 */
class SyncSocket(
    private val wsUrl: String,       // ex. wss://xxxx.ngrok-free.app/app/{clé}
    private val channel: String,     // ex. movie-session.ABC123
    private val scope: CoroutineScope,
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Événements remontés à l'UI / au gestionnaire de synchro. */
    sealed interface Event {
        data object Connected : Event       // socket établi (avant abonnement)
        data object Subscribed : Event      // abonné au canal, prêt à recevoir
        data class State(val state: VideoState) : Event
        data object Disconnected : Event    // coupure (une reconnexion est planifiée)
    }

    private var socket: WebSocket? = null
    private var closedByUser = false

    fun connect(onEvent: (Event) -> Unit) {
        open(onEvent)
    }

    private fun open(onEvent: (Event) -> Unit) {
        val request = Request.Builder()
            .url(wsUrl)
            .header("ngrok-skip-browser-warning", "true")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(webSocket, text, onEvent)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect(onEvent)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect(onEvent)
            }
        })
    }

    private fun scheduleReconnect(onEvent: (Event) -> Unit) {
        if (closedByUser) return
        onEvent(Event.Disconnected)
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!closedByUser) open(onEvent)
        }
    }

    /** Ferme proprement la connexion (1000 = normal closure) et stoppe la reconnexion. */
    fun close() {
        closedByUser = true
        socket?.close(1000, null)
        socket = null
    }

    private fun handle(webSocket: WebSocket, text: String, onEvent: (Event) -> Unit) {
        val message = JSONObject(text)
        when (message.optString("event")) {
            "pusher:connection_established" -> {
                val subscribe = JSONObject().apply {
                    put("event", "pusher:subscribe")
                    put("data", JSONObject().put("channel", channel))
                }
                webSocket.send(subscribe.toString())
                onEvent(Event.Connected)
            }

            "pusher_internal:subscription_succeeded" -> onEvent(Event.Subscribed)

            "pusher:ping" -> webSocket.send(JSONObject().put("event", "pusher:pong").toString())

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

    companion object {
        private const val RECONNECT_DELAY_MS = 2000L
    }
}
