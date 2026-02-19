package com.aura.link

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary message protocol for GATT communication
 * Avoids JSON fragmentation issues with MTU limits
 */
object BinaryMessage {
    
    private const val TAG = "BinaryMessage"
    
    // Fixed message size for all binary messages
    const val BINARY_MESSAGE_SIZE = 12 // [type:1][userHash:8][gender:1][nonce:2] = 12 bytes
    
    data class MatchMessage(
        val msgType: Byte,
        val senderUserHash: ByteArray,
        val senderGender: Byte,
        val nonce: Short,
        val playAtTime: Long = 0L // Only used for SYNC_PLAY_AT messages
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as MatchMessage
            
            if (msgType != other.msgType) return false
            if (!senderUserHash.contentEquals(other.senderUserHash)) return false
            if (senderGender != other.senderGender) return false
            if (nonce != other.nonce) return false
            if (playAtTime != other.playAtTime) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = msgType.toInt()
            result = 31 * result + senderUserHash.contentHashCode()
            result = 31 * result + senderGender
            result = 31 * result + nonce
            result = 31 * result + playAtTime.hashCode()
            return result
        }
    }
    
    /**
     * Create a match request message
     */
    fun createMatchRequest(senderUserHash: ByteArray, senderGender: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_MATCH_REQUEST)
        buffer.put(senderUserHash) // 8 bytes
        buffer.put(senderGender)
        buffer.putShort(System.currentTimeMillis().toShort()) // nonce/timestamp
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created MATCH_REQUEST: ${result.size} bytes")
        return result
    }
    
    /**
     * Create a match accept message
     */
    fun createMatchAccept(senderUserHash: ByteArray, senderGender: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_MATCH_ACCEPT)
        buffer.put(senderUserHash) // 8 bytes
        buffer.put(senderGender)
        buffer.putShort(System.currentTimeMillis().toShort()) // nonce/timestamp
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created MATCH_ACCEPT: ${result.size} bytes")
        return result
    }
    
    /**
     * Create a match reject message
     */
    fun createMatchReject(senderUserHash: ByteArray, senderGender: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_MATCH_REJECT)
        buffer.put(senderUserHash) // 8 bytes
        buffer.put(senderGender)
        buffer.putShort(System.currentTimeMillis().toShort()) // nonce/timestamp
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created MATCH_REJECT: ${result.size} bytes")
        return result
    }
    
    /**
     * Create a sync play at message
     */
    fun createSyncPlayAt(senderUserHash: ByteArray, senderGender: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_SYNC_PLAY_AT)
        buffer.put(senderUserHash) // 8 bytes
        buffer.put(senderGender)
        buffer.putShort(System.currentTimeMillis().toShort()) // nonce/timestamp
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created SYNC_PLAY_AT: ${result.size} bytes")
        return result
    }
    
    /**
     * Create a play at data message (contains the actual timestamp)
     */
    fun createPlayAtData(playAtElapsedRealtimeMs: Long): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_PLAY_AT_DATA)
        buffer.putLong(playAtElapsedRealtimeMs) // 8 bytes
        buffer.put(0x00) // padding
        buffer.put(0x00) // padding
        buffer.put(0x00) // padding
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created PLAY_AT_DATA: ${result.size} bytes, playAt=$playAtElapsedRealtimeMs")
        return result
    }
    
    /**
     * Create a sync ready message
     */
    fun createSyncReady(senderUserHash: ByteArray, senderGender: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(BINARY_MESSAGE_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(AuraProtocol.MSG_SYNC_READY)
        buffer.put(senderUserHash) // 8 bytes
        buffer.put(senderGender)
        buffer.putShort(System.currentTimeMillis().toShort()) // nonce/timestamp
        
        val result = buffer.array()
        Log.d(TAG, "📤 Created SYNC_READY: ${result.size} bytes")
        return result
    }
    
    /**
     * Parse a binary message
     */
    fun parseMessage(data: ByteArray): MatchMessage? {
        if (data.size != BINARY_MESSAGE_SIZE) {
            Log.w(TAG, "❌ Invalid message size: ${data.size}, expected: $BINARY_MESSAGE_SIZE")
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            val msgType = buffer.get()
            val senderUserHash = ByteArray(8)
            buffer.get(senderUserHash)
            val senderGender = buffer.get()
            val nonce = buffer.getShort()
            
            Log.d(TAG, "📥 Parsed message: type=${msgType.toInt()}, gender=${senderGender.toInt()}, nonce=$nonce")
            
            return MatchMessage(msgType, senderUserHash, senderGender, nonce)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse binary message", e)
            return null
        }
    }
    
    /**
     * Parse a play at data message
     */
    fun parsePlayAtData(data: ByteArray): Long? {
        if (data.size != BINARY_MESSAGE_SIZE) {
            Log.w(TAG, "❌ Invalid play at data size: ${data.size}")
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            val msgType = buffer.get()
            if (msgType != AuraProtocol.MSG_PLAY_AT_DATA) {
                Log.w(TAG, "❌ Not a PLAY_AT_DATA message: $msgType")
                return null
            }
            
            val playAtTime = buffer.getLong()
            Log.d(TAG, "📥 Parsed PLAY_AT_DATA: playAt=$playAtTime")
            
            return playAtTime
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse play at data", e)
            return null
        }
    }
    
    /**
     * Get user hash from current user preferences
     */
    fun getUserHash(userPrefs: UserPreferences): ByteArray {
        val userId = userPrefs.getUserId()
        val userIdBytes = userId.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userIdBytes)
        return fullHash.copyOf(8)
    }
    
    /**
     * Get gender byte from user preferences
     */
    fun getGenderByte(userPrefs: UserPreferences): Byte {
        val gender = userPrefs.getGender() ?: "U"
        return when (gender.uppercase()) {
            "M", "MALE" -> AuraProtocol.GENDER_MALE
            "F", "FEMALE" -> AuraProtocol.GENDER_FEMALE
            else -> AuraProtocol.GENDER_UNKNOWN
        }
    }
}