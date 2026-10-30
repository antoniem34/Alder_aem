package dev.alder

import org.json.JSONObject

/**
 * Built-in anime metadata provider. Its public manifest is pinned to the verified
 * Anime Kitsu v0.0.10 resource/catalog declarations so browsing works without
 * writing to the viewer's Stremio account or fetching a manifest at startup.
 * Catalogs and metadata are still fetched live from the addon host.
 */
internal object BundledAddons {
    const val KITSU_ID = "community.anime.kitsu"
    const val KITSU_URL = "https://anime-kitsu.strem.fun/manifest.json"

    private val kitsu by lazy {
        val manifest = JSONObject(
            """
            {
              "id":"community.anime.kitsu",
              "version":"0.0.10",
              "name":"Anime Kitsu",
              "description":"Unofficial Kitsu.io anime catalog addon",
              "resources":["catalog","meta","subtitles"],
              "types":["anime","movie","series"],
              "idPrefixes":["kitsu","mal","anilist","anidb"],
              "catalogs":[
                {"id":"kitsu-anime-trending","name":"Kitsu Trending","type":"anime"},
                {"id":"kitsu-anime-airing","name":"Kitsu Top Airing","type":"anime","extra":[{"name":"genre"},{"name":"skip"}]},
                {"id":"kitsu-anime-popular","name":"Kitsu Most Popular","type":"anime","extra":[{"name":"genre"},{"name":"skip"}]},
                {"id":"kitsu-anime-rating","name":"Kitsu Highest Rated","type":"anime","extra":[{"name":"genre"},{"name":"skip"}]},
                {"id":"kitsu-anime-list","name":"Kitsu","type":"anime","extra":[{"name":"search","isRequired":true},{"name":"lastVideosIds"},{"name":"skip"}]},
                {"id":"kitsu-anime-last-videos","name":"Last videos","type":"series","extra":[{"name":"lastVideosIds","isRequired":true}]}
              ]
            }
            """.trimIndent()
        )
        Addon(
            JSONObject()
                .put("transportUrl", KITSU_URL)
                .put("manifest", manifest)
                .put("flags", JSONObject().put("official", false).put("protected", true))
        )
    }

    /** Keep exactly one Kitsu instance, even if it was also installed through Stremio. */
    fun withKitsu(accountAddons: List<Addon>): List<Addon> =
        listOf(kitsu) + accountAddons.filterNot { it.id == KITSU_ID }

    /** The bundled addon must never be written to or removed from the Stremio account. */
    fun accountOnly(addons: List<Addon>): List<Addon> =
        addons.filterNot { it.id == KITSU_ID }
}
