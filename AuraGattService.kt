package com.aura.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Robust GATT service with binary protocol, chunking, and proper retry logic
 * Fixes status=133, fragmentation, and one-sided match flow
 * 
 * BINARY PROTOCOL SPECIFICATION:
 * - Frame Header: [type:1][msgId:2][totalLen:2][offset:2] = 7 bytes
 * - Max Payload per Chunk: 20 - 7 = 13 bytes (MTU-safe)
 * - Reassembly Buffer: stored per msgId in BinaryFraming.reassemblyBuffers
 * - Match Storage: bilateral on both ACCEPT sender and receiver
 */
class AuraGattService(private val context: Context) {
    
    companion object {
        private const val TAG = "AuraGattService"
        
        // Timeouts and delays - INCREASED for slower devices (A51, etc.)
        private const val CONNECT_TIMEOUT_MS = 12000L // INCREASED from 8000L
        private const val DISCOVER_TIMEOUT_MS = 20000L // Already increased to 20s for problematic devices
        private const val MTU_TIMEOUT_MS = 800L // Fallback if MTU callback not received
        private const val WRITE_DELAY_MS = 500L
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRIES = 2
        
        // MTU constants - CRITICAL FOR CHUNKING
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private const val MAX_CHUNK_SIZE = DEFAULT_MTU - ATT_OVERHEAD // 20 bytes max per write
        
        // Client state machine
        private enum class ClientState {
            IDLE, CONNECTING, CONNECTED, DISCOVERING, READY, WRITING, DONE, ERROR
        }
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    private val userPrefs = UserPreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    
    // Client state machine
    private var clientState = ClientState.IDLE
    private var retryCount = 0
    private var pendingDevice: BluetoothDevice? = null
    private var pendingMessage: List<ByteArray>? = null
    private var connectTimeoutRunnable: Runnable? = null
    private var discoverTimeoutRunnable: Runnable? = null
    private var mtuTimeoutRunnable: Runnable? = null
    
    // Cached service and characteristics after discovery
    private var cachedAuraService: BluetoothGattService? = null
    private var cachedRequestChar: BluetoothGattCharacteristic? = null
    private var cachedResponseChar: BluetoothGattCharacteristic? = null
    private var cachedChatChar: BluetoothGattCharacteristic? = null
    
    // Current MTU
    private var currentMtu = DEFAULT_MTU
    
    // Channel tracking for correct characteristic selection
    private enum class OutgoingChannel {
        REQUEST, RESPONSE, CHAT
    }
    private var outgoingChannel: OutgoingChannel? = null
    
    // Collision control - prevent dual-client issues
    private var isClientBusy = false
    
    interface MatchRequestListener {
        fun onMatchRequestReceived(fromUserHashHex: String, fromGender: String)
        fun onMatchAccepted(userHashHex: String)
        fun onMatchRejected(userHashHex: String)
        fun onMatchTimeout(userHashHex: String)
        fun onConnectionError(error: String)
        fun onSyncedMatchConfirmed(playAt: Long, gender: Gender, outputMode: OutputMode)
        fun onGattReadyToSend()
        fun onChatMessageReceived(fromUserHashHex: String, message: String)
        fun onUnmatchReceived(fromUserHashHex: String)
        fun onBlockReceived(fromUserHashHex: String)
    }
    
    private var matchRequestListener: MatchRequestListener? = null
    
    fun setMatchRequestListener(listener: MatchRequestListener?) {
        this.matchRequestListener = listener
        Log.d(TAG, "Match request listener set: ${listener != null}")
    }
    
    // ========== SERVER IMPLEMENTATION ==========
    
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "🔗 Server connection: device=${device.address}, status=$status, state=$newState")
            
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "🧹 Server disconnected: ${device.address}")
            }
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            
            Log.d(TAG, "📝 SERVER_WRITE: device=${device.address}, char_uuid=${characteristic.uuid}, len=${value.size}, first_bytes=${if (value.isNotEmpty()) "%02x".format(value[0]) else "empty"}")
            
            try {
                when (characteristic.uuid) {
                    AuraProtocol.MATCH_REQUEST_CHARACTERISTIC_UUID -> {
                        Log.d(TAG, "📥 Server received: characteristic=REQUEST_UUID")
                        handleBinaryChunk(device, requestId, value, responseNeeded, "REQUEST")
                    }
                    AuraProtocol.CHAT_CHARACTERISTIC_UUID -> {
                        Log.d(TAG, "📥 Server received: characteristic=CHAT_UUID")
                        handleBinaryChunk(device, requestId, value, responseNeeded, "CHAT")
                    }
                    AuraProtocol.MATCH_RESPONSE_CHARACTERISTIC_UUID -> {
                        Log.d(TAG, "📥 Server received: characteristic=RESPONSE_UUID")
                        handleBinaryChunk(device, requestId, value, responseNeeded, "RESPONSE")
                    }
                    else -> {
                        Log.w(TAG, "❌ Unknown characteristic: ${characteristic.uuid}")
                        if (responseNeeded) {
                            safeSendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception in write handler", e)
                if (responseNeeded) {
                    safeSendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }
    }
    
    private fun handleBinaryChunk(
        device: BluetoothDevice,
        requestId: Int,
        chunk: ByteArray,
        responseNeeded: Boolean,
        type: String
    ) {
        try {
            // Process chunk using BinaryFraming - handles reassembly internally
            val framedMessage = BinaryFraming.processChunk(chunk)
            
            if (framedMessage != null) {
                // Complete message received
                Log.d(TAG, "✅ Complete $type message received: type=${framedMessage.type}, size=${framedMessage.payload.size}")
                Log.d(TAG, "📥 Server processed: framedMessage.type=${framedMessage.type}")
                
                when (framedMessage.type) {
                    AuraProtocol.MSG_MATCH_REQUEST -> {
                        handleMatchRequest(framedMessage.payload, device)
                    }
                    AuraProtocol.MSG_MATCH_ACCEPT, AuraProtocol.MSG_MATCH_REJECT -> {
                        handleMatchResponse(framedMessage.type, framedMessage.payload)
                    }
                    AuraProtocol.MSG_CHAT_MESSAGE -> {
                        handleChatMessage(framedMessage.payload)
                    }
                    AuraProtocol.MSG_UNMATCH -> {
                        handleUnmatchMessage(framedMessage.payload)
                    }
                    AuraProtocol.MSG_BLOCK -> {
                        handleBlockMessage(framedMessage.payload)
                    }
                    else -> {
                        Log.w(TAG, "❓ Unknown message type: ${framedMessage.type}")
                    }
                }
            } else {
                Log.d(TAG, "📥 Partial $type chunk received, waiting for more...")
            }
            
            if (responseNeeded) {
                safeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling $type chunk", e)
            if (responseNeeded) {
                safeSendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }
    
    private fun handleMatchRequest(payload: ByteArray, senderDevice: BluetoothDevice) {
        val parsed = BinaryFraming.parseMatchRequest(payload)
        if (parsed != null) {
            val (userHash, gender) = parsed
            val fromUserHashHex = userHash.joinToString("") { "%02x".format(it) }
            val fromGender = when (gender) {
                AuraProtocol.GENDER_MALE -> "M"
                AuraProtocol.GENDER_FEMALE -> "F"
                else -> "U"
            }
            
            Log.d(TAG, "📥 RECEIVE_REQUEST from ${senderDevice.address} fromUserHashHex=$fromUserHashHex")
            Log.d(TAG, "✅ INCOMING_MATCH_REQUEST: fromHash=$fromUserHashHex, gender=$fromGender")
            Log.d(TAG, "📥 BINARY_FRAMING_COMPLETE: requester_hash=$fromUserHashHex, gender=$fromGender")
            Log.d(TAG, "📥 PARSED sender identity: $fromUserHashHex from device: ${senderDevice.address}")
            Log.d(TAG, "🔍 PROOF: Receiving request - senderHashHex=$fromUserHashHex, device=${senderDevice.address}")
            
            // BLOCKING FILTER: Auto-reject blocked users
            val blockStore = BlockStore(context)
            if (blockStore.isBlocked(fromUserHashHex)) {
                Log.d(TAG, "🚫 Auto-rejecting request from blocked user: $fromUserHashHex")
                return // Silently ignore blocked users
            }
            
            // Store device info for potential response - use the sender's hash as key
            storeDeviceForResponse(fromUserHashHex, senderDevice)
            Log.d(TAG, "📱 STORED device in map: $fromUserHashHex -> ${senderDevice.address}")
            Log.d(TAG, "🔍 PROOF: Stored in deviceResponseMap - key=$fromUserHashHex, device=${senderDevice.address}")
            
            matchRequestListener?.onMatchRequestReceived(fromUserHashHex, fromGender)
        } else {
            Log.e(TAG, "❌ Failed to parse match request")
        }
    }
    
    private fun handleMatchResponse(messageType: Byte, payload: ByteArray) {
        Log.d(TAG, "📥 Raw response messageType=$messageType, payload[0]: ${if (payload.isNotEmpty()) payload[0] else "empty"}")
        
        val parsed = BinaryFraming.parseMatchResponse(payload)
        if (parsed != null) {
            val (accepted, userHash, gender) = parsed
            val fromUserHashHex = userHash.joinToString("") { "%02x".format(it) }
            val fromGender = when (gender) {
                AuraProtocol.GENDER_MALE -> "M"
                AuraProtocol.GENDER_FEMALE -> "F"
                else -> "U"
            }
            
            // CRITICAL FIX: Determine acceptance from messageType, not payload flag
            val isAccepted = messageType == AuraProtocol.MSG_MATCH_ACCEPT
            
            Log.d(TAG, "📥 RECEIVED_RESPONSE messageType=$messageType -> accepted=$isAccepted from $fromUserHashHex (payload accepted flag=$accepted)")
            
            if (isAccepted) {
                // Create deterministic match ID for debug log
                val currentUserId = userPrefs.getUserId()
                val currentUserHash = getUserHash(currentUserId).joinToString("") { "%02x".format(it) }
                val sortedHashes = listOf(currentUserHash, fromUserHashHex).sorted()
                val matchId = "${sortedHashes[0]}:${sortedHashes[1]}".hashCode().toString()
                
                Log.d(TAG, "📥 RECEIVE_RESPONSE accepted=$isAccepted responderHashHex=$fromUserHashHex STORE_MATCH matchId=$matchId")
                Log.d(TAG, "✅ ACCEPT responderHashHex=$fromUserHashHex storing match for sender UI")
                Log.d(TAG, "✅ MATCH_ACCEPT received from $fromUserHashHex")
                Log.d(TAG, "✅ MATCH_ACCEPT received from $fromUserHashHex - storing match - emitting matches")
                Log.d(TAG, "🔍 PROOF: Receiving response - accepted=$isAccepted, responderHashHex=$fromUserHashHex, sender storing match")
                
                // BILATERAL STORAGE: Store match on sender side when accept is received
                val deviceAddress = pendingDevice?.address
                MatchRequestManager.storeSuccessfulMatch(fromUserHashHex, fromGender, deviceAddress)
                
                matchRequestListener?.onMatchAccepted(fromUserHashHex)
            } else {
                Log.d(TAG, "❌ MATCH_REJECT received from $fromUserHashHex - NO MATCH STORED")
                matchRequestListener?.onMatchRejected(fromUserHashHex)
            }
        } else {
            Log.e(TAG, "❌ Failed to parse match response")
        }
    }
    
    private fun handleChatMessage(payload: ByteArray) {
        val parsed = BinaryFraming.parseChatMessage(payload)
        if (parsed != null) {
            val (userHash, message) = parsed
            val fromUserHashHex = userHash.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "💬 CHAT_MESSAGE received from $fromUserHashHex: ${message.take(50)}...")
            Log.d(TAG, "💬 CHAT_MESSAGE received from $fromUserHashHex")
            matchRequestListener?.onChatMessageReceived(fromUserHashHex, message)
        } else {
            Log.e(TAG, "❌ Failed to parse chat message")
        }
    }
    
    private fun handleUnmatchMessage(payload: ByteArray) {
        // Payload contains the sender's hash who is unmatching
        if (payload.size >= 8) {
            val senderHash = payload.copyOf(8)
            val fromUserHashHex = senderHash.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "💔 UNMATCH received from $fromUserHashHex")
            
            // Remove match locally
            val matchStore = MatchStore(context)
            val matches = matchStore.getMatches().toMutableList()
            val removedMatch = matches.removeAll { it.userHash == fromUserHashHex }
            
            if (removedMatch) {
                matchStore.saveMatches(matches)
                Log.d(TAG, "💔 Removed match with $fromUserHashHex due to unmatch")
                
                // Clear chat history
                val chatStore = ChatStore(context)
                chatStore.clearChatHistory(fromUserHashHex)
                
                // Notify listener
                matchRequestListener?.onUnmatchReceived(fromUserHashHex)
            }
        } else {
            Log.e(TAG, "❌ Invalid unmatch payload size: ${payload.size}")
        }
    }
    
    private fun handleBlockMessage(payload: ByteArray) {
        // Payload contains the sender's hash who is blocking us
        if (payload.size >= 8) {
            val senderHash = payload.copyOf(8)
            val fromUserHashHex = senderHash.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "🚫 BLOCK received from $fromUserHashHex")
            
            // Remove match if exists
            val matchStore = MatchStore(context)
            val matches = matchStore.getMatches().toMutableList()
            val removedMatch = matches.removeAll { it.userHash == fromUserHashHex }
            
            if (removedMatch) {
                matchStore.saveMatches(matches)
                Log.d(TAG, "🚫 Removed match with $fromUserHashHex due to block")
                
                // Clear chat history
                val chatStore = ChatStore(context)
                chatStore.clearChatHistory(fromUserHashHex)
            }
            
            // Note: We don't block them back automatically - that's a user choice
            Log.d(TAG, "🚫 User $fromUserHashHex has blocked us")
        } else {
            Log.e(TAG, "❌ Invalid block payload size: ${payload.size}")
        }
    }
    
    // Device storage for responses (temporary solution)
    private val deviceResponseMap = ConcurrentHashMap<String, BluetoothDevice>()
    
    private fun storeDeviceForResponse(userHash: String, device: BluetoothDevice) {
        deviceResponseMap[userHash] = device
        Log.d(TAG, "📱 Stored device for response: $userHash -> ${device.address}")
    }
    
    /**
     * Get device address for a user hash (for chat functionality)
     */
    fun getDeviceAddressForUserHash(userHashHex: String): String? {
        return deviceResponseMap[userHashHex]?.address
    }
    
    // ========== CLIENT IMPLEMENTATION ==========
    
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "🔗 CONNECTED -> Client state: $clientState -> status=$status, newState=$newState")
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (clientState == ClientState.CONNECTING) {
                        Log.d(TAG, "✅ GATT connected successfully")
                        clientState = ClientState.CONNECTED
                        cancelConnectTimeout()
                        
                        tryGattRefresh(gatt)
                        
                        if (hasConnectPermission()) {
                            try {
                                val mtuResult = gatt.requestMtu(247)
                                Log.d(TAG, "� MTU request: $mtuResult")
                                // Add timeout fallback in case MTU callback never comes
                                mtuTimeoutRunnable = Runnable {
                                    if (clientState == ClientState.CONNECTED) {
                                        Log.w(TAG, "⏰ MTU timeout - starting discovery anyway")
                                        startServiceDiscovery(gatt)
                                    }
                                }
                                handler.postDelayed(mtuTimeoutRunnable!!, MTU_TIMEOUT_MS)
                            } catch (e: SecurityException) {
                                Log.w(TAG, "MTU request failed", e)
                                startServiceDiscovery(gatt)
                            }
                        } else {
                            failAndReset("Bluetooth connect permission not granted")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "🔌 GATT disconnected (status=$status)")
                    
                    cancelAllTimeouts()
                    clearCachedCharacteristics()
                    
                    // Always close the GATT connection
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception closing GATT", e)
                    }
                    
                    if (gattClient == gatt) {
                        gattClient = null
                    }
                    
                    // Retry logic for status=133 or other connection failures
                    if ((status == 133 || status != BluetoothGatt.GATT_SUCCESS) && retryCount < MAX_RETRIES && pendingDevice != null) {
                        Log.w(TAG, "🔄 Connection failed (status=$status) - retry ${retryCount + 1}/$MAX_RETRIES")
                        clientState = ClientState.IDLE
                        retryCount++
                        
                        handler.postDelayed({
                            Log.d(TAG, "🔄 Retrying connection after ${RETRY_DELAY_MS}ms...")
                            connectToDevice(pendingDevice!!)
                        }, RETRY_DELAY_MS)
                    } else {
                        clientState = if (status == BluetoothGatt.GATT_SUCCESS) ClientState.DONE else ClientState.ERROR
                        
                        // COLLISION CONTROL: Clear client busy flag on disconnection
                        isClientBusy = false
                        outgoingChannel = null
                        
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            val errorMsg = when (status) {
                                133 -> if (retryCount >= MAX_RETRIES) "Device not compatible" else "Connection failed"
                                8 -> "Connection timeout"
                                19 -> "Remote device disconnected"
                                else -> "Connection failed (status=$status)"
                            }
                            failAndReset(errorMsg)
                        }
                    }
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Log.d(TAG, "� MTU changed: mtu=$mtu, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Cancel MTU timeout since callback was received
                cancelMtuTimeout()
                
                currentMtu = mtu
                Log.d(TAG, "📏 MTU updated: $currentMtu, max payload: ${currentMtu - ATT_OVERHEAD - AuraProtocol.FRAME_HEADER_SIZE}")
                
                // CRITICAL FIX: Start discovery immediately after MTU success
                Log.d(TAG, "✅ MTU negotiated successfully - starting service discovery")
                startServiceDiscovery(gatt)
            } else {
                Log.w(TAG, "❌ MTU negotiation failed - starting discovery with default MTU")
                // Start discovery anyway with default MTU
                startServiceDiscovery(gatt)
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "🔍 Services discovered: status=$status, state=$clientState")
            
            cancelDiscoverTimeout()
            
            if (clientState != ClientState.DISCOVERING) {
                Log.w(TAG, "⚠️ Unexpected discovery callback in state: $clientState")
                return
            }
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Services discovered successfully")
                
                val auraService = gatt.getService(AuraProtocol.AURA_GATT_SERVICE_UUID)
                if (auraService != null) {
                    val requestChar = auraService.getCharacteristic(AuraProtocol.MATCH_REQUEST_CHARACTERISTIC_UUID)
                    val responseChar = auraService.getCharacteristic(AuraProtocol.MATCH_RESPONSE_CHARACTERISTIC_UUID)
                    val chatChar = auraService.getCharacteristic(AuraProtocol.CHAT_CHARACTERISTIC_UUID)
                    
                    if (requestChar != null && responseChar != null && chatChar != null) {
                        Log.d(TAG, "✅ All characteristics found - caching and marking ready!")
                        
                        // Cache characteristics for reuse
                        cachedAuraService = auraService
                        cachedRequestChar = requestChar
                        cachedResponseChar = responseChar
                        cachedChatChar = chatChar
                        
                        // CRITICAL FIX: Mark ready immediately - no notification setup needed
                        Log.d(TAG, "✅ Characteristics cached - marking client ready")
                        clientState = ClientState.READY
                        
                        // Start sending immediately after delay
                        handler.postDelayed({
                            sendNextChunk()
                        }, WRITE_DELAY_MS)
                    } else {
                        Log.e(TAG, "❌ Missing required characteristics")
                        failAndReset(if (retryCount >= MAX_RETRIES) "Device not compatible" else "Missing characteristics")
                    }
                } else {
                    Log.e(TAG, "❌ Aura GATT service not found")
                    failAndReset(if (retryCount >= MAX_RETRIES) "Device not compatible" else "Service not found")
                }
            } else {
                Log.e(TAG, "❌ Service discovery failed (status=$status)")
                failAndReset("Service discovery failed")
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(TAG, "📝 Write completed: status=$status, char=${characteristic.uuid}")
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ Chunk sent successfully")
                
                // Send next chunk if available
                sendNextChunk()
            } else if (status == 133 && retryCount < MAX_RETRIES && pendingDevice != null) {
                Log.w(TAG, "🔄 Write GATT_ERROR (133) - retrying connection")
                clientState = ClientState.IDLE
                retryCount++
                
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception during retry cleanup", e)
                }
                
                if (gattClient == gatt) {
                    gattClient = null
                }
                
                handler.postDelayed({
                    Log.d(TAG, "🔄 Retrying connection after write failure...")
                    connectToDevice(pendingDevice!!)
                }, RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "❌ Write failed (status=$status)")
                clientState = ClientState.ERROR
                val errorMsg = when (status) {
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "Write not permitted"
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "Invalid attribute length"
                    133 -> if (retryCount >= MAX_RETRIES) "Device not compatible" else "Write failed"
                    else -> "Write failed (status=$status)"
                }
                handleConnectionError(errorMsg)
            }
        }
        
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            Log.d(TAG, "📥 Characteristic changed: char=${characteristic.uuid}, size=${characteristic.value?.size ?: 0}")
            
            // For now, we don't use characteristic changes for match responses
            // Responses are handled via GATT server writes
            Log.d(TAG, "📥 Characteristic change ignored - using GATT server writes for responses")
        }
    }
    
    // ========== PUBLIC API ==========
    
    /**
     * Hard reset client state before every match attempt
     * CRITICAL: Prevents "connection lost" issues
     */
    private fun resetClient() {
        Log.d(TAG, "🔄 Hard reset client state")
        
        cancelAllTimeouts()
        clearCachedCharacteristics()
        
        gattClient?.let { client ->
            try {
                client.disconnect()
                client.close()
                Log.d(TAG, "🧹 Closed existing GATT client")
            } catch (e: Exception) {
                Log.w(TAG, "Exception closing GATT client", e)
            }
        }
        gattClient = null
        
        clientState = ClientState.IDLE
        retryCount = 0
        pendingMessage = null
        
        // COLLISION CONTROL: Clear client busy flag and channel
        isClientBusy = false
        outgoingChannel = null
        
        // Clean up BinaryFraming reassembly buffers
        BinaryFraming.cleanup()
        
        Log.d(TAG, "✅ Client reset complete")
    }
    
    fun startGattServer() {
        if (!hasConnectPermission()) {
            Log.e(TAG, "❌ No BLUETOOTH_CONNECT permission")
            return
        }
        
        try {
            Log.d(TAG, "🔧 Starting GATT server with Aura service")
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            
            val service = BluetoothGattService(
                AuraProtocol.AURA_GATT_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            // COMPATIBILITY FIX: All characteristics need READ+WRITE+WRITE_NO_RESPONSE for discovery compatibility
            val requestCharacteristic = BluetoothGattCharacteristic(
                AuraProtocol.MATCH_REQUEST_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or 
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or 
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            val responseCharacteristic = BluetoothGattCharacteristic(
                AuraProtocol.MATCH_RESPONSE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or 
                BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            val chatCharacteristic = BluetoothGattCharacteristic(
                AuraProtocol.CHAT_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or 
                BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // COMPATIBILITY FIX: Add CCCD descriptor to ALL characteristics for discovery compatibility
            val cccdUuid = AuraProtocol.CCCD_UUID
            
            val requestCccd = BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            requestCccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            requestCharacteristic.addDescriptor(requestCccd)
            
            val responseCccd = BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            responseCccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            responseCharacteristic.addDescriptor(responseCccd)
            
            val chatCccd = BluetoothGattDescriptor(
                cccdUuid,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            chatCccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            chatCharacteristic.addDescriptor(chatCccd)
            
            service.addCharacteristic(requestCharacteristic)
            service.addCharacteristic(responseCharacteristic)
            service.addCharacteristic(chatCharacteristic)
            
            val success = gattServer?.addService(service) ?: false
            Log.d(TAG, "✅ GATT server started with compatibility characteristics: $success")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception starting GATT server", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception starting GATT server", e)
        }
    }
    
    fun sendMatchRequest(device: BluetoothDevice) {
        Log.d(TAG, "🚀 SEND_REQUEST to ${device.address}")
        Log.d(TAG, "📤 MATCH_REQUEST_INIT: target_address=${device.address}, client_state=$clientState, retry_count=$retryCount")
        
        // COLLISION CONTROL: Check if client is busy
        if (isClientBusy) {
            Log.w(TAG, "⚠️ Client busy, cannot send match request now")
            return
        }
        
        // ALWAYS reset client state first - CRITICAL for preventing "connection lost"
        resetClient()
        
        // Set pending device for retries
        pendingDevice = device
        
        // PROTOCOL FIX: Send sender's own hash and gender (NOT target hash)
        val senderId = userPrefs.getUserId()
        val senderGender = userPrefs.getGender() ?: "U"
        val genderByte = when (senderGender.uppercase()) {
            "M", "MALE" -> AuraProtocol.GENDER_MALE
            "F", "FEMALE" -> AuraProtocol.GENDER_FEMALE
            else -> AuraProtocol.GENDER_UNKNOWN
        }
        
        val senderHash = getUserHash(senderId) // Send OUR hash, not target hash
        val senderHashHex = senderHash.joinToString("") { "%02x".format(it) }
        val chunks = BinaryFraming.createMatchRequest(senderHash, genderByte)
        pendingMessage = chunks
        
        // CHARACTERISTIC SELECTION FIX: Set outgoing channel
        outgoingChannel = OutgoingChannel.REQUEST
        
        Log.d(TAG, "📦 SEND_REQUEST to ${device.address} SENDER_HASH_HEX=$senderHashHex")
        Log.d(TAG, "📦 OUTGOING channel=Request, chunks=${chunks.size}")
        Log.d(TAG, "📦 Created ${chunks.size} chunks for match request (${chunks.sumOf { it.size }} total bytes) - sending SENDER hash: $senderHashHex")
        Log.d(TAG, "📦 TARGET device address: ${device.address}")
        Log.d(TAG, "🔍 PROOF: Sending request - myHashHex=$senderHashHex, targetDevice=${device.address}")
        
        connectToDevice(device)
    }
    
    /**
     * DEPRECATED: Old client-write approach - replaced by sendMatchResponseToRequester
     * Kept for compatibility but should not be used
     */
    @Deprecated("Use sendMatchResponseToRequester instead")
    fun sendMatchResponse(accepted: Boolean, targetUserId: String, targetDevice: BluetoothDevice?) {
        Log.w(TAG, "⚠️ DEPRECATED: sendMatchResponse called - use sendMatchResponseToRequester instead")
        targetDevice?.let { device ->
            // Find requester hash from deviceResponseMap (reverse lookup)
            val requesterHashHex = deviceResponseMap.entries.find { it.value.address == device.address }?.key
            if (requesterHashHex != null) {
                sendMatchResponseToRequester(requesterHashHex, accepted)
            } else {
                Log.e(TAG, "❌ Cannot find requester hash for device: ${device.address}")
            }
        }
    }
    
    /**
     * DEPRECATED: Simple overload - replaced by sendMatchResponseToRequester
     */
    @Deprecated("Use sendMatchResponseToRequester instead")
    fun sendMatchResponse(device: BluetoothDevice, accepted: Boolean) {
        Log.w(TAG, "⚠️ DEPRECATED: sendMatchResponse called - use sendMatchResponseToRequester instead")
        // Find requester hash from deviceResponseMap (reverse lookup)
        val requesterHashHex = deviceResponseMap.entries.find { it.value.address == device.address }?.key
        if (requesterHashHex != null) {
            sendMatchResponseToRequester(requesterHashHex, accepted)
        } else {
            Log.e(TAG, "❌ Cannot find requester hash for device: ${device.address}")
        }
    }
    
    fun sendChatMessage(device: BluetoothDevice, message: String) {
        Log.d(TAG, "💬 Sending chat message to: ${device.address}")
        
        // COLLISION CONTROL: Check if client is busy
        if (isClientBusy) {
            Log.w(TAG, "⚠️ Client busy, cannot send chat message now")
            return
        }
        
        // Reset client state
        resetClient()
        
        // Set pending device
        pendingDevice = device
        
        // Create binary chat message
        val currentUserId = userPrefs.getUserId()
        val userHash = getUserHash(currentUserId)
        val chunks = BinaryFraming.createChatMessage(message, userHash)
        pendingMessage = chunks
        
        // CHARACTERISTIC SELECTION FIX: Set outgoing channel
        outgoingChannel = OutgoingChannel.CHAT
        
        Log.d(TAG, "📦 OUTGOING channel=Chat, chunks=${chunks.size}")
        
        Log.d(TAG, "� Created ${chunks.size} chunks for chat message (${chunks.sumOf { it.size }} total bytes)")
        
        connectToDevice(device)
    }
    
    /**
     * Send unmatch message to a user
     */
    fun sendUnmatchMessage(userHashHex: String) {
        Log.d(TAG, "💔 Sending unmatch to: $userHashHex")
        
        // Find device from stored matches or deviceResponseMap
        val device = deviceResponseMap[userHashHex]
        if (device == null) {
            Log.w(TAG, "⚠️ Cannot send unmatch - device not found for: $userHashHex")
            return
        }
        
        // COLLISION CONTROL: Check if client is busy
        if (isClientBusy) {
            Log.w(TAG, "⚠️ Client busy, cannot send unmatch now")
            return
        }
        
        // Reset client state
        resetClient()
        
        // Set pending device
        pendingDevice = device
        
        // Create unmatch message with our own hash
        val currentUserId = userPrefs.getUserId()
        val myHash = getUserHash(currentUserId)
        val chunks = BinaryFraming.createUnmatchMessage(myHash)
        pendingMessage = chunks
        
        // Set outgoing channel
        outgoingChannel = OutgoingChannel.REQUEST // Reuse request channel
        
        Log.d(TAG, "📦 Created ${chunks.size} chunks for unmatch message")
        
        connectToDevice(device)
    }
    
    /**
     * Send block message to a user
     */
    fun sendBlockMessage(userHashHex: String) {
        Log.d(TAG, "🚫 Sending block to: $userHashHex")
        
        // Find device from stored matches or deviceResponseMap
        val device = deviceResponseMap[userHashHex]
        if (device == null) {
            Log.w(TAG, "⚠️ Cannot send block - device not found for: $userHashHex")
            return
        }
        
        // COLLISION CONTROL: Check if client is busy
        if (isClientBusy) {
            Log.w(TAG, "⚠️ Client busy, cannot send block now")
            return
        }
        
        // Reset client state
        resetClient()
        
        // Set pending device
        pendingDevice = device
        
        // Create block message with our own hash
        val currentUserId = userPrefs.getUserId()
        val myHash = getUserHash(currentUserId)
        val chunks = BinaryFraming.createBlockMessage(myHash)
        pendingMessage = chunks
        
        // Set outgoing channel
        outgoingChannel = OutgoingChannel.REQUEST // Reuse request channel
        
        Log.d(TAG, "📦 Created ${chunks.size} chunks for block message")
        
        connectToDevice(device)
    }
    
    // ========== HELPER METHODS ==========
    
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            failAndReset("Bluetooth connect permission not granted")
            return
        }
        
        if (clientState != ClientState.IDLE) {
            Log.w(TAG, "⚠️ Connection attempt in wrong state: $clientState")
            return
        }
        
        // COLLISION CONTROL: Set client busy flag
        isClientBusy = true
        
        clientState = ClientState.CONNECTING
        
        connectTimeoutRunnable = Runnable {
            Log.e(TAG, "❌ Connection timeout")
            clientState = ClientState.ERROR
            gattClient?.close()
            gattClient = null
            handleConnectionError("Connection timeout")
        }
        handler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
        
        try {
            Log.d(TAG, "🔗 Connecting to: ${device.address} (attempt ${retryCount + 1})")
            
            gattClient = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattClientCallback)
            }
            
            Log.d(TAG, "✅ GATT connection initiated with TRANSPORT_LE")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception connecting", e)
            clientState = ClientState.ERROR
            cancelConnectTimeout()
            failAndReset("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception connecting", e)
            clientState = ClientState.ERROR
            cancelConnectTimeout()
            failAndReset("Failed to connect: ${e.message}")
        }
    }
    
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (clientState != ClientState.CONNECTED) {
            Log.w(TAG, "⚠️ Discovery attempt in wrong state: $clientState")
            return
        }
        
        clientState = ClientState.DISCOVERING
        
        discoverTimeoutRunnable = Runnable {
            Log.e(TAG, "❌ Service discovery timeout - performing robust state reset")
            failAndReset("Service discovery timeout")
        }
        handler.postDelayed(discoverTimeoutRunnable!!, DISCOVER_TIMEOUT_MS)
        
        try {
            Log.d(TAG, "🔍 Starting service discovery...")
            val result = gatt.discoverServices()
            Log.d(TAG, "🔍 Discovery initiated: $result")
            
            if (!result) {
                cancelDiscoverTimeout()
                clientState = ClientState.ERROR
                failAndReset("Failed to start service discovery")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception during discovery", e)
            cancelDiscoverTimeout()
            clientState = ClientState.ERROR
            failAndReset("Permission denied for service discovery")
        }
    }
    
    private fun sendNextChunk() {
        val chunks = pendingMessage
        if (chunks == null || chunks.isEmpty()) {
            Log.d(TAG, "✅ All chunks sent successfully")
            clientState = ClientState.DONE
            retryCount = 0
            
            // COLLISION CONTROL: Clear client busy flag
            isClientBusy = false
            outgoingChannel = null
            
            // Clean up device reference
            deviceResponseMap.values.removeAll { it == pendingDevice }
            pendingDevice = null
            
            return
        }
        
        // STRICT STATE CHECK: Only send when READY and characteristics cached
        if (clientState != ClientState.READY) {
            Log.w(TAG, "⚠️ Cannot send chunk - client not ready: $clientState")
            return
        }
        
        val nextChunk = chunks.first()
        pendingMessage = chunks.drop(1)
        
        // CHARACTERISTIC SELECTION FIX: Use outgoingChannel instead of chunk[0]
        val characteristic = when (outgoingChannel) {
            OutgoingChannel.REQUEST -> cachedRequestChar
            OutgoingChannel.RESPONSE -> cachedResponseChar
            OutgoingChannel.CHAT -> cachedChatChar
            null -> {
                Log.e(TAG, "❌ No outgoing channel set")
                cachedRequestChar // fallback
            }
        }
        
        if (characteristic == null) {
            Log.e(TAG, "❌ No cached characteristic available - client not ready")
            handleConnectionError("No characteristic available")
            return
        }
        
        Log.d(TAG, "📤 SEND chunk index=${chunks.size - pendingMessage!!.size} characteristic=${characteristic.uuid}")
        
        try {
            clientState = ClientState.WRITING
            
            // Try WRITE_TYPE_DEFAULT first, fallback to NO_RESPONSE if it fails
            var writeResult = false
            
            // First attempt with WRITE_TYPE_DEFAULT
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = nextChunk
            writeResult = gattClient?.writeCharacteristic(characteristic) ?: false
            
            Log.d(TAG, "📝 Writing chunk (DEFAULT): ${nextChunk.size} bytes, result=$writeResult")
            
            if (!writeResult) {
                // Fallback to WRITE_TYPE_NO_RESPONSE
                Log.d(TAG, "🔄 Retrying with WRITE_TYPE_NO_RESPONSE")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                writeResult = gattClient?.writeCharacteristic(characteristic) ?: false
                Log.d(TAG, "📝 Writing chunk (NO_RESPONSE): ${nextChunk.size} bytes, result=$writeResult")
            }
            
            if (!writeResult) {
                clientState = ClientState.ERROR
                failAndReset("Failed to initiate write with both write types")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception sending chunk", e)
            clientState = ClientState.ERROR
            failAndReset("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception sending chunk", e)
            clientState = ClientState.ERROR
            failAndReset("Failed to send chunk: ${e.message}")
        }
    }
    
    private fun tryGattRefresh(gatt: BluetoothGatt) {
        try {
            Log.d(TAG, "🔄 Attempting Samsung GATT refresh...")
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val refreshResult = refreshMethod.invoke(gatt) as Boolean
            Log.d(TAG, "🔄 GATT refresh result: $refreshResult")
        } catch (e: Exception) {
            Log.d(TAG, "GATT refresh not available: ${e.message}")
        }
    }
    
    private fun clearCachedCharacteristics() {
        cachedAuraService = null
        cachedRequestChar = null
        cachedResponseChar = null
        cachedChatChar = null
    }
    
    private fun cancelConnectTimeout() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }
    
    private fun cancelDiscoverTimeout() {
        discoverTimeoutRunnable?.let { handler.removeCallbacks(it) }
        discoverTimeoutRunnable = null
    }
    
    private fun cancelAllTimeouts() {
        cancelConnectTimeout()
        cancelDiscoverTimeout()
        cancelMtuTimeout()
    }
    
    private fun cancelMtuTimeout() {
        mtuTimeoutRunnable?.let { handler.removeCallbacks(it) }
        mtuTimeoutRunnable = null
    }
    
    /**
     * Robust state reset function - handles ALL connection failures
     * Ensures client state is properly reset and next request can proceed
     */
    private fun failAndReset(errorMsg: String) {
        Log.e(TAG, "🔌 FAIL_AND_RESET: $errorMsg")
        
        // Cancel all timeouts first
        cancelAllTimeouts()
        
        // Clear cached characteristics
        clearCachedCharacteristics()
        
        // Close GATT client safely
        try {
            gattClient?.disconnect()
            gattClient?.close()
            Log.d(TAG, "🧹 GATT client closed safely")
        } catch (e: Exception) {
            Log.w(TAG, "Exception closing GATT client", e)
        }
        gattClient = null
        
        // Reset client state to IDLE (critical for next request)
        clientState = ClientState.IDLE
        
        // Clear collision control flags
        isClientBusy = false
        outgoingChannel = null
        
        // Clear pending data (but keep pendingDevice for potential retry)
        pendingMessage = null
        
        // Clean up BinaryFraming reassembly buffers
        BinaryFraming.cleanup()
        
        Log.d(TAG, "✅ FAIL_AND_RESET: State reset complete, ready for next request")
        
        // Notify listener AFTER cleanup
        matchRequestListener?.onConnectionError(errorMsg)
    }
    
    @Deprecated("Use failAndReset instead")
    private fun handleConnectionError(error: String) {
        failAndReset(error)
    }
    
    /**
     * Send match response to requester using GATT client WRITE (reliable, no subscription dependency)
     * This is the preferred method for UI to call when user accepts/rejects
     */
    fun sendMatchResponseToRequester(requesterHashHex: String, accepted: Boolean) {
        Log.d(TAG, "📤 SEND_RESPONSE accepted=$accepted toRequesterHashHex=$requesterHashHex")
        
        // Find BluetoothDevice from deviceResponseMap
        val device = deviceResponseMap[requesterHashHex]
        if (device == null) {
            Log.e(TAG, "❌ Device not found for requester hash: $requesterHashHex")
            Log.e(TAG, "❌ LOOKUP FAILED: deviceResponseMap keys: ${deviceResponseMap.keys}")
            return
        }
        
        Log.d(TAG, "✅ LOOKUP SUCCESS: Found device for $requesterHashHex -> ${device.address}")
        
        // COLLISION CONTROL: Check if client is busy
        if (isClientBusy) {
            Log.w(TAG, "⚠️ Client busy, cannot send response now")
            return
        }
        
        // Reset client state
        resetClient()
        
        // Set pending device
        pendingDevice = device
        
        // Create response payload with responder's own hash and gender
        val currentUserId = userPrefs.getUserId()
        val currentGender = userPrefs.getGender() ?: "U"
        val genderByte = when (currentGender.uppercase()) {
            "M", "MALE" -> AuraProtocol.GENDER_MALE
            "F", "FEMALE" -> AuraProtocol.GENDER_FEMALE
            else -> AuraProtocol.GENDER_UNKNOWN
        }
        
        val responderHash = getUserHash(currentUserId)
        val responderHashHex = responderHash.joinToString("") { "%02x".format(it) }
        val chunks = BinaryFraming.createMatchResponse(accepted, responderHash, genderByte)
        pendingMessage = chunks
        
        // CHARACTERISTIC SELECTION FIX: Set outgoing channel for response
        outgoingChannel = OutgoingChannel.RESPONSE
        
        Log.d(TAG, "📦 RESPONSE responderHashHex=$responderHashHex, accepted=$accepted")
        Log.d(TAG, "📦 OUTGOING channel=Response, chunks=${chunks.size}")
        Log.d(TAG, "📦 Created ${chunks.size} chunks for response")
        Log.d(TAG, "🔍 PROOF: Sending response - accepted=$accepted, myHashHex=$responderHashHex, requesterHashHex=$requesterHashHex, device=${device.address}")
        
        // Connect to requester device and send response
        connectToDevice(device)
    }
    
    private fun getUserHash(userId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userId.toByteArray())
        return fullHash.copyOf(8)
    }
    
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun safeSendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ): Boolean {
        if (!hasConnectPermission()) {
            Log.e(TAG, "❌ No BLUETOOTH_CONNECT permission for response")
            return false
        }
        
        return try {
            gattServer?.sendResponse(device, requestId, status, offset, value) ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception sending GATT response", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending GATT response", e)
            false
        }
    }
    
    fun cleanup() {
        try {
            cancelAllTimeouts()
            deviceResponseMap.clear()
            clearCachedCharacteristics()
            BinaryFraming.cleanup()
            
            if (hasConnectPermission()) {
                gattClient?.close()
                gattServer?.close()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception during cleanup", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during cleanup", e)
        }
        
        gattClient = null
        gattServer = null
        matchRequestListener = null
        pendingDevice = null
        pendingMessage = null
        clientState = ClientState.IDLE
        retryCount = 0
    }
}