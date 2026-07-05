package com.virtueson.copilotmaps.data

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted EN/ID choice, the single source of truth for app language. */
class LanguageSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("language", Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(load())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun set(lang: AppLanguage) {
        prefs.edit().putString(KEY, lang.tag).apply()
        _language.value = lang
    }

    private fun load(): AppLanguage {
        val saved = prefs.getString(KEY, null)
        return AppLanguage.forTag(saved) ?: AppLanguage.fromLocale(Locale.getDefault())
    }

    private companion object {
        const val KEY = "app_language"
    }
}
