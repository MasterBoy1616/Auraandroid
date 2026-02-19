package com.aura.link

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.*

class AuraApp : Application() {
    
    companion object {
        private const val TAG = "AuraApp"
        
        /**
         * Apply language from saved preferences or system language
         */
        fun applyLanguage(context: Context): Context {
            val languageCode = LanguageManager.getSavedLanguage(context)
            Log.d(TAG, "🌍 LOCALE: Applying language: $languageCode")
            
            // Force apply language to context
            val updatedContext = LanguageManager.applyLanguage(context, languageCode)
            
            // Also update the application context if this is the application context
            if (context is Application) {
                val config = Configuration(context.resources.configuration)
                val language = LanguageManager.getLanguageByCode(languageCode)
                if (language != null) {
                    val locale = Locale(language.code, language.countryCode)
                    Locale.setDefault(locale)
                    config.setLocale(locale)
                    context.resources.updateConfiguration(config, context.resources.displayMetrics)
                }
            }
            
            return updatedContext
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "AuraApp onCreate() - initializing BLE-only components")
        
        // Apply saved language preference
        applyLanguage(this)
        
        // Initialize BLE Engine Manager
        BleEngineManager.initialize(this)
        
        // Initialize Match Request Manager with BLE Engine
        MatchRequestManager.initialize(this)
        
        // Register lifecycle observer for advertising control
        ProcessLifecycleOwner.get().lifecycle.addObserver(BleLifecycleObserver(this))
        
        Log.d(TAG, "AuraApp onCreate() completed - BLE Engine initialized")
    }
    
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let { applyLanguage(it) })
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        // Handle locale changes
        applyLanguage(this)
        Log.d(TAG, "🌍 LOCALE: Configuration changed, locale updated")
    }
    
    /**
     * BLE lifecycle observer for advertising control
     * Controls advertising based on app foreground/background state AND user visibility setting
     */
    private class BleLifecycleObserver(private val appContext: Application) : DefaultLifecycleObserver {
        
        override fun onStart(owner: LifecycleOwner) {
            // App moved to foreground
            Log.d(TAG, "🟢 App FOREGROUND - starting BLE services")
            
            val userPrefs = UserPreferences(appContext)
            if (userPrefs.getVisibilityEnabled()) {
                BleEngineManager.startAdvertising()
                BleEngineManager.ensureBackgroundScanning()
                Log.d(TAG, "📡 Started advertising AND background scanning (visibility enabled)")
                
                // Background service disabled for now - will be added later
                // AuraBackgroundService.start(appContext)
            }
        }
        
        override fun onStop(owner: LifecycleOwner) {
            // App moved to background - KEEP advertising for background messaging
            Log.d(TAG, "🔴 App BACKGROUND - keeping BLE advertising for background messaging")
            
            // DON'T stop advertising - we need it for background chat
            // BleEngineManager.stopAdvertising()
            
            Log.d(TAG, "📡 BLE services continue in background for messaging")
        }
    }
}