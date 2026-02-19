package com.aura.link

import android.content.Context
import android.util.Log

/**
 * Single source of truth for Aura advertising and GATT server
 * Controlled by ProcessLifecycleOwner AND manual broadcast toggle
 */
object AuraCore {
    
    private const val TAG = "AuraCore"
    
    private var context: Context? = null
    private var auraGattService: AuraGattService? = null
    
    // State tracking
    private var gattServerRunning = false
    private var isAppInForeground = false
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        
        // Initialize MatchRequestManager first
        MatchRequestManager.initialize(context)
        
        Log.d(TAG, "AuraCore initialized")
    }
    
    /**
     * Called when app enters foreground (ProcessLifecycleOwner.onStart)
     */
    fun onAppForeground(context: Context) {
        Log.d(TAG, "🌅 App entered foreground")
        isAppInForeground = true
        
        // ALWAYS start GATT server when app is in foreground (independent from visibility)
        startGattServerInternal()
        Log.d(TAG, "📡 GATT server started (independent from visibility)")
        
        // Check if user has advertising enabled
        val userPrefs = UserPreferences(context)
        if (userPrefs.getVisibilityEnabled()) {
            Log.d(TAG, "📡 Visibility enabled - starting advertising")
            AdvertisingController.startIfPossible(context)
        } else {
            Log.d(TAG, "📡 Visibility disabled - GATT server running but not advertising")
        }
    }
    
    /**
     * Called when app enters background (ProcessLifecycleOwner.onStop)
     */
    fun onAppBackground(context: Context) {
        Log.d(TAG, "🌙 App entered background")
        isAppInForeground = false
        
        // Stop advertising when app goes to background
        AdvertisingController.stop(context)
        
        // Stop GATT server when app goes to background
        stopGattServerInternal()
        Log.d(TAG, "📡 Stopped advertising and GATT server on background")
    }
    
    /**
     * Start broadcast (advertising + GATT server)
     * Called when user enables visibility OR app enters foreground with visibility enabled
     */
    fun startBroadcast(context: Context) {
        Log.d(TAG, "🚀 Starting broadcast (advertising + GATT server)")
        
        // ALWAYS start GATT server first (required for match requests)
        startGattServerInternal()
        
        // Then start advertising (with debouncing)
        val advertisingStarted = AdvertisingController.startIfPossible(context)
        Log.d(TAG, "📡 Advertising start result: $advertisingStarted")
    }
    
    /**
     * Stop broadcast (advertising + GATT server)
     * Called when user disables visibility OR app enters background
     */
    fun stopBroadcast(context: Context) {
        Log.d(TAG, "🛑 Stopping broadcast (advertising + GATT server)")
        
        // Stop advertising
        AdvertisingController.stop(context)
        
        // Stop GATT server
        stopGattServerInternal()
    }
    
    /**
     * Called when user toggles visibility setting
     */
    fun onVisibilityChanged(context: Context, enabled: Boolean) {
        Log.d(TAG, "👁️ Visibility changed: enabled=$enabled, appInForeground=$isAppInForeground")
        
        if (enabled && isAppInForeground) {
            // User enabled visibility and app is in foreground - start advertising only
            // GATT server should already be running from onAppForeground
            AdvertisingController.startIfPossible(context)
            Log.d(TAG, "📡 Started advertising (GATT server already running)")
        } else {
            // User disabled visibility - stop advertising only, keep GATT server
            AdvertisingController.stop(context)
            Log.d(TAG, "📡 Stopped advertising (GATT server still running)")
        }
    }
    
    // LEGACY API for backward compatibility (delegates to new methods)
    
    @Deprecated("Use startBroadcast() instead")
    fun startAll(context: Context) = startBroadcast(context)
    
    @Deprecated("Use stopBroadcast() instead")
    fun stopAll(context: Context) = stopBroadcast(context)
    
    // PUBLIC API for Activities to use (delegates to AdvertisingController)
    
    /**
     * Start advertising - delegates to AdvertisingController
     */
    fun startAdvertising(context: Context): Boolean {
        return AdvertisingController.startIfPossible(context.applicationContext)
    }
    
    /**
     * Stop advertising - delegates to AdvertisingController
     */
    fun stopAdvertising(context: Context) {
        AdvertisingController.stop(context.applicationContext)
    }
    
    /**
     * Check if advertising is active - delegates to AdvertisingController
     */
    fun isAdvertising(): Boolean {
        return AdvertisingController.isAdvertising()
    }
    
    /**
     * Bootstrap sequence called after permissions are granted
     * Ensures GATT server + advertising start properly without duplicates
     */
    fun bootstrapAfterPermissions(context: Context) {
        Log.d(TAG, "🚀 BOOTSTRAP: Starting post-permission bootstrap")
        
        val ctx = context.applicationContext
        this.context = ctx
        
        // STEP 1: Always start GATT server first (required for match requests)
        if (!gattServerRunning) {
            startGattServerInternal()
            Log.d(TAG, "BOOTSTRAP: gatt server started")
        } else {
            Log.d(TAG, "BOOTSTRAP: gatt server already running")
        }
        
        // STEP 2: Start advertising ONLY if visibility is enabled
        val userPrefs = UserPreferences(ctx)
        if (userPrefs.getVisibilityEnabled()) {
            if (!AdvertisingController.isAdvertising()) {
                val success = AdvertisingController.startIfPossible(ctx)
                Log.d(TAG, "BOOTSTRAP: advertising started: $success")
            } else {
                Log.d(TAG, "BOOTSTRAP: advertising already running")
            }
        } else {
            Log.d(TAG, "BOOTSTRAP: advertising skipped (visibility disabled)")
        }
        
        Log.d(TAG, "BOOTSTRAP: ready")
        Log.i(TAG, "🚀 BOOTSTRAP_OK: All services started successfully")
    }
    
    /**
     * Ensure GATT server is running (idempotent)
     */
    fun ensureGattServerRunning() {
        val ctx = context
        if (ctx == null) {
            Log.e(TAG, "❌ AuraCore not initialized, cannot start GATT server")
            return
        }
        
        // PERMISSION CHECK: Do not start GATT server without BLUETOOTH_CONNECT permission
        if (!PermissionHelper.hasAdvertisingPermissions(ctx)) {
            Log.e(TAG, "❌ No BLUETOOTH_CONNECT permission - cannot start GATT server")
            return
        }
        
        if (!gattServerRunning) {
            startGattServerInternal()
            Log.d(TAG, "✅ Ensured GATT server is running")
        }
    }
    
    /**
     * Get GATT service instance
     */
    fun getGattService(): AuraGattService? = auraGattService
    
    /**
     * Get user preferences - convenience method for Activities
     */
    fun getUserPreferences(context: Context): UserPreferences {
        return UserPreferences(context)
    }
    
    /**
     * Start GATT server - public wrapper for Activities
     */
    fun startGattServer() {
        if (context == null) {
            Log.w(TAG, "AuraCore not initialized, cannot start GATT server")
            return
        }
        startGattServerInternal()
    }
    
    /**
     * Stop GATT server - public wrapper for Activities
     */
    fun stopGattServer() {
        stopGattServerInternal()
    }
    
    private fun startGattServerInternal() {
        Log.d(TAG, "🔧 Starting GATT server")
        
        val ctx = context
        if (ctx == null) {
            Log.e(TAG, "❌ Context is null, cannot start GATT server")
            return
        }
        
        if (gattServerRunning && auraGattService != null) {
            Log.d(TAG, "GATT server already running")
            return
        }
        
        try {
            // Clean up any existing instance first
            if (auraGattService != null) {
                auraGattService?.cleanup()
                auraGattService = null
            }
            
            auraGattService = AuraGattService(ctx)
            
            // CRITICAL: Set MatchRequestManager as permanent listener
            auraGattService?.setMatchRequestListener(MatchRequestManager)
            Log.d(TAG, "🎯 Set MatchRequestManager as permanent GATT listener")
            
            auraGattService?.startGattServer()
            gattServerRunning = true
            Log.d(TAG, "✅ GATT server started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start GATT server", e)
            gattServerRunning = false
            auraGattService = null
        }
    }
    
    private fun stopGattServerInternal() {
        Log.d(TAG, "🔧 Stopping GATT server")
        
        if (!gattServerRunning && auraGattService == null) {
            Log.d(TAG, "GATT server not running")
            return
        }
        
        try {
            auraGattService?.cleanup()
            auraGattService = null
            gattServerRunning = false
            Log.d(TAG, "✅ GATT server stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception stopping GATT server", e)
            // Force cleanup even if exception occurred
            auraGattService = null
            gattServerRunning = false
        }
    }
    
    // Public getters
    fun isGattServerRunning(): Boolean = gattServerRunning
    fun isAppInForeground(): Boolean = isAppInForeground
}