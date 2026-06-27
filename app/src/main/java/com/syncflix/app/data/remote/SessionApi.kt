package com.syncflix.app.data.remote

import com.syncflix.app.data.model.Movie
import com.syncflix.app.data.model.SessionState
import com.syncflix.app.data.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client HTTP des endpoints de session (`/api/sessions/...`).
 *
 * Volontairement minimal : OkHttp + `org.json` (pas de dépendance de sérialisation). Les appels
 * sont `suspend` et s'exécutent sur le dispatcher IO.
 *
 * Le header `ngrok-skip-browser-warning` saute la page d'avertissement du tunnel ngrok gratuit
 * (inerte sur un autre tunnel / serveur direct), comme côté lecteur.
 */
class SessionApi(
    private val client: OkHttpClient = defaultClient(),
) {
    /** Liste les films disponibles (pour le choix dans l'app). */
    suspend fun listMovies(serverUrl: String): List<Movie> {
        val array = JSONArray(rawBody(serverUrl, Request.Builder().get(), "/api/movies"))
        return (0 until array.length()).map { i -> parseMovie(array.getJSONObject(i)) }
    }

    /** Crée une session sur le film choisi et renvoie son état. */
    suspend fun create(serverUrl: String, movieId: Long): SessionState =
        post(serverUrl, "/api/sessions", body = JSONObject().put("movie_id", movieId).toString())

    /** Rejoint une session existante via son code. */
    suspend fun join(serverUrl: String, code: String): SessionState =
        post(serverUrl, "/api/sessions/${code.trim().uppercase()}/join", body = "{}")

    /** Récupère l'état courant de la session (resync à la (re)connexion du WebSocket). */
    suspend fun getState(serverUrl: String, code: String): SessionState =
        get(serverUrl, "/api/sessions/${code.trim().uppercase()}/state")

    /**
     * Pousse une intention de lecture (Play/Pause/Seek). Le serveur incrémente `seq`, persiste et
     * diffuse l'état sur le canal. [triggeredBy] = id du téléphone émetteur (anti-boucle).
     */
    suspend fun updateState(
        serverUrl: String,
        code: String,
        isPlaying: Boolean,
        positionMs: Long,
        triggeredBy: String,
    ) {
        val body = JSONObject().apply {
            put("is_playing", isPlaying)
            put("position_ms", positionMs)
            put("triggered_by", triggeredBy)
        }.toString()
        post(serverUrl, "/api/sessions/${code.trim().uppercase()}/state", body)
    }

    /**
     * Signe l'abonnement au canal Reverb. Renvoie le jeton `auth` (format Pusher `<clé>:<signature>`)
     * et, pour un canal de présence, le `channel_data` à renvoyer verbatim au `pusher:subscribe`.
     * [userId] = identité (anonyme) du membre de présence. Le secret reste côté serveur.
     */
    suspend fun authChannel(
        serverUrl: String,
        socketId: String,
        channel: String,
        userId: String,
    ): ChannelAuth {
        val body = JSONObject().apply {
            put("socket_id", socketId)
            put("channel_name", channel)
            put("user_id", userId)
        }.toString()
        val json = JSONObject(
            rawBody(serverUrl, Request.Builder().post(body.toRequestBody(JSON)), "/api/broadcasting/auth"),
        )
        return ChannelAuth(
            auth = json.getString("auth"),
            channelData = if (json.has("channel_data")) json.getString("channel_data") else null,
        )
    }

    /** Diffuse une réaction emoji éphémère aux spectateurs de la session. */
    suspend fun react(serverUrl: String, code: String, emoji: String) {
        val body = JSONObject().put("emoji", emoji).toString()
        rawBody(serverUrl, Request.Builder().post(body.toRequestBody(JSON)), "/api/sessions/${code.trim().uppercase()}/reaction")
    }

    private suspend fun post(serverUrl: String, path: String, body: String): SessionState =
        parse(serverUrl, rawBody(serverUrl, Request.Builder().post(body.toRequestBody(JSON)), path))

    private suspend fun get(serverUrl: String, path: String): SessionState =
        parse(serverUrl, rawBody(serverUrl, Request.Builder().get(), path))

    /**
     * Exécute une requête (sur IO) avec les en-têtes communs (`Accept` JSON + saut d'avertissement
     * ngrok) et renvoie le corps brut, ou lève [SessionException] sur un statut d'échec.
     * Point unique pour la politique d'en-têtes et de gestion d'erreurs.
     */
    private suspend fun rawBody(
        serverUrl: String,
        builder: Request.Builder,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        val request = builder
            .url("$base$path")
            .header("Accept", "application/json")
            .header("ngrok-skip-browser-warning", "true")
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SessionException(response.code, raw)
            }
            raw
        }
    }

    private fun parse(serverUrl: String, raw: String): SessionState {
        val json = JSONObject(raw)
        val movie = parseMovie(json.getJSONObject("movie"))
        return SessionState(
            serverUrl = serverUrl.trim().trimEnd('/'),
            code = json.getString("code"),
            movieId = movie.id,
            movieTitle = movie.title,
            streamPath = movie.streamPath,
            subtitles = movie.subtitles,
            isPlaying = json.getBoolean("is_playing"),
            positionMs = json.getLong("position_ms"),
            seq = json.getLong("seq"),
            serverTimestampMs = json.getLong("server_timestamp_ms"),
        )
    }

    /** Parse un objet film (`{id, title, stream_path, subtitles[]}`) — partagé par la liste et l'état. */
    private fun parseMovie(obj: JSONObject): Movie = Movie(
        id = obj.getLong("id"),
        title = obj.getString("title"),
        streamPath = obj.optString("stream_path"),
        subtitles = parseSubtitles(obj.optJSONArray("subtitles")),
    )

    private fun parseSubtitles(array: JSONArray?): List<Subtitle> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Subtitle(
                index = obj.getInt("index"),
                lang = obj.getString("lang"),
                label = obj.getString("label"),
                mime = obj.getString("mime"),
            )
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Timeouts tolérants : le join transite par un tunnel ngrok gratuit parfois lent. */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Réponse d'auth d'abonnement Reverb (cf. SessionApi.authChannel). */
data class ChannelAuth(val auth: String, val channelData: String?)

/** Erreur HTTP renvoyée par le backend de session (code + corps brut). */
class SessionException(val statusCode: Int, val bodyText: String) :
    Exception("HTTP $statusCode")
