package com.virtueson.copilotmaps.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import com.virtueson.copilotmaps.R

/** Localized wrapper words that surround Google's own (already-localized) instruction. */
data class NavPhrases(
    val inPrefix: (String) -> String,   // e.g. { d -> "In $d, " }
    val thenJoiner: String,             // ", then "
    val metersUnit: String,             // "meters"
    val kilometersUnit: String,         // "kilometers"
    val arrived: String,                // "You have arrived."
)

/** Fallback used by unit tests and as the default parameter — English. */
val EnglishNavPhrases = NavPhrases(
    inPrefix = { d -> "In $d, " },
    thenJoiner = ", then ",
    metersUnit = "meters",
    kilometersUnit = "kilometers",
    arrived = "You have arrived.",
)

/** Build [NavPhrases] from string resources for [locale], independent of the system locale. */
fun navPhrases(context: Context, locale: Locale): NavPhrases {
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val res = context.createConfigurationContext(config).resources
    return NavPhrases(
        inPrefix = { d -> res.getString(R.string.nav_in_prefix, d) },
        thenJoiner = res.getString(R.string.nav_then_joiner),
        metersUnit = res.getString(R.string.nav_meters),
        kilometersUnit = res.getString(R.string.nav_kilometers),
        arrived = res.getString(R.string.nav_arrived),
    )
}
