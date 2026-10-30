package dev.alder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Synopsis-only Spanish localization. Never alters title, episode data, IDs or streams. */
internal object SpanishMetadata {
    private const val TMDB = "https://api.themoviedb.org/3"
    private const val DEEPSEEK = "https://api.deepseek.com/chat/completions"

    fun localized(context: Context, meta: Meta): Meta {
        val original = meta.description?.trim().orEmpty()
        if (original.isBlank()) return meta
        val anime = meta.id.startsWith("kitsu:") || meta.type == "anime"
        val source = if (anime) "deepseek-es-MX-v1" else "tmdb-es-MX-v1"
        if (!anime && (!meta.id.startsWith("tt") || meta.type !in listOf("movie", "series"))) return meta
        val cache = SynopsisCache(context)
        val key = SynopsisCache.key(meta.id, source, original)
        cache.get(key)?.let { return meta.withSynopsis(it) }
        val translated = if (anime) {
            ApiCredentials.get(context, "deepseek")?.let { key -> runCatching { translate(key, original) }.getOrNull() }
        } else {
            ApiCredentials.get(context, "tmdb")?.let { key -> runCatching { tmdb(key, meta.id, meta.type) }.getOrNull() }
        }
        if (translated.isNullOrBlank()) return meta
        cache.put(key, translated)
        return meta.withSynopsis(translated)
    }

    private fun tmdb(key: String, imdbId: String, type: String): String? {
        val find = JSONObject(Http.get("$TMDB/find/$imdbId?api_key=$key&external_source=imdb_id"))
        val movie = type == "movie"
        val result = find.optJSONArray(if (movie) "movie_results" else "tv_results")?.optJSONObject(0)
            ?: return null
        val id = result.optInt("id").takeIf { it > 0 } ?: return null
        val media = if (movie) "movie" else "tv"
        return JSONObject(Http.get("$TMDB/$media/$id?api_key=$key&language=es-MX"))
            .optString("overview").trim().ifEmpty { null }
    }

    private fun translate(apiKey: String, original: String): String? {
        val connection = URL(DEEPSEEK).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            val request = JSONObject()
                .put("model", "deepseek-flash")
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("max_tokens", 1200)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content",
                        "Traduce únicamente esta sinopsis de anime al español latino neutro. " +
                        "Conserva nombres propios, no agregues información ni explicaciones. Devuelve solo la traducción."))
                    .put(JSONObject().put("role", "user").put("content", original.take(5000))))
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.trim()
                ?.takeIf { it.isNotEmpty() && it.length < 10000 }
        } finally { connection.disconnect() }
    }
}
