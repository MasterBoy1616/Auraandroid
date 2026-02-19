package com.aura.link

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ChatActivity : BaseThemedActivity(), BleEngine.BleEngineListener {
    
    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_MATCH_ID = "match_id"
        const val EXTRA_PARTNER_HASH = "partner_hash"
        const val EXTRA_PARTNER_GENDER = "partner_gender"
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var chatAdapter: ChatAdapter
    
    private var matchId: String? = null
    private var partnerHash: String? = null
    private var partnerGender: String? = null
    
    // Crystal System
    private lateinit var crystalManager: CrystalManager
    private lateinit var chatStore: ChatStore
    
    // Prevent duplicate message processing
    private val processedMessageIds = mutableSetOf<String>()
    
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, -300) // Ekranın ortasında, yukarıda
        toast.show()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        
        // Get match details from intent
        matchId = intent.getStringExtra(EXTRA_MATCH_ID)
        partnerHash = intent.getStringExtra(EXTRA_PARTNER_HASH)
        partnerGender = intent.getStringExtra(EXTRA_PARTNER_GENDER)
        
        Log.d(TAG, "💬 CHAT_CREATE: matchId=$matchId, partnerHash=$partnerHash, partnerGender=$partnerGender")
        
        if (matchId == null || partnerHash == null) {
            Log.e(TAG, "❌ Missing match details - matchId: $matchId, partnerHash: $partnerHash")
            showTopToast("Sohbet bilgileri eksik")
            finish()
            return
        }
        
        chatStore = ChatStore(this)
        
        // Initialize Crystal Manager
        crystalManager = CrystalManager(this)
        
        initViews()
        setupRecyclerView()
        
        // Load messages AFTER views are initialized
        handler.postDelayed({
            loadMessages()
        }, 100)
        
        // Set up BLE Engine listener for incoming messages
        BleEngineManager.addListener(this)
        
        // CRITICAL: ALWAYS ensure advertising is active for message sending
        val userPrefs = UserPreferences(this)
        userPrefs.setVisibilityEnabled(true) // Force enable for chat
        BleEngineManager.startAdvertising()
        BleEngineManager.ensureBackgroundScanning()
        
        Log.d(TAG, "💬 Chat opened for match: $matchId with $partnerHash")
        Log.d(TAG, "🔍 Background scanning status: ${BleEngineManager.isBackgroundScanningActive()}")
        Log.d(TAG, "📡 Advertising status: ${BleEngineManager.isAdvertisingActive()}")
        Log.d(TAG, "📡 FORCED advertising ON for chat messaging")
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        
        btnSend.setOnClickListener {
            sendMessage()
        }
        
        // Set title based on partner gender
        val genderText = when (partnerGender?.uppercase()) {
            "M" -> "Erkek kullanıcı"
            "F" -> "Kadın kullanıcı"
            else -> "Kullanıcı"
        }
        title = genderText
    }
    
    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = chatAdapter
        }
    }
    
    private fun loadMessages() {
        lifecycleScope.launch {
            try {
                val messages = chatStore.getMessages(matchId!!)
                chatAdapter.updateMessages(messages)
                
                // Scroll to bottom
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
                
                Log.d(TAG, "📋 Loaded ${messages.size} messages")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading messages", e)
                showTopToast("Mesajlar yüklenirken hata oluştu")
            }
        }
    }
    
    private fun sendMessage() {
        val messageText = etMessage.text.toString().trim()
        if (messageText.isEmpty()) {
            return
        }
        
        // Prevent double sending with more robust check
        if (isSendingMessage) {
            Log.d(TAG, "📤 SEND_MESSAGE: Already sending, ignoring duplicate")
            return
        }
        
        // Disable send button temporarily to prevent multiple clicks
        btnSend.isEnabled = false
        isSendingMessage = true
        
        Log.d(TAG, "📤 SEND_MESSAGE: Starting to send: ${messageText.take(20)}...")
        
        val userPrefs = UserPreferences(this)
        val currentUserId = userPrefs.getUserId()
        
        // Create more robust unique message ID to prevent duplicates
        val timestamp = System.currentTimeMillis()
        val messageId = "${currentUserId}_${partnerHash}_${messageText.hashCode()}_${timestamp}"
        
        // Check if this exact message was already sent recently (within 5 seconds)
        val recentMessages = chatStore.getMessages(matchId!!).filter { 
            it.isFromMe && it.content == messageText && (timestamp - it.timestamp) < 5000 
        }
        
        if (recentMessages.isNotEmpty()) {
            Log.d(TAG, "🚫 SEND_MESSAGE: Duplicate message detected, skipping")
            btnSend.isEnabled = true
            isSendingMessage = false
            return
        }
        
        // Create message
        val message = ChatStore.ChatMessage(
            id = messageId,
            matchId = matchId!!,
            senderId = currentUserId,
            receiverId = partnerHash!!,
            content = messageText,
            timestamp = timestamp,
            isFromMe = true,
            status = ChatStore.MessageStatus.PENDING
        )
        
        // Store locally FIRST
        chatStore.storeMessage(message)
        Log.d(TAG, "📤 SEND_MESSAGE: Message stored locally with ID: $messageId")
        
        // Reward crystals for first message
        val reward = crystalManager.completeFirstMessageTask()
        crystalManager.updateWeeklyProgress("message")
        crystalManager.updateWeeklyProgress("chat")
        
        if (reward > 0) {
            showTopToast("İlk mesaj ödülü: +$reward aura!")
        }
        
        // Clear input immediately
        etMessage.setText("")
        
        // Update UI immediately
        loadMessages()
        
        // Send via BLE after UI update
        handler.postDelayed({
            sendMessageViaBLE(message)
            
            // Re-enable send button after delay
            handler.postDelayed({
                btnSend.isEnabled = true
                isSendingMessage = false
            }, 1000) // 1 second delay before allowing next send
        }, 100)
        
        Log.d(TAG, "📤 SEND_MESSAGE: Process completed for: ${messageText.take(20)}...")
    }
    
    private var isSendingMessage = false
    
    private fun sendMessageViaBLE(message: ChatStore.ChatMessage) {
        val partnerHash = message.receiverId
        
        Log.d(TAG, "📤 CHAT_SEND_START: Sending message via BLE to: $partnerHash")
        
        // CRITICAL: Force advertising to be ON for message sending
        val userPrefs = UserPreferences(this)
        userPrefs.setVisibilityEnabled(true)
        BleEngineManager.startAdvertising()
        BleEngineManager.ensureBackgroundScanning()
        
        // FIXED: Send message only ONCE to prevent duplicates
        val bleEngine = BleEngineManager.getInstance()
        if (bleEngine != null) {
            // Send message only once - BLE engine will handle reliability internally
            bleEngine.enqueueChat(partnerHash, message.content)
            Log.d(TAG, "📤 CHAT_SEND: Single message sent (no spam)")
        } else {
            Log.e(TAG, "❌ CHAT_SEND: BleEngine is null!")
        }
        
        // Update message status to DELIVERED after delay
        handler.postDelayed({
            chatStore.updateMessageStatus(message.id, ChatStore.MessageStatus.DELIVERED)
            runOnUiThread {
                loadMessages()
            }
        }, 2000) // Reduced delay since we're only sending once
    }
    
    override fun onResume() {
        super.onResume()
        
        // CRITICAL: ALWAYS ensure background scanning is active when resuming chat
        Log.d(TAG, "▶️ CHAT_RESUME: Ensuring background scanning for message reception")
        
        // FORCE advertising to be ON for chat - this is critical for message sending
        val userPrefs = UserPreferences(this)
        userPrefs.setVisibilityEnabled(true)
        BleEngineManager.startAdvertising()
        BleEngineManager.ensureBackgroundScanning()
        
        Log.d(TAG, "▶️ CHAT_RESUME: FORCED advertising and background scanning ON")
        Log.d(TAG, "▶️ CHAT_RESUME: Advertising status: ${BleEngineManager.isAdvertisingActive()}")
        Log.d(TAG, "▶️ CHAT_RESUME: Background scanning status: ${BleEngineManager.isBackgroundScanningActive()}")
        
        // CRITICAL FIX: Refresh messages when returning to chat - this will show any messages received while away
        handler.postDelayed({
            loadMessages()
            Log.d(TAG, "▶️ CHAT_RESUME: Messages refreshed after delay")
        }, 500) // Small delay to ensure everything is loaded
        
        // Set up periodic background scanning check (every 10 seconds - very frequent)
        startPeriodicScanningCheck()
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ CHAT_PAUSE: Chat paused")
        
        // Stop periodic check when paused
        stopPeriodicScanningCheck()
        
        // DON'T stop background scanning - it should continue for message reception
        Log.d(TAG, "⏸️ CHAT_PAUSE: Background scanning continues for message reception")
    }
    
    // Periodic scanning check to ensure it stays active
    private var scanningCheckRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private fun startPeriodicScanningCheck() {
        stopPeriodicScanningCheck() // Stop any existing check
        
        scanningCheckRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "🔍 PERIODIC_CHECK: Ensuring background scanning and advertising are active")
                
                // FORCE both advertising and scanning to be active
                val userPrefs = UserPreferences(this@ChatActivity)
                userPrefs.setVisibilityEnabled(true)
                BleEngineManager.startAdvertising()
                BleEngineManager.ensureBackgroundScanning()
                
                // ALSO refresh messages to catch any new ones
                loadMessages()
                
                // Schedule next check in 5 seconds (more frequent for real-time chat)
                handler.postDelayed(this, 5000)
            }
        }
        
        // Start first check in 5 seconds
        handler.postDelayed(scanningCheckRunnable!!, 5000)
        Log.d(TAG, "🔍 PERIODIC_CHECK: Started periodic background scanning check (every 5s) with message refresh")
    }
    
    private fun stopPeriodicScanningCheck() {
        scanningCheckRunnable?.let { 
            handler.removeCallbacks(it)
            scanningCheckRunnable = null
            Log.d(TAG, "🔍 PERIODIC_CHECK: Stopped periodic background scanning check")
        }
    }
    
    // BleEngine.BleEngineListener implementation
    override fun onIncomingMatchRequest(senderHash: String) {
        // Not relevant for chat activity
    }
    
    override fun onMatchAccepted(senderHash: String) {
        // Not relevant for chat activity
    }
    
    override fun onMatchRejected(senderHash: String) {
        // Not relevant for chat activity
    }
    
    override fun onChatMessage(senderHash: String, message: String) {
        // Only handle messages from our chat partner
        if (senderHash == partnerHash) {
            runOnUiThread {
                Log.d(TAG, "💬 CHAT_ACTIVITY: Received message from partner: ${message.take(20)}...")
                
                // ENHANCED duplicate prevention with multiple checks
                val messageContent = message.trim()
                val contentHash = messageContent.hashCode()
                val currentTime = System.currentTimeMillis()
                val timeWindow = currentTime / 5000 // 5-second window for duplicates (tighter)
                val messageId = "${senderHash}_${contentHash}_${timeWindow}"
                
                // Check 1: Processed message IDs
                if (processedMessageIds.contains(messageId)) {
                    Log.d(TAG, "🚫 CHAT_ACTIVITY: DUPLICATE: Message already processed, skipping: $messageId")
                    return@runOnUiThread
                }
                
                // Check 2: Recent messages in ChatStore (more strict)
                val existingMessages = chatStore.getMessages(matchId!!).filter { 
                    !it.isFromMe && 
                    it.content == messageContent && 
                    (currentTime - it.timestamp) < 10000 // 10 seconds (tighter window)
                }
                
                if (existingMessages.isNotEmpty()) {
                    Log.d(TAG, "🚫 CHAT_ACTIVITY: DUPLICATE: Message already exists in chat history, skipping")
                    return@runOnUiThread
                }
                
                // Check 3: Very recent messages (last 3 seconds)
                val veryRecentMessages = chatStore.getMessages(matchId!!).filter { 
                    !it.isFromMe && 
                    (currentTime - it.timestamp) < 3000 // 3 seconds
                }
                
                if (veryRecentMessages.any { it.content == messageContent }) {
                    Log.d(TAG, "🚫 CHAT_ACTIVITY: DUPLICATE: Very recent identical message found, skipping")
                    return@runOnUiThread
                }
                
                processedMessageIds.add(messageId)
                Log.d(TAG, "✅ CHAT_ACTIVITY: NEW_MESSAGE: Processing message: $messageId")
                
                // Clean up old processed IDs (keep last 50)
                if (processedMessageIds.size > 50) {
                    val toRemove = processedMessageIds.take(processedMessageIds.size - 50)
                    processedMessageIds.removeAll(toRemove.toSet())
                    Log.d(TAG, "🧹 CHAT_ACTIVITY: Cleaned up ${toRemove.size} old message IDs")
                }
                
                // Store the received message with unique timestamp-based ID
                val uniqueMessageId = "${senderHash}_${currentTime}_${contentHash}_${Math.random().toInt()}"
                val chatMessage = ChatStore.ChatMessage(
                    id = uniqueMessageId,
                    matchId = matchId!!,
                    senderId = senderHash,
                    receiverId = UserPreferences(this@ChatActivity).getUserId(),
                    content = messageContent,
                    timestamp = currentTime,
                    isFromMe = false,
                    status = ChatStore.MessageStatus.DELIVERED
                )
                
                chatStore.storeMessage(chatMessage)
                Log.d(TAG, "💾 CHAT_ACTIVITY: Message stored with ID: $uniqueMessageId")
                
                // Refresh UI immediately
                loadMessages()
                
                Log.d(TAG, "✅ CHAT_ACTIVITY: UI refreshed for message: $uniqueMessageId")
            }
        } else {
            Log.d(TAG, "💬 CHAT_ACTIVITY: Message from different user ($senderHash), ignoring (partner: $partnerHash)")
        }
    }
    
    override fun onPhotoReceived(senderHash: String, photoBase64: String) {
        // Only handle photos from our chat partner
        if (senderHash == partnerHash) {
            runOnUiThread {
                Log.d(TAG, "📷 CHAT_ACTIVITY: Photo received from partner: $senderHash")
                // Photo is automatically cached by MatchRequestManager
                // Could show a toast or update UI if needed
                showTopToast("📷 Profil fotoğrafı alındı")
            }
        }
    }
    
    override fun onPhotoRequested(senderHash: String) {
        // Only handle photo requests from our chat partner
        if (senderHash == partnerHash) {
            runOnUiThread {
                Log.d(TAG, "📷 CHAT_ACTIVITY: Photo requested by partner: $senderHash")
                // Photo request is automatically handled by MatchRequestManager
            }
        }
    }
    
    override fun onUnmatchReceived(senderHash: String) {
        try {
            // Only handle unmatch from our chat partner
            if (senderHash == partnerHash) {
                // Safety check - ensure activity is not destroyed
                if (isDestroyed || isFinishing) {
                    Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring unmatch")
                    return
                }
                
                runOnUiThread {
                    try {
                        Log.d(TAG, "💔 CHAT_ACTIVITY: Unmatch received from partner: $senderHash")
                        showTopToast("Eşleşme iptal edildi. Sohbet sonlandırılıyor.")
                        
                        // Stop periodic scanning check to prevent memory leaks
                        stopPeriodicScanningCheck()
                        
                        // Close chat activity after a short delay
                        handler.postDelayed({
                            try {
                                if (!isDestroyed && !isFinishing) {
                                    finish()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error finishing activity", e)
                            }
                        }, 2000)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error handling unmatch in UI thread", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling unmatch", e)
        }
    }
    
    override fun onBlockReceived(senderHash: String) {
        try {
            // Only handle block from our chat partner
            if (senderHash == partnerHash) {
                // Safety check - ensure activity is not destroyed
                if (isDestroyed || isFinishing) {
                    Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring block")
                    return
                }
                
                runOnUiThread {
                    try {
                        Log.d(TAG, "🚫 CHAT_ACTIVITY: Block received from partner: $senderHash")
                        showTopToast("Engellendiniz. Sohbet sonlandırılıyor.")
                        
                        // Stop periodic scanning check to prevent memory leaks
                        stopPeriodicScanningCheck()
                        
                        // Close chat activity immediately
                        if (!isDestroyed && !isFinishing) {
                            finish()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error handling block in UI thread", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling block", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop periodic scanning check
        stopPeriodicScanningCheck()
        
        // Remove listener to prevent memory leaks
        BleEngineManager.removeListener(this)
        
        Log.d(TAG, "💀 CHAT_DESTROY: ChatActivity destroyed, listener removed")
    }
}