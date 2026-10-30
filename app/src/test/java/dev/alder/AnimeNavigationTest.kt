package dev.alder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anime is its own catalog type, but uses the same episodic playback controls as series. */
class AnimeNavigationTest {
    @Test fun animeAndSeriesAreEpisodic() {
        assertTrue(isEpisodicType("anime"))
        assertTrue(isEpisodicType("series"))
    }

    @Test fun movieAndLiveAreNotEpisodic() {
        assertFalse(isEpisodicType("movie"))
        assertFalse(isEpisodicType("tv"))
    }
}
