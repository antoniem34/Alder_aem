package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class CinemetaCatalogTest {
    @Test fun animatedRowsUseOfficialCinemetaEndpoints() {
        assertEquals("top", cinemetaCatalogId("aem-animation-top"))
        assertEquals("imdbRating", cinemetaCatalogId("aem-animation-rated"))
    }

    @Test fun standardCatalogIdsRemainUntouched() {
        assertEquals("top", cinemetaCatalogId("top"))
        assertEquals("year", cinemetaCatalogId("year"))
        assertEquals("imdbRating", cinemetaCatalogId("imdbRating"))
    }
}
