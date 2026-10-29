package dev.alder

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Enriches movie and series cast metadata with full cast lists, character names, and headshot photos from TMDB.
 */
object Tmdb {
    private const val KEY = "844dba0bfd8f3a4f3799f6130ef9e335"
    private const val BASE = "https://api.themoviedb.org/3"
    private val memoryCache = ConcurrentHashMap<String, List<CastMember>>()

    fun cast(imdbId: String, type: String): List<CastMember> {
        if (!imdbId.startsWith("tt")) return emptyList()
        memoryCache[imdbId]?.let { return it }
        return runCatching {
            val findUrl = "$BASE/find/$imdbId?api_key=$KEY&external_source=imdb_id"
            val findJson = JSONObject(Http.get(findUrl, keep = true))
            val isMovie = type != "series"
            val primaryKey = if (isMovie) "movie_results" else "tv_results"
            val fallbackKey = if (isMovie) "tv_results" else "movie_results"
            val results = findJson.optJSONArray(primaryKey) ?: findJson.optJSONArray(fallbackKey)
            val target = results?.optJSONObject(0) ?: return emptyList()
            val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return emptyList()
            val mediaType = if (target.has("title")) "movie" else "tv"
            val creditsUrl = "$BASE/$mediaType/$tmdbId/credits?api_key=$KEY"
            val creditsJson = JSONObject(Http.get(creditsUrl, keep = true))
            val castArray = creditsJson.optJSONArray("cast") ?: return emptyList()
            val list = mutableListOf<CastMember>()
            for (i in 0 until castArray.length()) {
                val item = castArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                val character = item.optString("character").trim().takeIf { it.isNotBlank() && it != "null" }
                val profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() && it != "null" }
                val photoUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                list.add(CastMember(name, character, photoUrl))
            }
            list.also { memoryCache[imdbId] = it }
        }.getOrDefault(emptyList())
    }
}
