package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishMetadataTest {
    @Test fun translationChangesOnlyVisibleText() {
        val original = Meta(JSONObject()
            .put("id", "tt123")
            .put("type", "series")
            .put("name", "Original")
            .put("description", "Original overview")
            .put("videos", org.json.JSONArray().put(JSONObject()
                .put("id", "tt123:1:1").put("season", 1).put("episode", 1).put("title", "Episode 1"))))
        val localized = original.withSynopsis("Sinopsis traducida")
        assertEquals("tt123", localized.id)
        assertEquals("Original", localized.name)
        assertEquals("Sinopsis traducida", localized.description)
        assertEquals(original.videos.first().id, localized.videos.first().id)
        assertEquals(original.videos.first().season, localized.videos.first().season)
        assertEquals(original.videos.first().episode, localized.videos.first().episode)
        assertEquals("Original", original.name)
    }
}
