package com.aura.link

import android.content.Context
import android.util.Log

/**
 * Global singleton manager for BLE Engine
 * Ensures single instance across the app lifecycle
 * Supports multiple listeners for different activities
 */
object BleEngineManager {
    
    private const val TAG = "BleEngineManager"
    
    private var bleEngine: BleEngine? = null
    private var isInitialized = false
    
    // Multiple listener support
    private val listeners = mutableSetOf<BleEngine.BleEngineListener>()
    
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        bleEngine = BleEngine(context.applicationContext)
        
        // Set up internal listener that broadcasts to all registered listeners
        bleEngine?.setListener(object : BleEngine.BleEngineListener {
            override fun onIncomingMatchRequest(senderHash: String) {
                Log.d(TAG, "📥 Broadcasting match request from: $senderHash to ${listeners.size} listeners")
                listeners.forEach { it.onIncomingMatchRequest(senderHash) }
            }
            
            override fun onMatchAccepted(senderHash: String) {
                Log.d(TAG, "✅ BLE_ENGINE_MANAGER: Broadcasting match accepted from: $senderHash to ${listeners.size} listeners")
                listeners.forEach { 
                    Log.d(TAG, "✅ BLE_ENGINE_MANAGER: Notifying listener: ${it.javaClass.simpleName}")
                    it.onMatchAccepted(senderHash) 
                }
            }
            
            override fun onMatchRejected(senderHash: String) {
                Log.d(TAG, "❌ Broadcasting match rejected from: $senderHash to ${listeners.size} listeners")
                listeners.forEach { it.onMatchRejected(senderHash) }
            }
            
            override fun onChatMessage(senderHash: String, message: String) {
                Log.d(TAG, "💬 Broadcasting chat message from: $senderHash to ${listeners.size} listeners")
                listeners.forEach { it.onChatMessage(senderHash, message) }
            }
            
            override fun onPhotoReceived(senderHash: String, photoBase64: String) {
                Log.d(TAG, "📷 Broadcasting photo received from: $senderHash to ${listeners.size} listeners")
                // Safe broadcasting - handle exceptions to prevent crashes
                listeners.forEach { listener ->
                    try {
                        listener.onPhotoReceived(senderHash, photoBase64)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error broadcasting photo received to ${listener.javaClass.simpleName}", e)
                    }
                }
            }
            
            override fun onPhotoRequested(senderHash: String) {
                Log.d(TAG, "📷 Broadcasting photo request from: $senderHash to ${listeners.size} listeners")
                // Safe broadcasting - handle exceptions to prevent crashes
                listeners.forEach { listener ->
                    try {
                        listener.onPhotoRequested(senderHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error broadcasting photo request to ${listener.javaClass.simpleName}", e)
                    }
                }
            }
            
            override fun onUnmatchReceived(senderHash: String) {
                Log.d(TAG, "💔 Broadcasting unmatch from: $senderHash to ${listeners.size} listeners")
                // Safe broadcasting - handle exceptions to prevent crashes
                listeners.forEach { listener ->
                    try {
                        listener.onUnmatchReceived(senderHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error broadcasting unmatch to ${listener.javaClass.simpleName}", e)
                    }
                }
            }
            
            override fun onBlockReceived(senderHash: String) {
                Log.d(TAG, "🚫 Broadcasting block from: $senderHash to ${listeners.size} listeners")
                // Safe broadcasting - handle exceptions to prevent crashes
                listeners.forEach { listener ->
                    try {
                        listener.onBlockReceived(senderHash)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error broadcasting block to ${listener.javaClass.simpleName}", e)
                    }
                }
            }
        })
        
        isInitialized = true
        
        Log.d(TAG, "BLE Engine Manager initialized with multi-listener support")
    }
    
    fun getInstance(): BleEngine? {
        if (!isInitialized) {
            Log.e(TAG, "BLE Engine Manager not initialized")
            return null
        }
        return bleEngine
    }
    
    fun setCurrentUser(userId: String) {
        bleEngine?.setCurrentUser(userId)
    }
    
    fun startAdvertising() {
        bleEngine?.startAdvertising()
    }
    
    fun stopAdvertising() {
        bleEngine?.stopAdvertising()
    }
    
    fun startScanning() {
        bleEngine?.startScanning()
    }
    
    fun stopScanning() {
        bleEngine?.stopScanning()
    }
    
    fun enqueueChat(targetHashHex: String, message: String) {
        bleEngine?.enqueueChat(targetHashHex, message)
    }
    
    fun enqueueUnmatch(targetHashHex: String) {
        bleEngine?.enqueueUnmatch(targetHashHex)
    }
    
    fun enqueueBlock(targetHashHex: String) {
        bleEngine?.enqueueBlock(targetHashHex)
    }
    
    fun addListener(listener: BleEngine.BleEngineListener) {
        listeners.add(listener)
        Log.d(TAG, "Added listener: ${listener.javaClass.simpleName}, total: ${listeners.size}")
    }
    
    fun removeListener(listener: BleEngine.BleEngineListener) {
        listeners.remove(listener)
        Log.d(TAG, "Removed listener: ${listener.javaClass.simpleName}, total: ${listeners.size}")
    }
    
    fun ensureBackgroundScanning() {
        val instance = getInstance()
        if (instance != null) {
            // RELAXED: Always try to ensure background scanning
            Log.d(TAG, "🔍 Ensuring background scanning for chat/messaging (relaxed mode)")
            instance.ensureBackgroundScanningForChat()
        } else {
            Log.e(TAG, "❌ BleEngine instance is null, cannot ensure background scanning")
        }
    }
    
    fun forceBackgroundScanningAlways() {
        val instance = getInstance()
        if (instance != null) {
            // SIMPLIFIED: Just ensure background scanning without aggressive monitoring
            Log.d(TAG, "🔍 FORCE: Starting background scanning for message reception")
            instance.ensureBackgroundScanningForChat()
        } else {
            Log.e(TAG, "❌ BleEngine instance is null, cannot force background scanning")
        }
    }
    
    fun isDeviceCompatible(): Boolean {
        return getInstance()?.isDeviceCompatible() ?: false
    }
    
    fun isAdvertisingActive(): Boolean {
        return getInstance()?.isAdvertisingActive() ?: false
    }
    
    fun isBackgroundScanningActive(): Boolean {
        return getInstance()?.isBackgroundScanningActive() ?: false
    }
    
    // Premium feature delegation methods
    fun setHighPowerMode(enabled: Boolean) {
        getInstance()?.setHighPowerMode(enabled)
    }
    
    fun setFastScanMode(enabled: Boolean) {
        getInstance()?.setFastScanMode(enabled)
    }
    
    fun setPriorityMode(enabled: Boolean) {
        getInstance()?.setPriorityMode(enabled)
    }
    
    fun setMoodData(moodType: String, message: String) {
        getInstance()?.setMoodData(moodType, message)
    }
    
    fun clearMoodData() {
        getInstance()?.clearMoodData()
    }
    
    // Nearby users flow delegation
    val nearbyUsersFlow get() = getInstance()?.nearbyUsersFlow
    
    fun sendQuickReaction(targetUserHash: String, emoji: String, message: String) {
        getInstance()?.sendQuickReaction(targetUserHash, emoji, message)
    }
    
    fun enqueueMatchRequest(targetHashHex: String) {
        getInstance()?.enqueueMatchRequest(targetHashHex)
    }
    
    fun enqueueMatchResponse(accepted: Boolean, targetHashHex: String) {
        getInstance()?.enqueueMatchResponse(accepted, targetHashHex)
    }
    
    fun enqueuePhoto(targetHashHex: String, photoBase64: String) {
        getInstance()?.enqueuePhoto(targetHashHex, photoBase64)
    }
    
    fun enqueuePhotoRequest(targetHashHex: String) {
        getInstance()?.enqueuePhotoRequest(targetHashHex)
    }
    
    fun cleanup() {
        listeners.clear()
        bleEngine?.cleanup()
        bleEngine = null
        isInitialized = false
        Log.d(TAG, "BLE Engine Manager cleaned up")
    }
}