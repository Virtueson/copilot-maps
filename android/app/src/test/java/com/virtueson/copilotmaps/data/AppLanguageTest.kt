package com.virtueson.copilotmaps.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test fun tags_are_bcp47() {
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("id", AppLanguage.INDONESIAN.tag)
    }

    @Test fun forTag_maps_known_and_null_for_unknown() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.forTag("id"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forTag("en"))
        assertNull(AppLanguage.forTag("fr"))
        assertNull(AppLanguage.forTag(null))
    }

    @Test fun fromLocale_picks_indonesian_for_id_else_english() {
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromLocale(Locale.forLanguageTag("id")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLocale(Locale.US))
    }
}
