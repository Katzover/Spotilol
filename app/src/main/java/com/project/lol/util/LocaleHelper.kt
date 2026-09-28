package com.project.lol.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language switcher.
 *
 * The app already ships Hebrew resources (values-he), so a Hebrew phone
 * gets Hebrew automatically. This helper only adds the optional manual
 * override picked in Settings -> Appearance -> App Language.
 */
object LocaleHelper {

    const val PREFS_NAME = "spotilol_prefs"
    const val KEY_LANGUAGE = "AppLanguage"

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val HEBREW = "he"

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, code: String) {
        // commit() (synchronous): callers restart/kill the process immediately after,
        // so the value must be on disk before the new process reads it. apply() is
        // async and its pending write is lost if the process dies right after.
        prefs(context).edit().putString(KEY_LANGUAGE, code).commit()
        val locale = if (code == SYSTEM) systemLocale() else localeFor(code)
        Locale.setDefault(locale)
        LocaleList.setDefault(LocaleList(locale))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun localeFor(code: String): Locale =
        when (code) {
            HEBREW -> Locale("he", "IL")
            else -> Locale("en", "US")
        }

    private fun systemLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.ENGLISH else locales[0]
    }

    /** Wraps any context (application, activity, service) with the picked language. */
    fun wrap(base: Context): Context {
        val code = language(base)
        if (code == SYSTEM) {
            return base
        }
        val locale = localeFor(code)
        Locale.setDefault(locale)
        LocaleList.setDefault(LocaleList(locale))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Applies the new language and restarts the screen so every string updates. */
    fun applyAndRestart(activity: Activity, code: String) {
        setLanguage(activity, code)
        activity.recreate()
    }
}
