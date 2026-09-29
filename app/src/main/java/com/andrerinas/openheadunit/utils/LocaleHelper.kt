package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.andrerinas.openheadunit.BuildConfig
import java.util.Locale

object LocaleHelper {

    const val SYSTEM_DEFAULT = ""

    /**
     * Gets available locales that the app has translations for.
     *
     * The list is determined at build time by scanning the res/values-* directories
     * for those containing strings.xml files. This is stored in BuildConfig.AVAILABLE_LOCALES
     * so new translations are automatically included when contributors add values-XX folders.
     *
     * English is always included as it's the default language in the base "values/" folder.
     */
    fun getAvailableLocales(context: Context): List<Locale> {
        return try {
            val localesFromBuildConfig = BuildConfig.AVAILABLE_LOCALES
                .takeIf { it.isNotBlank() }
                ?.split(",")
                ?.mapNotNull { parseLocale(it.trim()) }
                ?: emptyList()

            // Always include English as it's the default language (in values/ folder)
            val allLocales = localesFromBuildConfig + Locale.ENGLISH

            allLocales
                .distinctBy { it.language + "_" + it.country }
                .sortedBy { it.getDisplayName(it).lowercase() }
        } catch (e: Exception) {
            // Fallback: at minimum return English
            listOf(Locale.ENGLISH)
        }
    }

    /**
     * Parse a locale string like "es", "pt-rBR", "zh-rTW" into a Locale object.
     * Handles Android resource qualifier format where region is prefixed with 'r'.
     */
    private fun parseLocale(localeString: String): Locale? {
        if (localeString.isBlank()) return null

        // Android resource locales use format like "pt-rBR" for regional variants
        // Convert to standard format: "pt-rBR" -> "pt-BR"
        val normalized = localeString.replace("-r", "-")
        val parts = normalized.split("-", "_")

        return when (parts.size) {
            1 -> Locale(parts[0])
            2 -> Locale(parts[0], parts[1])
            3 -> Locale(parts[0], parts[1], parts[2])
            else -> null
        }
    }

    /**
     * Converts a Locale to a storage string format.
     */
    fun localeToString(locale: Locale?): String {
        if (locale == null) return SYSTEM_DEFAULT
        return if (locale.country.isNotEmpty()) {
            "${locale.language}-${locale.country}"
        } else {
            locale.language
        }
    }

    /**
     * Converts a stored string back to a Locale.
     */
    fun stringToLocale(localeString: String): Locale? {
        if (localeString.isEmpty()) return null
        return parseLocale(localeString)
    }

    /**
     * Gets the display name for a locale in its own language.
     */
    fun getDisplayName(locale: Locale): String {
        val displayName = locale.getDisplayName(locale)
        // Capitalize first letter
        return displayName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
        }
    }

    private const val KEY = "app-language"
    private const val MIGRATED = "app-language-platform-migrated"
    private const val CN_DEFAULT = "app-language-cn-default"
    private val supportedLanguages = setOf("en", "zh", "ar", "ru", "es")

    private fun preferences(context: Context) =
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    private fun deviceLanguage(context: Context): String {
        @Suppress("DEPRECATION")
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            context.resources.configuration.locales[0]
        } else {
            context.resources.configuration.locale
        }
        return locale.language.lowercase(Locale.ROOT)
    }

    private fun applyCnDefaultIfNeeded(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(CN_DEFAULT, false)) return
        val fallback = if (deviceLanguage(context) in supportedLanguages) null else "zh-CN"
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty && !prefs.contains(KEY) && fallback != null) {
                stringToLocale(fallback)?.let { manager.applicationLocales = android.os.LocaleList(it) }
            }
        } else if (!prefs.contains(KEY) && fallback != null) {
            prefs.edit().putString(KEY, fallback).apply()
        }
        prefs.edit().putBoolean(CN_DEFAULT, true).apply()
    }

    /** Android 13 settings and the in-app picker share the same source of truth. */
    fun preference(context: Context): String {
        applyCnDefaultIfNeeded(context)
        if (Build.VERSION.SDK_INT >= 33) {
            migrate(context)
            val locales = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM_DEFAULT else localeToString(locales[0])
        }
        return preferences(context).getString(KEY, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT
    }

    fun save(context: Context, language: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val locale = stringToLocale(language)
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                if (locale == null) android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList(locale)
            preferences(context).edit().putBoolean(MIGRATED, true).remove(KEY).apply()
        } else {
            preferences(context).edit().putString(KEY, language).apply()
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun migrate(context: Context) {
        val prefs = preferences(context)
        if (prefs.getBoolean(MIGRATED, false)) return
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val previous = stringToLocale(prefs.getString(KEY, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT)
        if (manager.applicationLocales.isEmpty && previous != null) {
            manager.applicationLocales = android.os.LocaleList(previous)
        }
        prefs.edit().putBoolean(MIGRATED, true).remove(KEY).apply()
    }

    fun applyLocale(context: Context, settings: Settings): Context {
        val selected = settings.appLanguage
        if (Build.VERSION.SDK_INT >= 33) return context
        // Do not mutate Locale.getDefault(): returning to System default must restore the system locale.
        @Suppress("DEPRECATION")
        val locale = stringToLocale(selected) ?: android.content.res.Resources.getSystem().configuration.locale
        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLocale(locale)
            config.setLayoutDirection(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return if (Build.VERSION.SDK_INT >= 17) context.createConfigurationContext(config) else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Updates the configuration for the given context.
     * Use this in attachBaseContext of Activities.
     */
    fun wrapContext(context: Context): Context {
        try {
            val isLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                userManager?.isUserUnlocked == false
            } else {
                false
            }
            if (isLocked) {
                return context
            }
            applyCnDefaultIfNeeded(context)
            val settings = Settings(context)
            return applyLocale(context, settings)
        } catch (e: Exception) {
            return context
        }
    }
}
