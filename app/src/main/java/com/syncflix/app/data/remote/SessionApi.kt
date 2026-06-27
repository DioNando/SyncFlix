package com.syncflix.app.data.remote

import com.syncflix.app.data.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Crée une session sur le serveur et renvoie son état. */
    suspend fun create(serverUrl: String): SessionState =
        post(serverUrl, "/api/sessions", body = "{}")

    /** Rejoint une session existante via son code. */
    suspend fun join(serverUrl: String, code: String): SessionState =
        post(serverUrl, "/api/sessions/${code.trim().uppercase()}/join", body = "{}")

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

    private suspend fun post(serverUrl: String, path: String, body: String): SessionState =
        withContext(Dispatchers.IO) {
            val base = serverUrl.trim().trimEnd('/')
            val request = Request.Builder()
                .url("$base$path")
                .post(body.toRequestBody(JSON))
                .header("Accept", "application/json")
                .header("ngrok-skip-browser-warning", "true")
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SessionException(response.code, raw)
                }
                parse(serverUrl, raw)
            }
        }

    private fun parse(serverUrl: String, raw: String): SessionState {
        val json = JSONObject(raw)
        val movie = json.getJSONObject("movie")
        return SessionState(
            serverUrl = serverUrl.trim().trimEnd('/'),
            code = json.getString("code"),
            movieId = movie.getLong("id"),
            movieTitle = movie.getString("title"),
            isPlaying = json.getBoolean("is_playing"),
            positionMs = json.getLong("position_ms"),
            seq = json.getLong("seq"),
            serverTimestampMs = json.getLong("server_timestamp_ms"),
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Erreur HTTP renvoyée par le backend de session (code + corps brut). */
class SessionException(val statusCode: Int, val bodyText: String) :
    Exception("HTTP $statusCode")
