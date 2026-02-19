package com.aura.link

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global singleton manager for match requests and notifications
 * Always active regardless of Activity lifecycle
 * Updated for BLE-only architecture
 */
object MatchRequestManager : AuraGattService.MatchRequestListener {
    
    private const val TAG = "MatchRequestManager"
    private const val NOTIFICATION_CHANNEL_ID = "aura_match_requests"
    private const val NOTIFICATION_ID = 1001
    
    private var context: Context? = null
    private var matchStore: MatchStore? = null
    private var chatStore: ChatStore? = null
    private var notificationManager: NotificationManagerCompat? = null
    
    // ENHANCED: Prevent duplicate notifications for the same message with stricter controls
    private val processedNotificationIds = mutableSetOf<String>()
    private val notificationTimeouts = mutableMapOf<String, Long>()
    private val NOTIFICATION_TIMEOUT = 60000L // 1 minute timeout for notifications
    
    // Additional tracking for match requests to prevent spam
    private val matchRequestNotificationTracker = mutableMapOf<String, Long>()
    private val MATCH_REQUEST_NOTIFICATION_COOLDOWN = 120000L // 2 minutes cooldown
    
    // State flows for UI updates
    private val _pendingRequestCount = MutableStateFlow(0)
    val pendingRequestCount: StateFlow<Int> = _pendingRequestCount.asStateFlow()
    
    private val _newRequestEvent = MutableStateFlow<String?>(null)
    val newRequestEvent: StateFlow<String?> = _newRequestEvent.asStateFlow()
    
    // Current activity listener (for immediate dialogs)
    private var currentActivityListener: MatchRequestListener? = null
    
    interface MatchRequestListener {
        fun onMatchRequestReceived(fromUserHashHex: String, fromGender: String)
        fun onMatchAccepted(userHashHex: String)
        fun onMatchRejected(userHashHex: String)
    }
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.matchStore = MatchStore(context)
        this.chatStore = ChatStore(context)
        this.notificationManager = NotificationManagerCompat.from(context)
        
        createNotificationChannel()
        updatePendingRequestCount()
        
        // Set up BLE Engine listener
        BleEngineManager.initialize(context)
        BleEngineManager.addListener(BleEngineListener())
        
