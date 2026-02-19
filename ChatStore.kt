package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent storage for chat messages using SharedPreferences + JSON
 * Future: can be migrated to Room database
 */
class ChatStore(context: Context) {
    
    companion object {
        private const val TAG = "ChatStore"
        private const val PREFS_NAME = "aura_chat"
        private const val KEY_MESSAGES = "messages"
    }
    
    private val context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Type token for Gson
    private val messagesType = object : TypeToken<List<ChatMessage>>() {}.type
    
    data class ChatMessage(
        val id: String,
        val matchId: String,
        val senderId: String,
        val receiverId: String,
        val content: String,
        val timestamp: Long,
        val isFromMe: Boolean,
        val status: MessageStatus = MessageStatus.PENDING
    )
    
    enum class MessageStatus {
        PENDING, DELIVERED, FAILED, READ
    }
    
    /**
     * Store a chat message
     */
    fun storeMessage(message: ChatMessage) {
        val messages = getAllMessages().toMutableList()
        messages.add(message)
        saveMessages(messages)
        Log.d(TAG, "💬 Stored message: ${message.content.take(20)}...")
    }
    
    /**
     * Get messages for a specific match - SORTED by timestamp
     */
    fun getMessages(matchId: String): List<ChatMessage> {
        return getAllMessages()
            .filter { it.matchId == matchId }
            .sortedBy { it.timestamp } // CRITICAL FIX: Sort by timestamp ascending (oldest first)
    }
    
    /**
     * Update message status
     */
    fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val messages = getAllMessages().toMutableList()
        val messageIndex = messages.indexOfFirst { it.id == messageId }
        
        if (messageIndex >= 0) {
            messages[messageIndex] = messages[messageIndex].copy(status = status)
            saveMessages(messages)
            Log.d(TAG, "📝 Updated message status: $messageId -> $status")
        }
    }
    
    /**
     * Clear chat history for a specific user
     */
    fun clearChatHistory(userHashHex: String) {
        val messages = getAllMessages().toMutableList()
        val removed = messages.removeAll { it.senderId == userHashHex || it.receiverId == userHashHex }
        
        if (removed) {
            saveMessages(messages)
            Log.d(TAG, "🧹 Cleared chat history for: $userHashHex")
        }
    }
    
    /**
     * Get all messages
     */
    private fun getAllMessages(): List<ChatMessage> {
        return try {
            val json = prefs.getString(KEY_MESSAGES, null)
            if (json != null) {
                gson.fromJson(json, messagesType) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading messages", e)
            emptyList()
        }
    }
    
    /**
     * Save messages to storage
     */
    private fun saveMessages(messages: List<ChatMessage>) {
        val json = gson.toJson(messages)
        prefs.edit().putString(KEY_MESSAGES, json).apply()
        Log.d(TAG, "💾 Saved ${messages.size} messages")
    }
}