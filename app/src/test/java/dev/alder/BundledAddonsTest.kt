package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledAddonsTest {
    private fun accountAddon(id: String): Addon = Addon(
        JSONObject().put("transportUrl", "https://example.org/${id}/manifest.json")
            .put("manifest", JSONObject()
                .put("id", id).put("name", id)
                .put("types", org.json.JSONArray().put("series"))
                .put("resources", org.json.JSONArray().put("catalog")))
    )

    @Test fun bundledKitsuExistsWithoutAnAccountInstall() {
        val addons = BundledAddons.withKitsu(emptyList())
        assertEquals(1, addons.size)
        assertEquals(BundledAddons.KITSU_ID, addons.single().id)
        assertTrue(addons.single().locked)
        assertTrue(addons.single().catalogs.any { it.type == "anime" })
        assertTrue(addons.single().serves("meta", "series", "kitsu:1"))
    }

    @Test fun duplicateKitsuFromAccountIsNotDisplayedTwice() {
        val addons = BundledAddons.withKitsu(listOf(accountAddon("cinemeta"), accountAddon(BundledAddons.KITSU_ID)))
        assertEquals(listOf(BundledAddons.KITSU_ID, "cinemeta"), addons.map { it.id })
        assertEquals(listOf("cinemeta"), BundledAddons.accountOnly(addons).map { it.id })
        assertFalse(addons.first().providesStreams)
    }
}
