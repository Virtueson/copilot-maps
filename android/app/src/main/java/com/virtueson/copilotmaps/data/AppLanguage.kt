package com.virtueson.copilotmaps.data

import java.util.Locale

/** The two languages the app supports. [tag] is a BCP-47 code. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    INDONESIAN("id");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun forTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }

        /** Indonesian iff the locale's language is Indonesian; English otherwise. */
        fun fromLocale(locale: Locale): AppLanguage =
            if (locale.language == Locale.forLanguageTag("id").language) INDONESIAN else ENGLISH
    }
}
