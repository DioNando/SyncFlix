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
import java.util.concurrent.TimeUnit

/**
 * Client WebSocket de synchro, parlant le **protocole Pusher** (implémenté par Laravel Reverb).
 *
 * Cycle : connexion → `pusher:connection_established` (porte le `socket_id`) → **auth** du canal
 * de présence via [authorize] → `pusher:subscribe` signé (+ `channel_data`) → réception des
 * événements `VideoStateUpdated` / `ReactionSent` et des membres présents.
 * Répond aux `pusher:ping` par un `pusher:pong` (keep-alive applicatif).
 *
 * **Reconnexion automatique** : à toute coupure non volontaire (échec/fermeture), retente après un
 * court délai tant que la session est active. À chaque réabonnement, l'appelant resynchronise via
 * `getState` (l'état a pu changer pendant la coupure).
 *
 * Volontairement minimal (OkHttp + `org.json`, pas de SDK Pusher). Canal de **présence**
 * `presence-movie-session.{code}` : [authorize] obtient signature + `channel_data` du serveur, et le
 * canal sert aussi à compter les spectateurs connectés (salon d'attente).
 */
class SyncSocket(
    private val wsUrl: String,       // ex. wss://xxxx.ngrok-free.app/app/{clé}
    private val channel: String,     // ex. presence-movie-session.ABC123
    private val scope: CoroutineScope,
    // Signe l'abonnement : (socketId, channel) → auth + channel_data. Cf. SessionApi.authChannel.
    private val authorize: suspend (socketId: String, channel: String) -> ChannelAuth,
    private val client: OkHttpClient = defaultClient(),
) {
    /** Événements remontés à l'UI / au gestionnaire de synchro. */
    sealed interface Event {
        data object Connected : Event       // socket établi (avant abonnement)
        data object Subscribed : Event      // abonné au canal, prêt à recevoir
        data class State(val state: VideoState) : Event
        data class Presence(val count: Int) : Event   // nombre de spectateurs connectés
        data class Reaction(val emoji: String) : Event
        data object Disconnected : Event    // coupure (une reconnexion est planifiée)
    }

    private var socket: WebSocket? = null
    private var closedByUser = false
    private var reconnectScheduled = false
    // Membres de présence actuellement connectés (ids opaques) → compteur du salon d'attente.
    private val members = mutableSetOf<String>()

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
        // Idempotent : échec + fermeture (ou auth refusée fermant un socket vivant) peuvent
        // déclencher deux appels rapprochés ; on ne replanifie qu'une fois.
        if (closedByUser || reconnectScheduled) return
        reconnectScheduled = true
        onEvent(Event.Disconnected)
        socket?.cancel()
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectScheduled = false
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
                onEvent(Event.Connected)
                // `data` est une chaîne JSON encodée portant le socket_id (convention Pusher).
                val socketId = JSONObject(message.getString("data")).getString("socket_id")
                // L'auth est un appel réseau → coroutine, puis abonnement signé.
                scope.launch {
                    val auth = runCatching { authorize(socketId, channel) }.getOrNull()
                    if (auth == null) {
                        // Auth refusée (code inexistant, serveur down) : on relance le cycle.
                        scheduleReconnect(onEvent)
                        return@launch
                    }
                    // Une reconnexion a pu remplacer le socket pendant l'appel d'auth (latence réseau) :
                    // ne pas s'abonner sur un socket périmé (le nouveau refera son propre cycle d'auth).
                    if (webSocket !== socket) return@launch
                    val subscribe = JSONObject().apply {
                        put("event", "pusher:subscribe")
                        put(
                            "data",
                            JSONObject()
                                .put("channel", channel)
                                .put("auth", auth.auth)
                                .apply { auth.channelData?.let { put("channel_data", it) } },
                        )
                    }
                    webSocket.send(subscribe.toString())
                }
            }

            "pusher_internal:subscription_succeeded" -> {
                // Présence : `data.presence.ids` = membres déjà connectés à notre arrivée.
                members.clear()
                val presence = JSONObject(message.getString("data")).optJSONObject("presence")
                presence?.optJSONArray("ids")?.let { ids ->
                    for (i in 0 until ids.length()) members.add(ids.getString(i))
                }
                onEvent(Event.Subscribed)
                onEvent(Event.Presence(members.size))
            }

            "pusher_internal:member_added" -> {
                val id = JSONObject(message.getString("data")).optString("user_id")
                if (id.isNotEmpty()) members.add(id)
                onEvent(Event.Presence(members.size))
            }

            "pusher_internal:member_removed" -> {
                val id = JSONObject(message.getString("data")).optString("user_id")
                if (id.isNotEmpty()) members.remove(id)
                onEvent(Event.Presence(members.size))
            }

            "ReactionSent" -> {
                val emoji = JSONObject(message.getString("data")).optString("emoji")
                if (emoji.isNotEmpty()) onEvent(Event.Reaction(emoji))
            }

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

        /**
         * Client par défaut avec **ping WebSocket automatique** (20 s) : maintient la connexion
         * ouverte à travers les NAT/proxies (Tailscale, mobile) et détecte vite une connexion morte
         * → reconnexion rapide. Réduit les déconnexions silencieuses (« kick »).
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
