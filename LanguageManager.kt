package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import java.util.*

/**
 * Language management utility for Aura app
 * Handles language switching and persistence
 */
object LanguageManager {
    
    private const val TAG = "LanguageManager"
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"
    
    // Supported languages with their display names and country codes
    data class SupportedLanguage(
        val code: String,
        val countryCode: String,
        val displayName: String,
        val nativeName: String,
        val flag: String
    )
    
    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("tr", "TR", "Turkish", "Türkçe", "🇹🇷"),
        SupportedLanguage("de", "DE", "German", "Deutsch", "🇩🇪"),
        SupportedLanguage("fr", "FR", "French", "Français", "🇫🇷"),
        SupportedLanguage("es", "ES", "Spanish", "Español", "🇪🇸"),
        SupportedLanguage("en", "GB", "English", "English", "🇬🇧"),
        SupportedLanguage("ja", "JP", "Japanese", "日本語", "🇯🇵"),
        SupportedLanguage("ko", "KR", "Korean", "한국어", "🇰🇷"),
        SupportedLanguage("it", "IT", "Italian", "Italiano", "🇮🇹"),
        SupportedLanguage("zh", "CN", "Chinese", "中文", "🇨🇳")
    )
    
    /**
     * Get saved language preference or system language if supported
     */
    fun getSavedLanguage(context: Context): String {
        val prefs = getPreferences(context)
        val savedLanguage = prefs.getString(KEY_SELECTED_LANGUAGE, null)
        
        return if (savedLanguage != null && isLanguageSupported(savedLanguage)) {
            Log.d(TAG, "🌍 Using saved language: $savedLanguage")
            savedLanguage
        } else {
            // Get system language
            val systemLanguage = getSystemLanguage()
            val supportedLanguage = if (isLanguageSupported(systemLanguage)) {
                systemLanguage
            } else {
                "en" // Default to English
            }
            Log.d(TAG, "🌍 Using system/default language: $supportedLanguage")
            supportedLanguage
        }
    }
    
    /**
     * Save language preference
     */
    fun saveLanguage(context: Context, languageCode: String) {
        if (isLanguageSupported(languageCode)) {
            val prefs = getPreferences(context)
            prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
            Log.d(TAG, "💾 Language saved: $languageCode")
        } else {
            Log.w(TAG, "⚠️ Unsupported language code: $languageCode")
        }
    }
    
    /**
     * Apply language to context
     */
    fun applyLanguage(context: Context, languageCode: String): Context {
        val language = SUPPORTED_LANGUAGES.find { it.code == languageCode }
        if (language == null) {
            Log.w(TAG, "⚠️ Language not found: $languageCode, using default")
            return context
        }
        
        val locale = Locale(language.code, language.countryCode)
        Locale.setDefault(locale)
        
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val newContext = context.createConfigurationContext(config)
            // Also update the original context resources for immediate effect
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            newContext
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }
    
    /**
     * Get current language display name
     */
    fun getCurrentLanguageDisplayName(context: Context): String {
        val currentLanguage = getSavedLanguage(context)
        val language = SUPPORTED_LANGUAGES.find { it.code == currentLanguage }
        return language?.let { "${it.flag} ${it.nativeName}" } ?: "🇬🇧 English"
    }
    
    /**
     * Get system language code
     */
    private fun getSystemLanguage(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Locale.getDefault().language
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault().language
        }
    }
    
    /**
     * Check if language is supported
     */
    private fun isLanguageSupported(languageCode: String): Boolean {
        return SUPPORTED_LANGUAGES.any { it.code == languageCode }
    }
    
    /**
     * Get SharedPreferences for language settings
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Get language by code
     */
    fun getLanguageByCode(code: String): SupportedLanguage? {
        return SUPPORTED_LANGUAGES.find { it.code == code }
    }
    
    /**
     * Auto-detect best language based on system locale
     */
    fun autoDetectLanguage(context: Context): String {
        val systemLanguage = getSystemLanguage()
        val systemCountry = Locale.getDefault().country
        
        Log.d(TAG, "🔍 Auto-detecting language: system=$systemLanguage, country=$systemCountry")
        
        // First try exact match with country
        val exactMatch = SUPPORTED_LANGUAGES.find { 
            it.code == systemLanguage && it.countryCode == systemCountry 
        }
        
        if (exactMatch != null) {
            Log.d(TAG, "✅ Exact match found: ${exactMatch.nativeName}")
            return exactMatch.code
        }
        
        // Then try language match only
        val languageMatch = SUPPORTED_LANGUAGES.find { it.code == systemLanguage }
        if (languageMatch != null) {
            Log.d(TAG, "✅ Language match found: ${languageMatch.nativeName}")
            return languageMatch.code
        }
        
        // Default to English
        Log.d(TAG, "❌ No match found, defaulting to English")
        return "en"
    }
}