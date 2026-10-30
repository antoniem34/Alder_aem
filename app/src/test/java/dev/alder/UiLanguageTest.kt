package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UiLanguageTest {
    @Test fun spanishInterfaceLabels() {
        assertEquals("Películas", UiLanguage.label("Movies", Locale.forLanguageTag("es-MX")))
        assertEquals("Series animadas", UiLanguage.label("Animated", Locale.forLanguageTag("es-ES")))
        assertEquals("Claves API", UiLanguage.label("API keys", Locale.forLanguageTag("es")))
    }

    @Test fun englishAndUnknownContentAreUnchanged() {
        assertEquals("Movies", UiLanguage.label("Movies", Locale.ENGLISH))
        assertEquals("Jujutsu Kaisen", UiLanguage.label("Jujutsu Kaisen", Locale.forLanguageTag("es-MX")))
    }
}
