package com.syncflix.app.data.remote

import com.syncflix.app.data.model.TmdbMovie
import com.syncflix.app.data.model.WishlistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client HTTP du **catalogue TMDB** (`/api/search`) et de la **watchlist** (`/api/wishlist`).
 *
 * Le serveur proxifie TMDB (le token reste côté serveur). Même style minimal que [SessionApi]
 * (OkHttp + `org.json`, header anti-ngrok, timeouts tolérants), et réutilise [SessionException].
 */
class CatalogApi(
    private val client: OkHttpClient = defaultClient(),
) {
    /** Recherche TMDB (vide si requête vide). */
    suspend fun search(serverUrl: String, query: String): List<TmdbMovie> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val array = JSONArray(rawBody(serverUrl, Request.Builder().get(), "/api/search?q=$q"))
        return (0 until array.length()).map { parseMovie(array.getJSONObject(it)) }
    }

    /** Liste de la watchlist. */
    suspend fun wishlist(serverUrl: String): List<WishlistItem> {
        val array = JSONArray(rawBody(serverUrl, Request.Builder().get(), "/api/wishlist"))
        return (0 until array.length()).map { parseWish(array.getJSONObject(it)) }
    }

    /** Ajoute (ou met à jour) un film dans la watchlist. */
    suspend fun add(serverUrl: String, movie: TmdbMovie): WishlistItem {
        val body = JSONObject().apply {
            put("tmdb_id", movie.tmdbId)
            put("title", movie.title)
            put("year", movie.year)
            put("poster_path", movie.posterPath)
            put("overview", movie.overview)
        }.toString()
        val raw = rawBody(serverUrl, Request.Builder().post(body.toRequestBody(JSON)), "/api/wishlist")
        return parseWish(JSONObject(raw))
    }

    /** Coche / décoche « vu ». */
    suspend fun setWatched(serverUrl: String, id: Long, watched: Boolean): WishlistItem {
        val body = JSONObject().put("watched", watched).toString()
        val raw = rawBody(serverUrl, Request.Builder().patch(body.toRequestBody(JSON)), "/api/wishlist/$id")
        return parseWish(JSONObject(raw))
    }

    /** Retire un film de la watchlist. */
    suspend fun remove(serverUrl: String, id: Long) {
        rawBody(serverUrl, Request.Builder().delete(), "/api/wishlist/$id")
    }

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
            if (!response.isSuccessful) throw SessionException(response.code, raw)
            raw
        }
    }

    private fun parseMovie(o: JSONObject) = TmdbMovie(
        tmdbId = o.getLong("tmdb_id"),
        title = o.getString("title"),
        year = o.stringOrNull("year"),
        posterPath = o.stringOrNull("poster_path"),
        posterUrl = o.stringOrNull("poster_url"),
        overview = o.stringOrNull("overview"),
    )

    private fun parseWish(o: JSONObject) = WishlistItem(
        id = o.getLong("id"),
        tmdbId = o.getLong("tmdb_id"),
        title = o.getString("title"),
        year = o.stringOrNull("year"),
        posterUrl = o.stringOrNull("poster_url"),
        overview = o.stringOrNull("overview"),
        watched = o.optBoolean("watched", false),
        available = o.optBoolean("available", false),
    )

    /** Renvoie null pour une clé absente, JSON `null`, ou chaîne vide (évite le piège `optString`→"null"). */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
