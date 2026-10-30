package dev.alder

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Optional Spanish display text. Never changes IMDb/Kitsu IDs, episodes, or stream resolution. */
internal object SpanishMetadata {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val LANGUAGE = "es-MX"
    private val cache = ConcurrentHashMap<String, Pair<String?, String?>>()
    private val unavailable = ConcurrentHashMap.newKeySet<String>()

    /** A TMDB key may be supplied in Gradle BuildConfig later; use the existing TMDB key meanwhile. */
    fun localized(meta: Meta): Meta {
        if (!meta.id.startsWith("tt") || meta.type !in listOf("movie", "series")) return meta
        val key = meta.type + ":" + meta.id
        if (key in unavailable) return meta
        val translated = cache[key] ?: runCatching { fetch(meta.id, meta.type) }.getOrNull()?.also { cache[key] = it }
            ?: return meta
        val (title, overview) = translated
        if (title.isNullOrBlank() && overview.isNullOrBlank()) return meta
        return meta.withDisplayText(title, overview)
    }

    private fun fetch(imdbId: String, type: String): Pair<String?, String?>? {
        val apiKey = Tmdb.apiKey
        val lookup = JSONObject(Http.get("$BASE/find/$imdbId?api_key=$apiKey&external_source=imdb_id", keep = true))
        val media = if (type == "movie") "movie" else "tv"
        val target = lookup.optJSONArray(if (type == "movie") "movie_results" else "tv_results")
            ?.optJSONObject(0) ?: return null
        val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return null
        val data = JSONObject(Http.get("$BASE/$media/$tmdbId?api_key=$apiKey&language=$LANGUAGE", keep = true))
        val title = data.optString(if (media == "movie") "title" else "name").trim().ifEmpty { null }
        val overview = data.optString("overview").trim().ifEmpty { null }
        return title to overview
    }
}
