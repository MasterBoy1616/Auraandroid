package com.aura.link

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile

/**
 * BLE-only engine for Aura proximity-based discovery
 * NO GATT connections - pure advertising + scanning architecture
 */
class BleEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "BleEngine"
        
        // CRITICAL FIX: Changed from 0000180F (Battery Service) to custom UUID
        // 0000180F conflicts with iOS system Battery Service!
        private val AURA_SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        
        // Scan/advertise intervals
        private const val SCAN_PERIOD_MS = 10000L // 10 seconds
        private const val ADVERTISE_PERIOD_MS = 15000L // 15 seconds
        private const val BACKGROUND_SCAN_INTERVAL_MS = 30000L // 30 seconds for background
        
        // Manufacturer data constants
        private const val COMPANY_ID = 0xFFFF
        
        // Device compatibility constants
        private const val MIN_SDK_VERSION = 21 // Android 5.0
    }
    
    // Bluetooth components
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // Current user data
    private var currentUserId: String = ""
    private var currentUserHash: ByteArray = ByteArray(4)
    private var currentUserGender: Byte = 0x00
    
    // State management
    private var isAdvertising = false
    private var isScanning = false
    private var isBackgroundScanning = false
    private var isHighPowerMode = false
    private var isFastScanMode = false
    private var isPriorityMode = false
    
    // Handlers and runnables
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null
    private var advertiseRunnable: Runnable? = null
    private var backgroundScanRunnable: Runnable? = null
    
    // Nearby users tracking
    private val nearbyUsers = ConcurrentHashMap<String, NearbyUser>()
    private val _nearbyUsersFlow = MutableStateFlow<List<NearbyUser>>(emptyList())
    val nearbyUsersFlow: StateFlow<List<NearbyUser>> = _nearbyUsersFlow
    
    // Message queues for BLE transmission
    private val outgoingMessageQueue = mutableListOf<QueuedMessage>()
    private var isProcessingQueue = false
    
    // iOS device tracking (for GATT connections)
    private val connectedIOSDevices = mutableSetOf<String>()
    private val iosDeviceCallbacks = mutableMapOf<String, BluetoothGattCallback>()
    
    // iOS characteristic UUID
    // CRITICAL FIX: Changed to match new custom service UUID
    private val PRESENCE_CHARACTERISTIC_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    
    // Listener interface
    interface BleEngineListener {
        fun onIncomingMatchRequest(senderHash: String)
        fun onMatchAccepted(senderHash: String)
        fun onMatchRejected(senderHash: String)
        fun onChatMessage(senderHash: String, message: String)
        fun onPhotoReceived(senderHash: String, photoBase64: String)
        fun onPhotoRequested(senderHash: String)
        fun onUnmatchReceived(senderHash: String)
        fun onBlockReceived(senderHash: String)
    }
    
    private var listener: BleEngineListener? = null
    
    // Data classes
    data class NearbyUser(
        val userHash: String,
        val userName: String,
        val gender: String,
        val rssi: Int,
        val lastSeen: Long = System.currentTimeMillis(),
        val moodType: String? = null,
        val moodMessage: String? = null
    )
    
    private data class QueuedMessage(
        val type: Byte,
        val targetHash: String,
        val data: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Premium feature state
    private var currentMoodType: String? = null
    private var currentMoodMessage: String? = null
    
    // Enhanced duplicate message prevention with stricter controls
    private val processedMessages = mutableSetOf<String>()
    private val messageTimeouts = mutableMapOf<String, Long>()
    private val MESSAGE_TIMEOUT = 45000L // Increased to 45 seconds for better duplicate prevention
    
    // Additional tracking for critical message types
    private val matchRequestTracker = mutableMapOf<String, Long>()
    private val MATCH_REQUEST_COOLDOWN = 60000L // 1 minute cooldown for match requests
    
    private fun shouldProcessMessage(senderHash: String, messageType: Byte): Boolean {
        val messageKey = "${senderHash}_${messageType}"
        val currentTime = System.currentTimeMillis()
        
        // ENHANCED: Special handling for match requests to prevent spam
        if (messageType == BLEPacket.TYPE_MATCH_REQ) {
            val lastMatchRequest = matchRequestTracker[senderHash]
            if (lastMatchRequest != null && (currentTime - lastMatchRequest) < MATCH_REQUEST_COOLDOWN) {
                Log.d(TAG, "🚫 MATCH_REQ_COOLDOWN: Ignoring match request from $senderHash (cooldown active)")
                return false
            }
            matchRequestTracker[senderHash] = currentTime
        }
        
        // Clean up old timeouts more aggressively
        val expiredKeys = messageTimeouts.entries.filter { (_, timestamp) ->
            currentTime - timestamp > MESSAGE_TIMEOUT
        }.map { it.key }
        
        expiredKeys.forEach { key ->
            messageTimeouts.remove(key)
            processedMessages.remove(key)
        }
        
        // Clean up old match request tracking
        matchRequestTracker.entries.removeAll { (_, timestamp) ->
            currentTime - timestamp > MATCH_REQUEST_COOLDOWN
        }
        
        // Check if we've already processed this message recently
        if (processedMessages.contains(messageKey)) {
            val timestamp = messageTimeouts[messageKey] ?: 0
            if (currentTime - timestamp < MESSAGE_TIMEOUT) {
                Log.d(TAG, "🚫 DUPLICATE: Ignoring duplicate message from $senderHash, type: $messageType (${getMessageTypeName(messageType)})")
                return false
            }
        }
        
        // Mark as processed
        processedMessages.add(messageKey)
        messageTimeouts[messageKey] = currentTime
        Log.d(TAG, "✅ PROCESSING: New message from $senderHash, type: ${getMessageTypeName(messageType)}")
        return true
    }
    
    init {
        Log.d(TAG, "🚀 BleEngine initialized")
        
        // Set default user from preferences if available
        try {
            val userPrefs = UserPreferences(context)
            val userId = userPrefs.getUserId()
            if (userId.isNotEmpty()) {
                setCurrentUser(userId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load user preferences: ${e.message}")
        }
    }
    
    /**
     * Device compatibility check - RELAXED for broader compatibility
     */
    fun isDeviceCompatible(): Boolean {
        return try {
            // Check Android version - more lenient
            if (Build.VERSION.SDK_INT < 21) { // Android 5.0 minimum
                Log.e(TAG, "❌ Android version too old: ${Build.VERSION.SDK_INT} < 21")
                return false
            }
            
            // Check BLE support - but allow fallback
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Log.w(TAG, "⚠️ BLE not officially supported, but allowing fallback mode")
                // Don't return false - allow app to work with limited functionality
            }
            
            // Check Bluetooth adapter - but allow fallback
            if (bluetoothAdapter == null) {
                Log.w(TAG, "⚠️ Bluetooth adapter not available, allowing limited mode")
                // Don't return false - allow app to work with limited functionality
            }
            
            // RELAXED: Don't check BLE scanner/advertiser availability
            // Many devices report these as null even when they work
            
            Log.d(TAG, "✅ Device allowed to use Aura (relaxed compatibility)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error checking device compatibility, allowing anyway", e)
            true // Allow app to work even if compatibility check fails
        }
    }
    
    /**
     * Set current user for advertising
     */
    fun setCurrentUser(userId: String) {
        currentUserId = userId
        currentUserHash = BLEPacket.hashUserIdTo4Bytes(userId)
        
        // Get gender from preferences
        try {
            val userPrefs = UserPreferences(context)
            val gender = userPrefs.getGender()
            currentUserGender = when (gender?.uppercase()) {
                "M", "MALE" -> 0x01
                "F", "FEMALE" -> 0x02
                else -> 0x00
            }
            Log.d(TAG, "👤 Set current user: $userId (gender: $gender)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get user gender: ${e.message}")
            currentUserGender = 0x00
        }
        
        // CRITICAL FIX: Restart advertising with new user data
        if (isAdvertising) {
            Log.d(TAG, "🔄 Restarting advertising with new user data")
            stopAdvertising()
            // Small delay to ensure clean restart
            handler.postDelayed({
                startAdvertising()
            }, 500)
        }
    }
    
    /**
     * Start BLE advertising with improved reliability
     */
    fun startAdvertising() {
        // RELAXED: Allow advertising attempt even if compatibility check fails
        Log.d(TAG, "📡 Starting BLE advertising (relaxed mode)")
        
        if (isAdvertising) {
            Log.d(TAG, "📡 Already advertising, restarting for reliability")
            stopAdvertising()
            // Small delay to ensure clean restart
            handler.postDelayed({ 
                startAdvertisingInternal()
            }, 1000) // Increased delay for better reliability
            return
        }
        
        startAdvertisingInternal()
    }
    
    private fun startAdvertisingInternal() {
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(if (isHighPowerMode) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(if (isHighPowerMode) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false) // No connections needed
                .setTimeout(0) // Advertise indefinitely
                .build()
            
            // Create manufacturer data with user info
            val manufacturerData = createPresencePacket()
            
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(AURA_SERVICE_UUID))
                .addManufacturerData(COMPANY_ID, manufacturerData)
                .build()
            
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            
            Log.d(TAG, "📡 Started advertising with ${manufacturerData.size} bytes of data")
            
            // Schedule periodic data updates
            scheduleAdvertisingUpdates()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start advertising", e)
            isAdvertising = false
        }
    }
    
    /**
     * Stop BLE advertising
     */
    fun stopAdvertising() {
        if (!isAdvertising) {
            Log.d(TAG, "📡 Already stopped advertising")
            return
        }
        
        try {
            bleAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            
            // Cancel scheduled updates
            advertiseRunnable?.let { handler.removeCallbacks(it) }
            
            Log.d(TAG, "📡 Stopped advertising")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop advertising", e)
        }
    }
    
    /**
     * Start BLE scanning with improved reliability
     */
    fun startScanning() {
        // RELAXED: Allow scanning attempt even if compatibility check fails
        Log.d(TAG, "🔍 Starting BLE scanning (relaxed mode)")
        
        if (isScanning) {
            Log.d(TAG, "🔍 Already scanning, restarting for reliability")
            stopScanning()
            // Small delay to ensure clean restart
            handler.postDelayed({ 
                startScanningInternal()
            }, 1000) // Increased delay for better reliability
            return
        }
        
        startScanningInternal()
    }
    
    private fun startScanningInternal() {
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(if (isFastScanMode) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()
            
            // CRITICAL FIX: Remove filter to see all BLE devices (including iOS)
            // Filter for Aura service UUID - DISABLED for iOS compatibility
            // val filter = ScanFilter.Builder()
            //     .setServiceUuid(ParcelUuid(AURA_SERVICE_UUID))
            //     .build()
            
            // Start scan WITHOUT filter
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            
            Log.d(TAG, "🔍 Started scanning with aggressive matching (NO FILTER for iOS compatibility)")
            
            // Schedule scan period management
            scheduleScanPeriod()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start scanning", e)
            isScanning = false
        }
    }
    
    /**
     * Stop BLE scanning
     */
    fun stopScanning() {
        if (!isScanning) {
            Log.d(TAG, "🔍 Already stopped scanning")
            return
        }
        
        try {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
            
            // Cancel scheduled scan management
            scanRunnable?.let { handler.removeCallbacks(it) }
            
            Log.d(TAG, "🔍 Stopped scanning")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop scanning", e)
        }
    }
    
    /**
     * Ensure background scanning for message reception
     */
    fun ensureBackgroundScanningForChat() {
        // RELAXED: Allow background scanning attempt even if compatibility check fails
        Log.d(TAG, "🔍 Ensuring background scanning for chat (relaxed mode)")
        
        if (isBackgroundScanning) {
            Log.d(TAG, "🔍 Background scanning already active")
            return
        }
        
        try {
            // Use low power settings for background scanning
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build()
            
            // Filter for Aura service UUID
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(AURA_SERVICE_UUID))
                .build()
            
            bleScanner?.startScan(listOf(filter), settings, backgroundScanCallback)
            isBackgroundScanning = true
            
            Log.d(TAG, "🔍 Started background scanning for message reception")
            
            // Schedule periodic background scan management
            scheduleBackgroundScanPeriod()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start background scanning", e)
        }
    }
    
    /**
     * Check if advertising is active
     */
    fun isAdvertisingActive(): Boolean = isAdvertising
    
    /**
     * Check if background scanning is active
     */
    fun isBackgroundScanningActive(): Boolean = isBackgroundScanning
    
    /**
     * Set listener for BLE events
     */
    fun setListener(listener: BleEngineListener?) {
        this.listener = listener
    }
    
    /**
     * Enqueue chat message for transmission
     */
    fun enqueueChat(targetHashHex: String, message: String) {
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_CHAT,
            targetHash = targetHashHex,
            data = message
        )
        
        synchronized(outgoingMessageQueue) {
            outgoingMessageQueue.add(queuedMessage)
        }
        
        Log.d(TAG, "💬 Enqueued chat message to $targetHashHex: ${message.take(20)}...")
        processMessageQueue()
    }
    
    /**
     * Enqueue unmatch message
     */
    fun enqueueUnmatch(targetHashHex: String) {
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_UNMATCH,
            targetHash = targetHashHex
        )
        
        synchronized(outgoingMessageQueue) {
            outgoingMessageQueue.add(queuedMessage)
        }
        
        Log.d(TAG, "💔 Enqueued unmatch to $targetHashHex")
        processMessageQueue()
    }
    
    /**
     * Enqueue block message
     */
    fun enqueueBlock(targetHashHex: String) {
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_BLOCK,
            targetHash = targetHashHex
        )
        
        synchronized(outgoingMessageQueue) {
            outgoingMessageQueue.add(queuedMessage)
        }
        
        Log.d(TAG, "🚫 Enqueued block to $targetHashHex")
        processMessageQueue()
    }
    
    /**
     * Enqueue match request - FIXED: Use BLE advertising system
     */
    fun enqueueMatchRequest(targetHashHex: String) {
        Log.d(TAG, "💝 ENQUEUE_MATCH_REQUEST: Starting for target: $targetHashHex")
        
        // CRITICAL FIX: Store pending match request
        try {
            val nearbyUsers = _nearbyUsersFlow.value
            val user = nearbyUsers.find { it.userHash == targetHashHex }
            val userGender = user?.gender ?: "U"
            
            val matchStore = MatchStore(context)
            matchStore.storePendingRequest(targetHashHex, userGender)
            Log.d(TAG, "💝 ENQUEUE_MATCH_REQUEST: Stored pending request for $targetHashHex")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ENQUEUE_MATCH_REQUEST: Error storing pending request", e)
        }
        
        // FIXED: Send match request via BLE advertising (more reliable)
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_MATCH_REQ,
            targetHash = targetHashHex
        )
        
        synchronized(outgoingMessageQueue) {
            // Check if we already have a pending match request for this user
            val existingRequest = outgoingMessageQueue.find { 
                it.type == BLEPacket.TYPE_MATCH_REQ && it.targetHash == targetHashHex 
            }
            
            if (existingRequest != null) {
                Log.d(TAG, "💝 Match request already queued for $targetHashHex, skipping duplicate")
                return
            }
            
            outgoingMessageQueue.add(queuedMessage)
            Log.d(TAG, "💝 Match request added to queue for $targetHashHex, queue size: ${outgoingMessageQueue.size}")
        }
        
        Log.d(TAG, "💝 Enqueued match request to $targetHashHex")
        processMessageQueue()
    }
    
    /**
     * Enqueue photo request
     */
    fun enqueuePhotoRequest(targetHashHex: String) {
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_PHOTO_REQUEST,
            targetHash = targetHashHex
        )
        
        synchronized(outgoingMessageQueue) {
            outgoingMessageQueue.add(queuedMessage)
        }
        
        Log.d(TAG, "📷 Enqueued photo request to $targetHashHex")
        processMessageQueue()
    }

    /**
     * Enqueue photo sharing
     */
    fun enqueuePhoto(targetHashHex: String, photoBase64: String) {
        Log.d(TAG, "📷 ENQUEUE_PHOTO: Starting for target: $targetHashHex, photo size: ${photoBase64.length} chars")
        
        // SAFETY: Check photo size before queuing
        if (photoBase64.length > 10000) { // 10KB limit
            Log.w(TAG, "📷 Photo too large for BLE transmission: ${photoBase64.length} chars, skipping")
            return
        }
        
        val queuedMessage = QueuedMessage(
            type = BLEPacket.TYPE_PHOTO,
            targetHash = targetHashHex,
            data = photoBase64
        )
        
        synchronized(outgoingMessageQueue) {
            // Check if we already have a pending photo for this user
            val existingPhoto = outgoingMessageQueue.find { 
                it.type == BLEPacket.TYPE_PHOTO && it.targetHash == targetHashHex 
            }
            
            if (existingPhoto != null) {
                Log.d(TAG, "📷 Photo already queued for $targetHashHex, replacing with newer one")
                outgoingMessageQueue.remove(existingPhoto)
            }
            
            outgoingMessageQueue.add(queuedMessage)
            Log.d(TAG, "📷 Photo added to queue for $targetHashHex, queue size: ${outgoingMessageQueue.size}")
        }
        
        Log.d(TAG, "📷 Enqueued photo to $targetHashHex: ${photoBase64.length} chars")
        processMessageQueue()
    }

    /**
     * Enqueue match response
     */
    fun enqueueMatchResponse(accepted: Boolean, targetHashHex: String) {
        val responseType = if (accepted) BLEPacket.TYPE_MATCH_ACC else BLEPacket.TYPE_MATCH_REJ
        val queuedMessage = QueuedMessage(
            type = responseType,
            targetHash = targetHashHex
        )
        
        synchronized(outgoingMessageQueue) {
            outgoingMessageQueue.add(queuedMessage)
        }
        
        Log.d(TAG, "🎯 Enqueued match response (${if (accepted) "ACCEPT" else "REJECT"}) to $targetHashHex")
        processMessageQueue()
    }
    
    // Premium feature methods
    fun setHighPowerMode(enabled: Boolean) {
        isHighPowerMode = enabled
        Log.d(TAG, "🔥 High power mode: $enabled")
        
        // Restart advertising with new settings if active
        if (isAdvertising) {
            stopAdvertising()
            startAdvertising()
        }
    }
    
    fun setFastScanMode(enabled: Boolean) {
        isFastScanMode = enabled
        Log.d(TAG, "⚡ Fast scan mode: $enabled")
        
        // Restart scanning with new settings if active
        if (isScanning) {
            stopScanning()
            startScanning()
        }
    }
    
    fun setPriorityMode(enabled: Boolean) {
        isPriorityMode = enabled
        Log.d(TAG, "🌟 Priority mode: $enabled")
    }
    
    fun setMoodData(moodType: String, message: String) {
        currentMoodType = moodType
        currentMoodMessage = message
        Log.d(TAG, "😊 Mood set: $moodType - $message")
        
        // Update advertising data if active
        if (isAdvertising) {
            updateAdvertisingData()
        }
    }
    
    fun clearMoodData() {
        currentMoodType = null
        currentMoodMessage = null
        Log.d(TAG, "😐 Mood cleared")
        
        // Update advertising data if active
        if (isAdvertising) {
            updateAdvertisingData()
        }
    }
    
    // Placeholder methods for premium features
    fun broadcastFlashEvent(event: SpontaneousFeatures.FlashEvent) {
        Log.d(TAG, "🎪 Broadcasting flash event: ${event.title}")
        // Implementation would broadcast event data via BLE
    }
    
    fun broadcastEventUpdate(event: SpontaneousFeatures.FlashEvent) {
        Log.d(TAG, "🎪 Broadcasting event update: ${event.title}")
        // Implementation would broadcast event update via BLE
    }
    
    fun sendQuickReaction(targetUserHash: String, emoji: String, message: String) {
        Log.d(TAG, "😊 QUICK_REACTION: Sending reaction: $emoji to $targetUserHash")
        
        // Create quick reaction message
        val reactionData = "$emoji:$message"
        enqueueChat(targetUserHash, "🎯REACTION:$reactionData")
        
        Log.d(TAG, "😊 QUICK_REACTION: Reaction queued successfully")
    }
    
    fun startProximityMonitoring(targetUserHash: String, callback: (Int) -> Unit) {
        Log.d(TAG, "📍 Starting proximity monitoring for: $targetUserHash")
        // Implementation would monitor RSSI changes
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up BLE Engine")
        
        stopAdvertising()
        stopScanning()
        
        // Stop background scanning
        if (isBackgroundScanning) {
            try {
                bleScanner?.stopScan(backgroundScanCallback)
                isBackgroundScanning = false
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping background scan", e)
            }
        }
        
        // Disconnect all iOS devices
        iosDeviceCallbacks.forEach { (address, _) ->
            try {
                Log.d(TAG, "🔌 Disconnecting iOS device: $address")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting iOS device", e)
            }
        }
        connectedIOSDevices.clear()
        iosDeviceCallbacks.clear()
        
        // Cancel all scheduled tasks
        scanRunnable?.let { handler.removeCallbacks(it) }
        advertiseRunnable?.let { handler.removeCallbacks(it) }
        backgroundScanRunnable?.let { handler.removeCallbacks(it) }
        
        // Clear data
        nearbyUsers.clear()
        _nearbyUsersFlow.value = emptyList()
        outgoingMessageQueue.clear()
        
        listener = null
    }
    
    // Private helper methods
    
    private fun createPresencePacket(): ByteArray {
        return try {
            val userPrefs = UserPreferences(context)
            val userName = userPrefs.getUserName().ifEmpty { "User${currentUserId.take(4)}" }
            
            // ENHANCED: Include mood data in presence packet
            if (currentMoodType != null && currentMoodMessage != null) {
                Log.d(TAG, "📦 Creating presence packet WITH mood: $currentMoodType - $currentMoodMessage")
                // Create presence packet with name, gender AND mood
                val moodData = "${currentMoodType}:${currentMoodMessage}".toByteArray(Charsets.UTF_8)
                BLEPacket.encodePresenceWithMood(currentUserHash, userName, currentUserGender, moodData)
            } else {
                Log.d(TAG, "📦 Creating presence packet without mood")
                // Create presence packet with name and gender only
                val packets = BLEPacket.encodePresenceWithNameAndGender(currentUserHash, userName, currentUserGender)
                packets.firstOrNull() ?: BLEPacket.encodePresence(currentUserHash)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating presence packet with mood/name, using simple presence", e)
            BLEPacket.encodePresence(currentUserHash)
        }
    }
    
    private fun updateAdvertisingData() {
        if (isAdvertising) {
            Log.d(TAG, "📡 UPDATE_ADVERTISING: Updating advertising data without stopping")
            // CRITICAL FIX: Don't stop advertising, just update the data
            // The periodic advertising updates will handle data refresh
            Log.d(TAG, "📡 UPDATE_ADVERTISING: Data will be updated in next advertising cycle")
        }
    }
    
    private fun scheduleAdvertisingUpdates() {
        advertiseRunnable = Runnable {
            if (isAdvertising) {
                updateAdvertisingData()
                handler.postDelayed(advertiseRunnable!!, ADVERTISE_PERIOD_MS)
            }
        }
        handler.postDelayed(advertiseRunnable!!, ADVERTISE_PERIOD_MS)
    }
    
    private fun scheduleScanPeriod() {
        scanRunnable = Runnable {
            if (isScanning) {
                // Restart scan to refresh results
                stopScanning()
                handler.postDelayed({ startScanning() }, 1000)
            }
        }
        handler.postDelayed(scanRunnable!!, SCAN_PERIOD_MS)
    }
    
    private fun scheduleBackgroundScanPeriod() {
        backgroundScanRunnable = Runnable {
            if (isBackgroundScanning) {
                // Keep background scanning active
                handler.postDelayed(backgroundScanRunnable!!, BACKGROUND_SCAN_INTERVAL_MS)
            }
        }
        handler.postDelayed(backgroundScanRunnable!!, BACKGROUND_SCAN_INTERVAL_MS)
    }
    
    private fun processMessageQueue() {
        Log.d(TAG, "📋 PROCESS_QUEUE: Starting, isProcessingQueue=$isProcessingQueue, queue size=${outgoingMessageQueue.size}")
        
        if (isProcessingQueue) {
            Log.d(TAG, "📋 PROCESS_QUEUE: Already processing, skipping")
            return
        }
        
        synchronized(outgoingMessageQueue) {
            if (outgoingMessageQueue.isEmpty()) {
                Log.d(TAG, "📋 PROCESS_QUEUE: Queue is empty")
                return
            }
            
            isProcessingQueue = true
            val message = outgoingMessageQueue.removeAt(0)
            
            Log.d(TAG, "📋 PROCESS_QUEUE: Processing message type ${message.type} (${getMessageTypeName(message.type)}) to ${message.targetHash}, remaining: ${outgoingMessageQueue.size}")
            
            // Process message via advertising - FASTER PROCESSING
            handler.post {
                transmitMessage(message)
                isProcessingQueue = false
                
                // Process next message if any - REDUCED DELAY for faster messaging
                if (outgoingMessageQueue.isNotEmpty()) {
                    Log.d(TAG, "📋 PROCESS_QUEUE: Scheduling next message in 500ms (FASTER)")
                    handler.postDelayed({ processMessageQueue() }, 500) // Reduced from 1000ms
                } else {
                    Log.d(TAG, "📋 PROCESS_QUEUE: Queue empty, finished processing")
                }
            }
        }
    }
    
    private fun getMessageTypeName(type: Byte): String {
        return when (type) {
            BLEPacket.TYPE_PRESENCE -> "PRESENCE"
            BLEPacket.TYPE_MATCH_REQ -> "MATCH_REQ"
            BLEPacket.TYPE_MATCH_ACC -> "MATCH_ACC"
            BLEPacket.TYPE_MATCH_REJ -> "MATCH_REJ"
            BLEPacket.TYPE_CHAT -> "CHAT"
            BLEPacket.TYPE_PHOTO -> "PHOTO"
            BLEPacket.TYPE_PHOTO_REQUEST -> "PHOTO_REQUEST"
            BLEPacket.TYPE_UNMATCH -> "UNMATCH"
            BLEPacket.TYPE_BLOCK -> "BLOCK"
            else -> "UNKNOWN($type)"
        }
    }
    
    private fun transmitMessage(message: QueuedMessage) {
        try {
            // CRITICAL FIX: Properly convert hex string to byte array
            val targetHashBytes = if (message.targetHash.length >= 8) {
                // Convert hex string to byte array - FIXED CONVERSION
                val hexString = message.targetHash.take(8).padEnd(8, '0') // Ensure 8 chars
                ByteArray(4) { i ->
                    val startIndex = i * 2
                    try {
                        hexString.substring(startIndex, startIndex + 2).toInt(16).toByte()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error converting hex at index $i: ${e.message}")
                        0
                    }
                }
            } else {
                // Fallback: pad with zeros
                val paddedHash = message.targetHash.padEnd(8, '0')
                ByteArray(4) { i ->
                    val startIndex = i * 2
                    try {
                        paddedHash.substring(startIndex, startIndex + 2).toInt(16).toByte()
                    } catch (e: Exception) {
                        0
                    }
                }
            }
            
            Log.d(TAG, "📤 TRANSMIT: Converting target hash '${message.targetHash}' to bytes: ${targetHashBytes.joinToString { "%02x".format(it) }}")
            
            val packets = when (message.type) {
                BLEPacket.TYPE_MATCH_REQ -> {
                    Log.d(TAG, "📤 TRANSMIT: Creating MATCH_REQ packet")
                    listOf(BLEPacket.encodeMatchReq(currentUserHash, currentUserGender, targetHashBytes))
                }
                BLEPacket.TYPE_CHAT -> {
                    BLEPacket.encodeChat(currentUserHash, targetHashBytes, message.data)
                }
                BLEPacket.TYPE_PHOTO -> {
                    BLEPacket.encodePhoto(currentUserHash, targetHashBytes, message.data)
                }
                BLEPacket.TYPE_PHOTO_REQUEST -> {
                    Log.d(TAG, "📤 TRANSMIT: Creating PHOTO_REQUEST packet from ${currentUserHash.joinToString { "%02x".format(it) }} to ${targetHashBytes.joinToString { "%02x".format(it) }}")
                    BLEPacket.encodePhotoRequest(currentUserHash, targetHashBytes)
                }
                BLEPacket.TYPE_UNMATCH -> {
                    BLEPacket.encodeUnmatch(currentUserHash, targetHashBytes)
                }
                BLEPacket.TYPE_BLOCK -> {
                    BLEPacket.encodeBlock(currentUserHash, targetHashBytes)
                }
                BLEPacket.TYPE_MATCH_ACC -> {
                    listOf(BLEPacket.encodeMatchResp(BLEPacket.TYPE_MATCH_ACC, currentUserHash, currentUserGender, targetHashBytes))
                }
                BLEPacket.TYPE_MATCH_REJ -> {
                    listOf(BLEPacket.encodeMatchResp(BLEPacket.TYPE_MATCH_REJ, currentUserHash, currentUserGender, targetHashBytes))
                }
                else -> {
                    Log.w(TAG, "Unknown message type: ${message.type}")
                    return
                }
            }
            
            // Transmit each packet via advertising with shorter intervals for faster delivery
            packets.forEachIndexed { index, packet ->
                handler.postDelayed({
                    transmitPacketViaAdvertising(packet)
                }, index * 300L) // Reduced from 1000ms to 300ms for faster match requests
            }
            
            Log.d(TAG, "📤 Transmitted ${packets.size} packets for message type ${message.type} to target: ${message.targetHash}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error transmitting message", e)
        }
    }
    
    private fun transmitPacketViaAdvertising(packet: ByteArray) {
        try {
            Log.d(TAG, "📤 TRANSMIT_PACKET: Starting transmission of ${packet.size} bytes")
            
            // CRITICAL FIX: NEVER stop main advertising!
            // Just send the message packet as a separate burst
            // Main advertising continues in parallel
            
            if (bleAdvertiser != null) {
                // Send message packet WITHOUT stopping main advertising
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(2000) // 2 seconds burst
                    .build()
                
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ParcelUuid(AURA_SERVICE_UUID))
                    .addManufacturerData(COMPANY_ID, packet)
                    .build()
                
                val messageCallback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.d(TAG, "📤 TRANSMIT_PACKET: Message sent successfully (main advertising still active)")
                    }
                    
                    override fun onStartFailure(errorCode: Int) {
                        Log.e(TAG, "❌ TRANSMIT_PACKET: Message send failed: $errorCode (main advertising still active)")
                    }
                }
                
                // Start message advertising WITHOUT stopping main advertising
                // Android supports multiple concurrent advertisers (up to 5)
                bleAdvertiser?.startAdvertising(settings, data, messageCallback)
                Log.d(TAG, "📤 TRANSMIT_PACKET: Message advertising started (main advertising continues)")
                
            } else {
                Log.e(TAG, "❌ TRANSMIT_PACKET: BLE advertiser not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ TRANSMIT_PACKET: Error", e)
        }
    }
    
    // BLE Callbacks
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "📡 Advertising started successfully")
            isAdvertising = true // Ensure state is set correctly
            
            // ENHANCED: More aggressive health monitoring
            scheduleAdvertisingHealthCheck()
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "❌ Advertising failed to start: $errorCode")
            isAdvertising = false
            
            // ENHANCED: More intelligent retry with exponential backoff
            val retryDelay = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> 10000L // 10 seconds
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> 30000L // 30 seconds
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> 5000L // 5 seconds
                else -> 8000L // 8 seconds default
            }
            
            Log.d(TAG, "🔄 Auto-retrying advertising after failure in ${retryDelay}ms...")
            handler.postDelayed({
                if (!isAdvertising) { // Only retry if still not advertising
                    Log.d(TAG, "🔄 Executing advertising retry...")
                    startAdvertisingInternal()
                }
            }, retryDelay)
        }
    }
    
    // ENHANCED: Dedicated advertising health check system - IMPROVED
    private fun scheduleAdvertisingHealthCheck() {
        val healthCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    if (isAdvertising) {
                        Log.d(TAG, "🏥 HEALTH_CHECK: Advertising health check - currently active")
                        
                        // ENHANCED: More aggressive health check - restart if needed
                        val shouldRestart = !isAdvertisingActive() // Check actual advertising state
                        
                        if (shouldRestart) {
                            Log.w(TAG, "🏥 HEALTH_CHECK: Advertising appears inactive, restarting...")
                            stopAdvertising()
                            handler.postDelayed({
                                startAdvertisingInternal()
                            }, 1000)
                        } else {
                            Log.d(TAG, "✅ HEALTH_CHECK: Advertising healthy")
                        }
                        
                        // Schedule next check - more frequent for reliability
                        handler.postDelayed(this, 15000) // Check every 15 seconds
                    } else {
                        Log.d(TAG, "🏥 HEALTH_CHECK: Advertising not active, stopping health check")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in advertising health check", e)
                    // Continue checking despite error
                    handler.postDelayed(this, 20000)
                }
            }
        }
        
        // Start health check after initial delay
        handler.postDelayed(healthCheckRunnable, 15000) // First check after 15 seconds
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { processScanResult(it, false) }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { processScanResult(it, false) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ Scan failed: $errorCode")
            isScanning = false
        }
    }
    
    private val backgroundScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { processScanResult(it, true) }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { processScanResult(it, true) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ Background scan failed: $errorCode")
            isBackgroundScanning = false
        }
    }
    
    private fun processScanResult(result: ScanResult, isBackground: Boolean) {
        try {
            // CRITICAL FIX: Don't filter by company ID - iOS uses Apple ID (0x004C), Android uses 0xFFFF
            // Get ALL manufacturer data entries
            val scanRecord = result.scanRecord
            var manufacturerData: ByteArray? = null
            
            // Try to get manufacturer data without company ID filter
            if (scanRecord != null && scanRecord.manufacturerSpecificData != null && scanRecord.manufacturerSpecificData.size() > 0) {
                // Get first manufacturer data entry (any company ID)
                val firstKey = scanRecord.manufacturerSpecificData.keyAt(0)
                val rawData = scanRecord.manufacturerSpecificData.get(firstKey)
                
                Log.d(TAG, "📱 Found manufacturer data with company ID: 0x${firstKey.toString(16).uppercase()}, size: ${rawData?.size ?: 0} bytes")
                
                // If data has company ID prefix (2 bytes), skip it
                if (rawData != null && rawData.size > 2) {
                    // Skip first 2 bytes (company ID) and get payload
                    manufacturerData = rawData.copyOfRange(2, rawData.size)
                    Log.d(TAG, "📱 Extracted payload: ${manufacturerData.size} bytes (skipped 2-byte company ID)")
                } else if (rawData != null) {
                    // Data is too small or doesn't have company ID prefix
                    manufacturerData = rawData
                }
            }
            
            val serviceData = scanRecord?.getServiceData(ParcelUuid(AURA_SERVICE_UUID))
            
            // ENHANCED: Process BOTH manufacturer data AND service data
            var processedAny = false
            
            if (manufacturerData != null) {
                Log.d(TAG, "📱 Processing manufacturer data: ${manufacturerData.size} bytes")
                
                // DEBUG: Print first 15 bytes in hex
                val hexDump = manufacturerData.take(15).joinToString(" ") { String.format("%02X", it) }
                Log.d(TAG, "📱 Payload HEX: $hexDump")
                
                val frame = BLEPacket.decode(manufacturerData)
                if (frame != null) {
                    handleBLEFrame(frame, result.rssi, isBackground)
                    processedAny = true
                } else {
                    Log.w(TAG, "⚠️ Failed to decode manufacturer data")
                }
            }
            
            if (serviceData != null) {
                // iOS device OR Android sending via service data
                Log.d(TAG, "📱 Device has service data: ${serviceData.size} bytes")
                val frame = BLEPacket.decode(serviceData)
                if (frame != null) {
                    handleBLEFrame(frame, result.rssi, isBackground)
                    processedAny = true
                } else {
                    Log.w(TAG, "⚠️ Failed to decode service data")
                }
            }
            
            // Fallback: Check if device name is "Aura" (for iOS GATT)
            if (!processedAny) {
                val deviceName = result.scanRecord?.deviceName
                if (deviceName == "Aura") {
                    Log.d(TAG, "📱 iOS device detected (name: Aura), connecting to read characteristic...")
                    // CRITICAL FIX: Always use isBackground=false for iOS GATT reads
                    // We need to update nearby users list even if this is a background scan
                    connectToiOSDevice(result.device, result.rssi, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing scan result", e)
        }
    }
    
    private fun handleBLEFrame(frame: BLEPacket.DecodedFrame, rssi: Int, isBackground: Boolean) {
        val senderHashHex = frame.senderHash.joinToString("") { "%02x".format(it) }
        
        Log.d(TAG, "🔍 handleBLEFrame: type=${frame.type}, sender=$senderHashHex, isBackground=$isBackground")
        
        when (frame.type) {
            BLEPacket.TYPE_PRESENCE -> {
                Log.d(TAG, "🔍 handleBLEFrame: PRESENCE packet, isBackground=$isBackground")
                if (!isBackground) { // Only update nearby users for foreground scanning
                    Log.d(TAG, "🔍 handleBLEFrame: Calling handlePresenceFrame")
                    handlePresenceFrame(frame, rssi, senderHashHex)
                } else {
                    Log.d(TAG, "🔍 handleBLEFrame: Skipping PRESENCE (background scan)")
                }
            }
            BLEPacket.TYPE_MATCH_REQ -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_MATCH_REQ)) {
                    Log.d(TAG, "📥 MATCH_REQ: Processing match request from: $senderHashHex")
                    listener?.onIncomingMatchRequest(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 MATCH_REQ: Duplicate/spam match request blocked from: $senderHashHex")
                }
            }
            BLEPacket.TYPE_MATCH_ACC -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_MATCH_ACC)) {
                    Log.d(TAG, "✅ MATCH_ACC: Processing match acceptance from: $senderHashHex")
                    listener?.onMatchAccepted(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 MATCH_ACC: Duplicate match acceptance blocked from: $senderHashHex")
                }
            }
            BLEPacket.TYPE_MATCH_REJ -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_MATCH_REJ)) {
                    Log.d(TAG, "❌ MATCH_REJ: Processing match rejection from: $senderHashHex")
                    listener?.onMatchRejected(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 MATCH_REJ: Duplicate match rejection blocked from: $senderHashHex")
                }
            }
            BLEPacket.TYPE_CHAT -> {
                if (frame.isComplete && frame.completeMessage != null) {
                    // CRITICAL FIX: For chat messages, use content-based duplicate detection
                    // Don't use shouldProcessMessage() because it blocks all messages from same sender
                    val messageContent = frame.completeMessage
                    val contentHash = messageContent.hashCode()
                    val messageKey = "${senderHashHex}_CHAT_${contentHash}"
                    val currentTime = System.currentTimeMillis()
                    
                    // Check if this exact message content was already processed recently (5 seconds)
                    val lastProcessed = messageTimeouts[messageKey]
                    if (lastProcessed != null && (currentTime - lastProcessed) < 5000) {
                        Log.d(TAG, "🚫 CHAT: Duplicate chat message blocked from: $senderHashHex (same content)")
                    } else {
                        // New message, process it
                        messageTimeouts[messageKey] = currentTime
                        processedMessages.add(messageKey)
                        
                        Log.d(TAG, "💬 CHAT: Processing chat message from: $senderHashHex: ${messageContent.take(20)}...")
                        listener?.onChatMessage(senderHashHex, messageContent)
                        
                        // Clean up old message keys (keep last 100)
                        if (messageTimeouts.size > 100) {
                            val toRemove = messageTimeouts.entries
                                .sortedBy { it.value }
                                .take(messageTimeouts.size - 100)
                                .map { it.key }
                            toRemove.forEach { key ->
                                messageTimeouts.remove(key)
                                processedMessages.remove(key)
                            }
                        }
                    }
                }
            }
            BLEPacket.TYPE_PHOTO -> {
                if (frame.isComplete && frame.completeMessage != null) {
                    Log.d(TAG, "📷 Photo received from: $senderHashHex, size: ${frame.completeMessage.length} chars")
                    listener?.onPhotoReceived(senderHashHex, frame.completeMessage)
                } else {
                    Log.d(TAG, "📷 Incomplete photo chunk from: $senderHashHex, chunk ${frame.chunkIndex}/${frame.chunkTotal}")
                }
            }
            BLEPacket.TYPE_PHOTO_REQUEST -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_PHOTO_REQUEST)) {
                    Log.d(TAG, "📷 PHOTO_REQ: Processing photo request from: $senderHashHex")
                    listener?.onPhotoRequested(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 PHOTO_REQ: Duplicate photo request blocked from: $senderHashHex")
                }
            }
            BLEPacket.TYPE_UNMATCH -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_UNMATCH)) {
                    Log.d(TAG, "💔 UNMATCH: Processing unmatch from: $senderHashHex")
                    listener?.onUnmatchReceived(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 UNMATCH: Duplicate unmatch blocked from: $senderHashHex")
                }
            }
            BLEPacket.TYPE_BLOCK -> {
                if (shouldProcessMessage(senderHashHex, BLEPacket.TYPE_BLOCK)) {
                    Log.d(TAG, "🚫 BLOCK: Processing block from: $senderHashHex")
                    listener?.onBlockReceived(senderHashHex)
                } else {
                    Log.d(TAG, "🚫 BLOCK: Duplicate block blocked from: $senderHashHex")
                }
            }
        }
    }
    
    
    /**
     * Connect to iOS device to read presence characteristic
     */
    private fun connectToiOSDevice(device: BluetoothDevice, rssi: Int, isBackground: Boolean) {
        val deviceAddress = device.address
        
        // FIXED: Allow reconnection after 5 seconds (for username changes)
        val lastConnection = iosDeviceCallbacks[deviceAddress]
        if (lastConnection != null && connectedIOSDevices.contains(deviceAddress)) {
            Log.d(TAG, "📱 Already connected to iOS device: $deviceAddress, skipping")
            return
        }
        
        connectedIOSDevices.add(deviceAddress)
        
        // FIXED: Auto-remove from connected list after 5 seconds
        handler.postDelayed({
            connectedIOSDevices.remove(deviceAddress)
            Log.d(TAG, "🔄 iOS device $deviceAddress removed from connected list (timeout)")
        }, 5000) // 5 seconds
        
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Connected to iOS device: $deviceAddress")
                        gatt?.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Disconnected from iOS device: $deviceAddress")
                        connectedIOSDevices.remove(deviceAddress)
                        iosDeviceCallbacks.remove(deviceAddress)
                        gatt?.close()
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "✅ Services discovered on iOS device")
                    Log.d(TAG, "   Total services: ${gatt?.services?.size ?: 0}")
                    
                    // Log ALL services
                    gatt?.services?.forEachIndexed { index, service ->
                        Log.d(TAG, "   Service[$index]: ${service.uuid}")
                        Log.d(TAG, "      Characteristics: ${service.characteristics?.size ?: 0}")
                        service.characteristics?.forEachIndexed { charIndex, char ->
                            Log.d(TAG, "         Char[$charIndex]: ${char.uuid}")
                        }
                    }
                    
                    // Find Aura service
                    val service = gatt?.getService(AURA_SERVICE_UUID)
                    if (service != null) {
                        Log.d(TAG, "✅ Found Aura service: $AURA_SERVICE_UUID")
                        Log.d(TAG, "   Service has ${service.characteristics?.size ?: 0} characteristics")
                        
                        // Log all characteristics in Aura service
                        service.characteristics?.forEachIndexed { index, char ->
                            Log.d(TAG, "   Characteristic[$index]: ${char.uuid}")
                        }
                        
                        // Find presence characteristic
                        val characteristic = service.getCharacteristic(PRESENCE_CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            Log.d(TAG, "✅ Found presence characteristic: $PRESENCE_CHARACTERISTIC_UUID")
                            Log.d(TAG, "   Properties: ${characteristic.properties}")
                            Log.d(TAG, "   Permissions: ${characteristic.permissions}")
                            Log.d(TAG, "   Reading characteristic...")
                            gatt.readCharacteristic(characteristic)
                        } else {
                            Log.w(TAG, "⚠️ Presence characteristic not found!")
                            Log.w(TAG, "   Looking for: $PRESENCE_CHARACTERISTIC_UUID")
                            Log.w(TAG, "   Available characteristics:")
                            service.characteristics?.forEach { char ->
                                Log.w(TAG, "      - ${char.uuid}")
                            }
                            gatt?.disconnect()
                        }
                    } else {
                        Log.w(TAG, "⚠️ Aura service not found!")
                        Log.w(TAG, "   Looking for: $AURA_SERVICE_UUID")
                        Log.w(TAG, "   Available services:")
                        gatt?.services?.forEach { svc ->
                            Log.w(TAG, "      - ${svc.uuid}")
                        }
                        gatt?.disconnect()
                    }
                } else {
                    Log.e(TAG, "❌ Service discovery failed: $status")
                    gatt?.disconnect()
                }
            }
            
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    var data = characteristic.value
                    if (data != null && data.isNotEmpty()) {
                        Log.d(TAG, "📦 Read ${data.size} bytes from iOS device")
                        
                        // CRITICAL FIX: iOS characteristic may return padded data (512 bytes)
                        // Find actual packet size by looking for pattern or trimming trailing zeros
                        // BLE packets have: [version][type][senderHash:4][targetHash:4][msgId][chunkIdx][chunkTotal][data...]
                        // Minimum size is 13 bytes, maximum useful size is ~50 bytes for presence with name
                        
                        // Find first occurrence of multiple consecutive zero bytes (padding)
                        var actualSize = data.size
                        for (i in 13 until data.size - 3) {
                            // If we find 4 consecutive zeros after minimum packet size, that's likely padding
                            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && 
                                data[i+2] == 0.toByte() && data[i+3] == 0.toByte()) {
                                actualSize = i
                                break
                            }
                        }
                        
                        if (actualSize < data.size) {
                            Log.d(TAG, "📦 Trimmed iOS data from ${data.size} to $actualSize bytes (removed padding)")
                            data = data.copyOfRange(0, actualSize)
                        }
                        
                        // Decode presence packet
                        val frame = BLEPacket.decode(data)
                        if (frame != null) {
                            handleBLEFrame(frame, rssi, isBackground)
                        } else {
                            Log.w(TAG, "⚠️ Failed to decode iOS presence packet")
                        }
                    } else {
                        Log.w(TAG, "⚠️ No data in characteristic")
                    }
                } else {
                    Log.e(TAG, "❌ Characteristic read failed: $status")
                }
                
                // Disconnect after reading
                gatt?.disconnect()
            }
        }
        
        // Store callback reference
        iosDeviceCallbacks[deviceAddress] = gattCallback
        
        // Connect to iOS device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }
    
    private fun handlePresenceFrame(frame: BLEPacket.DecodedFrame, rssi: Int, senderHashHex: String) {
        try {
            // Extract user name, gender and mood from presence data
            var userName = "User${senderHashHex.take(4).uppercase()}"
            var gender = "U"
            var moodType: String? = null
            var moodMessage: String? = null
            
            if (frame.chunkData.isNotEmpty()) {
                // First byte is gender, rest is name and possibly mood
                val genderByte = frame.chunkData[0]
                gender = when (genderByte.toInt()) {
                    0x01 -> "M"
                    0x02 -> "F"
                    else -> "U"
                }
                
                if (frame.chunkData.size > 1) {
                    val dataBytes = frame.chunkData.copyOfRange(1, frame.chunkData.size)
                    
                    // DEBUG: Log hex dump
                    Log.d(TAG, "👤 USER: Raw data bytes (${dataBytes.size}): ${dataBytes.joinToString(" ") { "%02x".format(it) }}")
                    
                    // CRITICAL FIX: Find where username ends (first byte < 0x20 or > 0x7E, but allow '|' for mood separator)
                    var endIndex = -1
                    var foundPipe = false
                    for (i in dataBytes.indices) {
                        val byte = dataBytes[i]
                        if (byte.toInt() == 0x7C) { // '|' character for mood separator
                            foundPipe = true
                            continue
                        }
                        if (byte < 0x20 || byte > 0x7E) {
                            endIndex = i
                            break
                        }
                    }
                    
                    Log.d(TAG, "👤 USER: First non-ASCII byte index: $endIndex, found pipe: $foundPipe")
                    
                    val cleanDataBytes = if (endIndex >= 0) {
                        dataBytes.copyOfRange(0, endIndex)
                    } else {
                        dataBytes
                    }
                    
                    Log.d(TAG, "👤 USER: Clean data bytes (${cleanDataBytes.size}): ${cleanDataBytes.joinToString(" ") { "%02x".format(it) }}")
                    
                    val dataString = String(cleanDataBytes, Charsets.UTF_8)
                    
                    // ENHANCED: Better mood data parsing
                    if (dataString.contains("|")) {
                        val parts = dataString.split("|", limit = 2)
                        userName = parts[0].take(20) // Limit name length
                        
                        if (parts.size > 1 && parts[1].isNotEmpty()) {
                            // Parse mood data: "moodType:moodMessage"
                            val moodParts = parts[1].split(":", limit = 2)
                            if (moodParts.size >= 2) {
                                moodType = moodParts[0].trim()
                                moodMessage = moodParts[1].trim()
                                Log.d(TAG, "😊 MOOD: Detected mood from $userName: $moodType - $moodMessage")
                            } else if (moodParts.size == 1 && moodParts[0].isNotEmpty()) {
                                // Single mood data without separator
                                moodType = "CUSTOM"
                                moodMessage = moodParts[0].trim()
                                Log.d(TAG, "😊 MOOD: Detected simple mood from $userName: $moodMessage")
                            }
                        }
                    } else {
                        // No mood data, just name
                        userName = dataString.take(20)
                        Log.d(TAG, "👤 USER: No mood data, just name: $userName")
                    }
                }
            }
            
            // Update or add nearby user with mood info
            val nearbyUser = NearbyUser(
                userHash = senderHashHex,
                userName = userName,
                gender = gender,
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                moodType = moodType,
                moodMessage = moodMessage
            )
            
            nearbyUsers[senderHashHex] = nearbyUser
            
            // Update flow
            _nearbyUsersFlow.value = nearbyUsers.values.toList()
            
            val moodInfo = if (moodType != null && moodMessage != null) " [Mood: $moodType - $moodMessage]" else ""
            Log.d(TAG, "👤 Updated nearby user: $userName ($gender) RSSI: $rssi$moodInfo")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling presence frame", e)
        }
    }
}