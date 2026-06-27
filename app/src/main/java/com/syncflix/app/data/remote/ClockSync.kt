package com.syncflix.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Mesure l'offset d'horloge entre le téléphone et le serveur, via `GET /api/time` (cf. ARCHITECTURE.md).
 *
 * Pour chaque échantillon : `offset ≈ server_ts − (t0 + t1) / 2` (correction du RTT). On garde
 * l'échantillon au **plus petit RTT** (le moins bruité par le réseau). Permet ensuite de convertir
 * un `server_timestamp_ms` reçu en temps local : `localCible = serverTs − offset`.
 */
class ClockSync(
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Renvoie l'offset (ms) tel que `serverTime ≈ localTime + offset`, sur [samples] mesures. */
    suspend fun measureOffset(serverUrl: String, samples: Int = 5): Long =
        withContext(Dispatchers.IO) {
            val base = serverUrl.trim().trimEnd('/')
            var bestRtt = Long.MAX_VALUE
            var bestOffset = 0L

            repeat(samples) {
                val t0 = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("$base/api/time")
                    .header("Accept", "application/json")
                    .header("ngrok-skip-browser-warning", "true")
                    .build()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        val t1 = System.currentTimeMillis()
                        val serverTs = JSONObject(response.body?.string().orEmpty())
                            .getLong("server_timestamp_ms")
                        val rtt = t1 - t0
                        if (rtt < bestRtt) {
                            bestRtt = rtt
                            bestOffset = serverTs - (t0 + t1) / 2
                        }
                    }
                }
            }
            bestOffset
        }
}