        Log.d(TAG, "MatchRequestManager initialized with BLE Engine")
    }
    
    fun setCurrentActivityListener(listener: MatchRequestListener?) {
        currentActivityListener = listener
        Log.d(TAG, "Current activity listener set: ${listener != null}")
    }
    
    // BLE Engine listener for handling match events
    private class BleEngineListener : BleEngine.BleEngineListener {
        override fun onIncomingMatchRequest(senderHash: String) {
            Log.d(TAG, "📥 BLE_LISTENER: INCOMING_MATCH_REQUEST received from: $senderHash")
            
            // ENHANCED: Prevent duplicate processing with stricter time-based deduplication
            val currentTime = System.currentTimeMillis()
            
            // Check match request notification cooldown
            val lastNotification = matchRequestNotificationTracker[senderHash]
            if (lastNotification != null && (currentTime - lastNotification) < MATCH_REQUEST_NOTIFICATION_COOLDOWN) {
                Log.d(TAG, "🚫 BLE_LISTENER: MATCH_REQ_COOLDOWN active for: $senderHash, ignoring")
                return
            }
            
            val requestKey = "${senderHash}_match_req_${currentTime / 60000}" // 1-minute window
            
            if (processedNotificationIds.contains(requestKey)) {
                Log.d(TAG, "🚫 BLE_LISTENER: DUPLICATE match request prevented for: $senderHash")
                return
            }
            
            // Clean up old notification IDs
            cleanupOldNotificationIds(currentTime)
            
            processedNotificationIds.add(requestKey)
            notificationTimeouts[requestKey] = currentTime
            matchRequestNotificationTracker[senderHash] = currentTime
            
            // Play test notification sound for match request
            val soundEngine = AuraSoundEngine(context ?: return)
            soundEngine.playTestNotification()
            
            val store = matchStore
            val ctx = context
            
            if (store != null && ctx != null) {
                // Check if we already have a pending request from this user
                val existingRequests = store.getPendingRequests()
                val existingRequest = existingRequests.find { it.fromUserHash == senderHash }
                
                if (existingRequest != null) {
                    Log.d(TAG, "🚫 BLE_LISTENER: Request already exists from: $senderHash, skipping")
                    return
                }
                
                // Get gender from BLE Engine's nearby users cache
                val nearbyUsers = BleEngineManager.getInstance()?.nearbyUsersFlow?.value ?: emptyList()
                val user = nearbyUsers.find { it.userHash == senderHash }
                val senderGender = user?.gender ?: "U"
                
                Log.d(TAG, "📝 BLE_LISTENER: Detected sender gender: $senderGender")
                
                // Store the request with proper gender
                val request = store.storePendingRequest(senderHash, senderGender)
                Log.d(TAG, "📝 BLE_LISTENER: REQUEST stored: ${request.id}")
                
                // Update UI state
                updatePendingRequestCount()
                _newRequestEvent.value = senderHash
                
                // Show notification ONLY ONCE
                showMatchRequestNotification(senderGender)
                Log.d(TAG, "🔔 BLE_LISTENER: SINGLE NOTIFICATION posted")
                
                // If there's an active activity listener, also show immediate dialog
                currentActivityListener?.onMatchRequestReceived(senderHash, senderGender)
            } else {
                Log.e(TAG, "❌ BLE_LISTENER: MatchStore or Context not initialized")
            }
        }
        
        override fun onMatchAccepted(senderHash: String) {
            Log.d(TAG, "✅ BLE_LISTENER: Match accepted by: $senderHash")
            
            // Play match notification sound
            val soundEngine = AuraSoundEngine(context ?: return)
            soundEngine.playMatchNotification()
            
            // CRITICAL FIX: Store match on sender side when acceptance received
            val store = matchStore
            val ctx = context
            
            if (store != null && ctx != null) {
                Log.d(TAG, "📝 BLE_LISTENER: SENDER side - storing match for accepted response from: $senderHash")
                
                // Get gender from BLE Engine's nearby users cache
                val nearbyUsers = BleEngineManager.getInstance()?.nearbyUsersFlow?.value ?: emptyList()
                val user = nearbyUsers.find { it.userHash == senderHash }
                val senderGender = user?.gender ?: "U"
                
                Log.d(TAG, "📝 BLE_LISTENER: Found user in nearby list: ${user != null}, gender: $senderGender")
                
                // Store match on sender side (the one who sent the original request)
                val currentUserPrefs = getUserPreferences()
                val currentUserId = currentUserPrefs.getUserId()
                val currentUserHash = getUserHash(currentUserId)
                
                Log.d(TAG, "📝 BLE_LISTENER: Current user ID: $currentUserId")
                Log.d(TAG, "📝 BLE_LISTENER: Current user hash: $currentUserHash")
                
                // Create deterministic match ID (same on both devices)
                val matchId = createDeterministicMatchId(currentUserHash, senderHash)
                Log.d(TAG, "📝 BLE_LISTENER: Created deterministic match ID: $matchId")
                
                // Get user name from BLE Engine cache or generate default
                val userName = user?.userName ?: "User${senderHash.take(4).uppercase()}"
                Log.d(TAG, "📝 BLE_LISTENER: User name: $userName")
                
                val match = MatchStore.Match(
                    id = matchId,
                    userHash = senderHash,
                    userName = userName,
                    gender = senderGender,
                    deviceAddress = null
                )
                
                Log.d(TAG, "📝 BLE_LISTENER: About to store match: $match")
                store.storeMatch(match)
                Log.d(TAG, "📝 BLE_LISTENER: SENDER side match stored successfully: ${match.id}")
                
                // Verify match was stored
                val allMatches = store.getMatches()
                Log.d(TAG, "📝 BLE_LISTENER: Total matches after sender storage: ${allMatches.size}")
                allMatches.forEach { m ->
                    Log.d(TAG, "📝 BLE_LISTENER: Match in store: ID=${m.id}, userHash=${m.userHash}, userName=${m.userName}")
                }
            } else {
                Log.e(TAG, "❌ BLE_LISTENER: MatchStore or Context not initialized for sender side storage")
            }
            
            // Notify current activity
            currentActivityListener?.onMatchAccepted(senderHash)
        }
        
        override fun onMatchRejected(senderHash: String) {
            Log.d(TAG, "❌ BLE_LISTENER: Match rejected by: $senderHash")
            
            // Notify current activity
            currentActivityListener?.onMatchRejected(senderHash)
        }
        
        override fun onChatMessage(senderHash: String, message: String) {
            Log.d(TAG, "💬 BLE_LISTENER: Chat message received from: $senderHash")
            
            // ENHANCED: Stricter duplicate prevention for notifications
            val messageContent = message.trim()
            val contentHash = messageContent.hashCode()
            val currentTime = System.currentTimeMillis()
            val timeWindow = currentTime / 15000 // 15-second window (increased)
            val notificationId = "${senderHash}_${contentHash}_${timeWindow}"
            
            if (processedNotificationIds.contains(notificationId)) {
                Log.d(TAG, "🚫 BLE_LISTENER: DUPLICATE notification prevented for: $notificationId")
                return
            }
            
            processedNotificationIds.add(notificationId)
            notificationTimeouts[notificationId] = currentTime
            Log.d(TAG, "✅ BLE_LISTENER: NEW notification: $notificationId")
            
            // Clean up old notification IDs
            cleanupOldNotificationIds(currentTime)
            
            // CRITICAL FIX: Store message in ChatStore for ALL matches
            val store = matchStore
            val ctx = context
            
            if (store != null && ctx != null) {
                // Find match ID for this user
                val matches = store.getMatches()
                val match = matches.find { it.userHash == senderHash }
                
                if (match != null) {
                    Log.d(TAG, "💾 BLE_LISTENER: Storing message in ChatStore for match: ${match.id}")
                    
                    // ENHANCED: Check if message already exists to prevent duplicates
                    val existingMessages = chatStore?.getMessages(match.id) ?: emptyList()
                    val messageAlreadyExists = existingMessages.any { 
                        it.content == messageContent && 
                        !it.isFromMe && 
                        (currentTime - it.timestamp) < 15000 // 15 seconds window
                    }
                    
                    if (messageAlreadyExists) {
                        Log.d(TAG, "🚫 BLE_LISTENER: Message already exists in ChatStore, skipping storage")
                        return
                    }
                    
                    // Create unique message ID to prevent duplicates
                    val uniqueMessageId = "${senderHash}_${currentTime}_${contentHash}_${Math.random().toInt()}"
                    
                    val chatMessage = ChatStore.ChatMessage(
                        id = uniqueMessageId,
                        matchId = match.id,
                        senderId = senderHash,
                        receiverId = getUserPreferences().getUserId(),
                        content = messageContent,
                        timestamp = currentTime,
                        isFromMe = false,
                        status = ChatStore.MessageStatus.DELIVERED
                    )
                    
                    chatStore?.storeMessage(chatMessage)
                    Log.d(TAG, "💾 BLE_LISTENER: Message stored successfully with ID: $uniqueMessageId")
                } else {
                    Log.w(TAG, "❌ BLE_LISTENER: No match found for message sender: $senderHash")
                }
            } else {
                Log.w(TAG, "❌ BLE_LISTENER: Store or context is null, cannot store message")
            }
            
            // Play message notification sound ONLY ONCE
            val soundEngine = AuraSoundEngine(context ?: return)
            soundEngine.playMessageNotification()
            
            // Show notification ONCE
            showChatNotification(senderHash, message)
            
            Log.d(TAG, "💬 BLE_LISTENER: Message notification shown ONCE, message stored in ChatStore")
        }
        
        override fun onPhotoReceived(senderHash: String, photoBase64: String) {
            Log.d(TAG, "📷 BLE_LISTENER: Photo received from: $senderHash, size: ${photoBase64.length}")
            
            // Cache the received photo
            val success = ProfilePhotoManager.cachePhotoFromBase64(senderHash, photoBase64)
            if (success) {
                Log.d(TAG, "✅ BLE_LISTENER: Photo cached successfully for: $senderHash")
            } else {
                Log.e(TAG, "❌ BLE_LISTENER: Failed to cache photo for: $senderHash")
            }
        }
        
        override fun onPhotoRequested(senderHash: String) {
            Log.d(TAG, "📷 BLE_LISTENER: Photo requested by: $senderHash")
            
            // Send current user's photo if available - DO THIS IN BACKGROUND THREAD
            val ctx = context
            if (ctx != null) {
                // Use background thread to prevent UI blocking and crashes
                Thread {
                    try {
                        // Additional safety check - ensure context is still valid
                        if (ctx.applicationContext != null) {
                            Log.d(TAG, "📷 RESPONSE: Getting current user photo for: $senderHash")
                            val photoBase64 = ProfilePhotoManager.getCurrentUserPhotoAsBase64(ctx)
                            
                            if (photoBase64 != null) {
                                Log.d(TAG, "📷 RESPONSE: Photo found, size: ${photoBase64.length} chars")
                                if (photoBase64.length < 10000) { // Safety limit: max 10KB base64
                                    Log.d(TAG, "📷 RESPONSE: Sending photo to requester: $senderHash")
                                    BleEngineManager.getInstance()?.enqueuePhoto(senderHash, photoBase64)
                                    Log.d(TAG, "📷 RESPONSE: Photo enqueued successfully for: $senderHash")
                                } else {
                                    Log.w(TAG, "📷 RESPONSE: Photo too large (${photoBase64.length} chars), not sending to: $senderHash")
                                }
                            } else {
                                Log.w(TAG, "📷 RESPONSE: No photo available for user, cannot send to: $senderHash")
                            }
                        } else {
                            Log.w(TAG, "📷 RESPONSE: Context invalid, cannot process photo request from: $senderHash")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing photo request from: $senderHash", e)
                    }
                }.start()
            } else {
                Log.w(TAG, "📷 RESPONSE: Context is null, cannot process photo request from: $senderHash")
            }
        }
        
        override fun onUnmatchReceived(senderHash: String) {
            try {
                Log.d(TAG, "💔 BLE_LISTENER: Unmatch received from: $senderHash")
                
                val store = matchStore
                val ctx = context
                
                if (store != null && ctx != null) {
                    // Remove match locally
                    store.removeMatch(senderHash)
                    
                    // Clear chat history
                    chatStore?.clearChatHistory(senderHash)
                    
                    Log.d(TAG, "💔 BLE_LISTENER: Processed unmatch from: $senderHash")
                    
                    // Show notification
                    showUnmatchNotification(senderHash)
                } else {
                    Log.w(TAG, "💔 BLE_LISTENER: Store or context is null, cannot process unmatch")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing unmatch from $senderHash", e)
            }
        }
        
        override fun onBlockReceived(senderHash: String) {
            try {
                Log.d(TAG, "🚫 BLE_LISTENER: Block received from: $senderHash")
                
                val store = matchStore
                val ctx = context
                
                if (store != null && ctx != null) {
                    // Remove match locally
                    store.removeMatch(senderHash)
                    
                    // Clear chat history
                    chatStore?.clearChatHistory(senderHash)
                    
                    // Add to block list to prevent future interactions
                    val blockStore = BlockStore(ctx)
                    blockStore.blockUser(senderHash)
                    
                    Log.d(TAG, "🚫 BLE_LISTENER: Processed block from: $senderHash")
                    
                    // Show notification
                    showBlockNotification(senderHash)
                } else {
                    Log.w(TAG, "🚫 BLE_LISTENER: Store or context is null, cannot process block")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing block from $senderHash", e)
            }
        }
    }
    
    private fun getUserPreferences(): UserPreferences {
        return UserPreferences(context ?: throw IllegalStateException("Context not initialized"))
    }
    
    private fun showChatNotification(fromUserHashHex: String, message: String) {
        val ctx = context ?: return
        
        Log.d(TAG, "🔔 CHAT_NOTIFICATION: Creating notification for: $fromUserHashHex")
        
        // Additional duplicate check for notifications - prevent same message notification multiple times
        val messageContent = message.trim()
        val notificationKey = "${fromUserHashHex}_${messageContent.hashCode()}_${System.currentTimeMillis() / 15000}" // 15-second window
        
        // Check if we already showed notification for this message recently
        if (processedNotificationIds.contains("notif_$notificationKey")) {
            Log.d(TAG, "🚫 CHAT_NOTIFICATION: Duplicate notification prevented for: $notificationKey")
            return
        }
        
        processedNotificationIds.add("notif_$notificationKey")
        
        // Find the match for this user
        val matches = matchStore?.getMatches() ?: emptyList()
        Log.d(TAG, "🔔 CHAT_NOTIFICATION: Total matches available: ${matches.size}")
        matches.forEach { match ->
            Log.d(TAG, "🔔 CHAT_NOTIFICATION: Available match - userHash: ${match.userHash}, id: ${match.id}")
        }
        
        val match = matches.find { it.userHash == fromUserHashHex }
        
        Log.d(TAG, "🔔 CHAT_NOTIFICATION: Found match: ${match?.id} for user: $fromUserHashHex")
        
        if (match != null) {
            // Create intent to open ChatActivity with correct parameters
            val intent = Intent(ctx, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ChatActivity.EXTRA_MATCH_ID, match.id)
                putExtra(ChatActivity.EXTRA_PARTNER_HASH, match.userHash)
                putExtra(ChatActivity.EXTRA_PARTNER_GENDER, match.gender)
            }
            
            Log.d(TAG, "🔔 CHAT_NOTIFICATION: Intent created with matchId=${match.id}, partnerHash=${match.userHash}, gender=${match.gender}")
            
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                fromUserHashHex.hashCode(), // Unique request code for each user
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Get user name from match or BLE cache
            val userName = match.userName.takeIf { it.isNotBlank() } 
                ?: BleEngineManager.getInstance()?.nearbyUsersFlow?.value?.find { it.userHash == fromUserHashHex }?.userName
                ?: "User${fromUserHashHex.take(4).uppercase()}"
            
            // Create better notification text
            val notificationTitle = "AURA - $userName"
            val notificationText = message.take(100) // Show more of the message
            
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 200, 100, 200))
                .build()
            
            try {
                // Use unique notification ID for each user to prevent overwriting
                val notificationId = NOTIFICATION_ID + 1000 + fromUserHashHex.hashCode()
                notificationManager?.notify(notificationId, notification)
                Log.d(TAG, "🔔 CHAT_NOTIFICATION: Notification posted with ID: $notificationId")
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission not granted", e)
            }
        } else {
            Log.w(TAG, "❌ CHAT_NOTIFICATION: No match found for user: $fromUserHashHex")
            Log.d(TAG, "❌ CHAT_NOTIFICATION: Available matches: ${matches.map { "${it.userHash} -> ${it.id}" }}")
            
            // FALLBACK: Create a generic chat intent without match ID
            Log.w(TAG, "❌ CHAT_NOTIFICATION: Creating fallback notification - will open main activity")
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Add extra info for debugging
                putExtra("DEBUG_MISSING_MATCH", fromUserHashHex)
                putExtra("DEBUG_MESSAGE", message)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                fromUserHashHex.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heart)
                .setContentTitle("AURA - Yeni Mesaj")
                .setContentText("${message.take(100)} (Debug: Match bulunamadı)")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            
            try {
                val notificationId = NOTIFICATION_ID + 1000 + fromUserHashHex.hashCode()
                notificationManager?.notify(notificationId, notification)
                Log.d(TAG, "🔔 CHAT_NOTIFICATION: Fallback notification posted - will open MainActivity")
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission not granted", e)
            }
        }
    }
    
    private fun showUnmatchNotification(fromUserHashHex: String) {
        val ctx = context ?: return
        
        Log.d(TAG, "🔔 UNMATCH_NOTIFICATION: Creating notification for: $fromUserHashHex")
        
        // Find the match for this user to get their name
        val matches = matchStore?.getMatches() ?: emptyList()
        val match = matches.find { it.userHash == fromUserHashHex }
        
        // Get user name
        val userName = match?.userName?.takeIf { it.isNotBlank() } 
            ?: BleEngineManager.getInstance()?.nearbyUsersFlow?.value?.find { it.userHash == fromUserHashHex }?.userName
            ?: "User${fromUserHashHex.take(4).uppercase()}"
        
        // Create intent to open MatchesActivity
        val intent = Intent(ctx, MatchesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            fromUserHashHex.hashCode() + 2000, // Unique request code for unmatch notifications
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Eşleşme İptal Edildi")
            .setContentText("$userName eşleşmeyi iptal etti")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            // Use unique notification ID for unmatch notifications
            val notificationId = NOTIFICATION_ID + 2000 + fromUserHashHex.hashCode()
            notificationManager?.notify(notificationId, notification)
            Log.d(TAG, "🔔 UNMATCH_NOTIFICATION: Notification posted with ID: $notificationId")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }
    
    private fun showBlockNotification(fromUserHashHex: String) {
        val ctx = context ?: return
        
        Log.d(TAG, "🔔 BLOCK_NOTIFICATION: Creating notification for: $fromUserHashHex")
        
        // Find the match for this user to get their name
        val matches = matchStore?.getMatches() ?: emptyList()
        val match = matches.find { it.userHash == fromUserHashHex }
        
        // Get user name
        val userName = match?.userName?.takeIf { it.isNotBlank() } 
            ?: BleEngineManager.getInstance()?.nearbyUsersFlow?.value?.find { it.userHash == fromUserHashHex }?.userName
            ?: "User${fromUserHashHex.take(4).uppercase()}"
        
        // Create intent to open MatchesActivity
        val intent = Intent(ctx, MatchesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            fromUserHashHex.hashCode() + 3000, // Unique request code for block notifications
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Engellendiniz")
            .setContentText("$userName sizi engelledi")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            // Use unique notification ID for block notifications
            val notificationId = NOTIFICATION_ID + 3000 + fromUserHashHex.hashCode()
            notificationManager?.notify(notificationId, notification)
            Log.d(TAG, "🔔 BLOCK_NOTIFICATION: Notification posted with ID: $notificationId")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }
    
    private fun showMatchRequestNotification(fromGender: String) {
        val ctx = context ?: return
        
        // Create intent to open MatchesActivity
        val intent = Intent(ctx, MatchesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("focus_tab", "requests")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val genderText = when (fromGender.uppercase()) {
            "M" -> "erkek"
            "F" -> "kadın"
            else -> ""
        }
        
        val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Yeni eşleşme isteği")
            .setContentText("Bir $genderText kullanıcı sizinle eşleşmek istiyor")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 250, 250, 250))
            .build()
        
        try {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted", e)
        }
    }
    
    // Public API for UI
    
    /**
     * Accept a pending request
     * BILATERAL STORAGE: Store match on receiver side when accepting
     */
    fun acceptRequest(requestId: String): MatchStore.Match? {
        Log.d(TAG, "🎯 ACCEPT_REQUEST_START: requestId=$requestId")
        
        val requests = matchStore?.getPendingRequests() ?: emptyList()
        Log.d(TAG, "🎯 ACCEPT_REQUEST: Found ${requests.size} pending requests")
        
        val request = requests.find { it.id == requestId }
        
        if (request != null) {
            Log.d(TAG, "✅ RECEIVER: ACCEPT sent for request: $requestId from ${request.fromUserHash}")
            
            // BILATERAL STORAGE: Store match on receiver side with deterministic ID
            val currentUserPrefs = getUserPreferences()
            val currentUserId = currentUserPrefs.getUserId()
            val currentUserHash = getUserHash(currentUserId)
            val partnerUserHash = request.fromUserHash
            
            // Create deterministic match ID (same on both devices)
            val matchId = createDeterministicMatchId(currentUserHash, partnerUserHash)
            
            // Get user name from BLE Engine cache or generate default
            val userName = BleEngineManager.getInstance()?.let { engine ->
                // Try to get user name from nearby users
                engine.nearbyUsersFlow.value.find { it.userHash == partnerUserHash }?.userName
            } ?: "User${partnerUserHash.take(4).uppercase()}"
            
            val match = MatchStore.Match(
                id = matchId,
                userHash = partnerUserHash,
                userName = userName,
                gender = request.fromGender,
                deviceAddress = request.deviceAddress
            )
            
            Log.d(TAG, "📝 RECEIVER: About to store match: ${match.id} with userHash: ${match.userHash}")
            matchStore?.storeMatch(match)
            Log.d(TAG, "📝 RECEIVER: Match stored successfully!")
            
            // Verify match was stored
            val allMatches = matchStore?.getMatches() ?: emptyList()
            Log.d(TAG, "📝 RECEIVER: Total matches after storage: ${allMatches.size}")
            Log.d(TAG, "📝 RECEIVER: Match IDs: ${allMatches.map { it.id }}")
            
            // Remove from pending requests
            val removeSuccess = matchStore?.rejectRequest(requestId) ?: false
            Log.d(TAG, "🗑️ Remove from pending: success=$removeSuccess")
            
            updatePendingRequestCount()
            
            // Send BLE response back to sender
            sendMatchResponse(true, partnerUserHash)
            
            return match
        } else {
            Log.w(TAG, "❌ Request not found for acceptance: $requestId")
            Log.d(TAG, "❌ Available request IDs: ${requests.map { it.id }}")
            return null
        }
    }
    
    /**
     * Reject a pending request
     */
    fun rejectRequest(requestId: String): Boolean {
        // Get request info BEFORE removing it
        val requests = matchStore?.getPendingRequests() ?: emptyList()
        val request = requests.find { it.id == requestId }
        
        val success = matchStore?.rejectRequest(requestId) ?: false
        if (success) {
            Log.d(TAG, "❌ REJECT sent for request: $requestId")
            updatePendingRequestCount()
            
            // Send BLE response back to sender using saved request info
            if (request != null) {
                sendMatchResponse(false, request.fromUserHash)
            }
        }
        return success
    }
    
    /**
     * Get all pending requests
     */
    fun getPendingRequests(): List<MatchStore.PendingRequest> {
        return matchStore?.getPendingRequests() ?: emptyList()
    }
    
    /**
     * Get all matches
     */
    fun getMatches(): List<MatchStore.Match> {
        return matchStore?.getMatches() ?: emptyList()
    }
    
    /**
     * Store a successful match (when our request gets accepted)
     * BILATERAL STORAGE: Called from AuraGattService when ACCEPT received
     */
    fun storeSuccessfulMatch(userHash: String, gender: String, deviceAddress: String? = null) {
        Log.d(TAG, "📝 storeSuccessfulMatch called with userHash=$userHash, gender=$gender, deviceAddress=$deviceAddress")
        
        val currentUserPrefs = getUserPreferences()
        val currentUserId = currentUserPrefs.getUserId()
        val currentUserHash = getUserHash(currentUserId)
        
        // Create deterministic match ID (same on both devices)
        val matchId = createDeterministicMatchId(currentUserHash, userHash)
        
        // Get user name from BLE Engine cache or generate default
        val userName = BleEngineManager.getInstance()?.let { engine ->
            // Try to get user name from nearby users
            engine.nearbyUsersFlow.value.find { it.userHash == userHash }?.userName
        } ?: "User${userHash.take(4).uppercase()}"
        
        val match = MatchStore.Match(
            id = matchId,
            userHash = userHash,
            userName = userName,
            gender = gender,
            deviceAddress = deviceAddress
        )
        
        matchStore?.storeMatch(match)
        Log.d(TAG, "📝 STORE_MATCH on sender side: $userHash with ID: $matchId, deviceAddress: $deviceAddress")
    }
    
    /**
     * Create deterministic match ID from two user hashes
     * DETERMINISTIC MATCH ID: Same ID generated on both devices for same pair
     */
    private fun createDeterministicMatchId(hashA: String, hashB: String): String {
        val sortedHashes = listOf(hashA, hashB).sorted()
        val combined = "${sortedHashes[0]}:${sortedHashes[1]}"
        
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(combined.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }.take(16) // 16 char hex
        } catch (e: Exception) {
            Log.e(TAG, "Error creating deterministic match ID", e)
            java.util.UUID.randomUUID().toString()
        }
    }
    
    /**
     * Get user hash from user ID
     */
    private fun getUserHash(userId: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(userId.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }.take(16) // 16 char hex
        } catch (e: Exception) {
            Log.e(TAG, "Error creating user hash", e)
            userId.hashCode().toString()
        }
    }
    
    /**
     * Store a pending match (when we send a request)
     */
    fun storePendingMatch(userHash: String, gender: String) {
        // For now, just log - we could store pending outgoing requests if needed
        Log.d(TAG, "📤 Pending match stored: $userHash")
    }
    
    /**
     * Send match request notification directly (for testing)
     */
    fun sendMatchRequestNotification(targetHashHex: String, senderGender: String, senderName: String) {
        Log.d(TAG, "📤 DIRECT_MATCH_REQUEST: Sending notification to $targetHashHex from $senderName ($senderGender)")
        
        val store = matchStore
        val ctx = context
        
        if (store != null && ctx != null) {
            // Store the request
            val request = store.storePendingRequest(targetHashHex, senderGender)
            Log.d(TAG, "📝 DIRECT_MATCH_REQUEST: REQUEST stored: ${request.id}")
            
            // Update UI state
            updatePendingRequestCount()
            _newRequestEvent.value = targetHashHex
            
            // Show notification
            showMatchRequestNotification(senderGender)
            Log.d(TAG, "🔔 DIRECT_MATCH_REQUEST: NOTIFICATION posted")
            
            // If there's an active activity listener, also show immediate dialog
            currentActivityListener?.onMatchRequestReceived(targetHashHex, senderGender)
        } else {
            Log.e(TAG, "❌ DIRECT_MATCH_REQUEST: MatchStore or Context not initialized")
        }
    }
    
    // Private helper methods
    
    /**
     * Clean up old notification IDs to prevent memory leaks
     */
    private fun cleanupOldNotificationIds(currentTime: Long) {
        // Remove expired notification IDs
        val expiredIds = notificationTimeouts.entries.filter { (_, timestamp) ->
            currentTime - timestamp > NOTIFICATION_TIMEOUT
        }.map { it.key }
        
        expiredIds.forEach { id ->
            processedNotificationIds.remove(id)
            notificationTimeouts.remove(id)
        }
        
        // Clean up old match request tracking
        matchRequestNotificationTracker.entries.removeAll { (_, timestamp) ->
            currentTime - timestamp > MATCH_REQUEST_NOTIFICATION_COOLDOWN
        }
        
        if (expiredIds.isNotEmpty()) {
            Log.d(TAG, "🧹 Cleaned up ${expiredIds.size} expired notification IDs")
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Match Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming match requests"
                enableVibration(true)
                setShowBadge(true)
            }
            
            val systemNotificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            systemNotificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun updatePendingRequestCount() {
        val count = matchStore?.getPendingRequestCount() ?: 0
        _pendingRequestCount.value = count
        Log.d(TAG, "📊 Updated pending request count: $count")
    }
    
    private fun sendMatchResponse(accepted: Boolean, toUserHash: String) {
        Log.d(TAG, "🚀 SEND_MATCH_RESPONSE: Attempting to send response: accepted=$accepted to $toUserHash")
        
        // Send response via BLE Engine
        val bleEngine = BleEngineManager.getInstance()
        if (bleEngine != null) {
            bleEngine.enqueueMatchResponse(accepted, toUserHash)
            Log.d(TAG, "📤 Match response queued via BLE Engine: accepted=$accepted to $toUserHash")
        } else {
            Log.e(TAG, "❌ BleEngineManager instance is null!")
        }
    }
    
    // AuraGattService.MatchRequestListener implementation
    override fun onMatchRequestReceived(fromUserHashHex: String, fromGender: String) {
        Log.d(TAG, "📥 GATT: Match request received from: $fromUserHashHex, gender: $fromGender")
        
        val store = matchStore
        val ctx = context
        
        if (store != null && ctx != null) {
            // Store the request
            val request = store.storePendingRequest(fromUserHashHex, fromGender)
            Log.d(TAG, "📝 RECEIVER: REQUEST stored: ${request.id}")
            
            // Update UI state
            updatePendingRequestCount()
            _newRequestEvent.value = fromUserHashHex
            
            // Show notification
            showMatchRequestNotification(fromGender)
            Log.d(TAG, "🔔 NOTIFICATION posted")
            
            // If there's an active activity listener, also show immediate dialog
            currentActivityListener?.onMatchRequestReceived(fromUserHashHex, fromGender)
        } else {
            Log.e(TAG, "❌ MatchStore or Context not initialized")
        }
    }
    
    override fun onMatchAccepted(userHashHex: String) {
        Log.d(TAG, "✅ GATT: Match accepted by: $userHashHex")
        storeSuccessfulMatch(userHashHex, "U") // Gender unknown for accepted matches
        currentActivityListener?.onMatchAccepted(userHashHex)
    }
    
    override fun onMatchRejected(userHashHex: String) {
        Log.d(TAG, "❌ GATT: Match rejected by: $userHashHex")
        currentActivityListener?.onMatchRejected(userHashHex)
    }
    
    override fun onMatchTimeout(userHashHex: String) {
        Log.d(TAG, "⏰ GATT: Match timeout for: $userHashHex")
        // Handle as rejection
        onMatchRejected(userHashHex)
    }
    
    override fun onConnectionError(error: String) {
        Log.e(TAG, "🔌 GATT: Connection error: $error")
    }
    
    override fun onSyncedMatchConfirmed(playAt: Long, gender: Gender, outputMode: OutputMode) {
        Log.d(TAG, "🎵 GATT: Synced match confirmed")
    }
    
    override fun onGattReadyToSend() {
        Log.d(TAG, "📡 GATT: Ready to send")
    }
    
    override fun onChatMessageReceived(fromUserHashHex: String, message: String) {
        Log.d(TAG, "💬 GATT: Chat message received from: $fromUserHashHex")
        
        // Store chat message locally
        val chatStore = ChatStore(context ?: return)
        
        // Find match ID for this user
        val matches = matchStore?.getMatches() ?: emptyList()
        val match = matches.find { it.userHash == fromUserHashHex }
        
        if (match != null) {
            val chatMessage = ChatStore.ChatMessage(
                id = System.currentTimeMillis().toString(),
                matchId = match.id,
                senderId = fromUserHashHex,
                receiverId = getUserPreferences().getUserId(),
                content = message,
                timestamp = System.currentTimeMillis(),
                isFromMe = false,
                status = ChatStore.MessageStatus.DELIVERED
            )
            
            chatStore.storeMessage(chatMessage)
            Log.d(TAG, "💾 Stored incoming chat message")
            
            // Show notification
            showChatNotification(fromUserHashHex, message)
        } else {
            Log.w(TAG, "❌ No match found for chat sender: $fromUserHashHex")
        }
    }
    
    override fun onUnmatchReceived(fromUserHashHex: String) {
        Log.d(TAG, "💔 GATT: Unmatch received from: $fromUserHashHex")
        
        // Remove match locally
        val matchStore = this.matchStore
        if (matchStore != null) {
            matchStore.removeMatch(fromUserHashHex)
            
            // Clear chat history
            chatStore?.clearChatHistory(fromUserHashHex)
            
            Log.d(TAG, "💔 Processed unmatch from: $fromUserHashHex")
        }
    }
    
    override fun onBlockReceived(fromUserHashHex: String) {
        Log.d(TAG, "🚫 GATT: Block received from: $fromUserHashHex")
        
        // Remove match if exists
        val matchStore = this.matchStore
        if (matchStore != null) {
            matchStore.removeMatch(fromUserHashHex)
            
            // Clear chat history
            chatStore?.clearChatHistory(fromUserHashHex)
            
            Log.d(TAG, "🚫 Processed block from: $fromUserHashHex")
        }
    }
}