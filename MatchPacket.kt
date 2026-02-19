package com.aura.link

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Binary match packet protocol (16 bytes fixed size)
 * Replaces JSON to avoid truncation and fragmentation issues
 */
object MatchPacket {
    
    private const val TAG = "MatchPacket"
    private const val PACKET_SIZE = AuraProtocol.GATT_PACKET_SIZE // Use protocol constant
    
    // Message types from AuraProtocol
    const val MSG_MATCH_REQUEST: Byte = AuraProtocol.MSG_MATCH_REQUEST
    const val MSG_MATCH_ACCEPT: Byte = AuraProtocol.MSG_MATCH_ACCEPT
    const val MSG_MATCH_REJECT: Byte = AuraProtocol.MSG_MATCH_REJECT
    
    // Gender codes from AuraProtocol
    const val GENDER_MALE: Byte = AuraProtocol.GENDER_MALE
    const val GENDER_FEMALE: Byte = AuraProtocol.GENDER_FEMALE
    const val GENDER_UNKNOWN: Byte = AuraProtocol.GENDER_UNKNOWN
    
    // Protocol version
    const val PROTOCOL_VERSION: Byte = AuraProtocol.PROTOCOL_VERSION_GATT
    
    data class ParsedPacket(
        val messageType: Byte,
        val gender: Byte,
        val userHash: ByteArray,
        val nonce: Int,
        val version: Byte
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as ParsedPacket
            
            if (messageType != other.messageType) return false
            if (gender != other.gender) return false
            if (!userHash.contentEquals(other.userHash)) return false
            if (nonce != other.nonce) return false
            if (version != other.version) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = messageType.toInt()
            result = 31 * result + gender
            result = 31 * result + userHash.contentHashCode()
            result = 31 * result + nonce
            result = 31 * result + version
            return result
        }
    }
    
    /**
     * Build match request packet (16 bytes)
     */
    fun buildMatchRequest(userId: String, gender: String): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // byte0: messageType
        buffer.put(MSG_MATCH_REQUEST)
        
        // byte1: gender
        buffer.put(getGenderByte(gender))
        
        // bytes2-9: userHash (8 bytes)
        val userHash = getUserHash(userId)
        buffer.put(userHash)
        
        // bytes10-13: nonce (UInt32 timestamp)
        val nonce = (System.currentTimeMillis() / 1000).toInt()
        buffer.putInt(nonce)
        
        // bytes14-15: version/reserved
        buffer.put(PROTOCOL_VERSION)
        buffer.put(0x00.toByte()) // reserved
        
        val result = buffer.array()
        Log.d(TAG, "📤 Built MATCH_REQUEST: ${result.size} bytes, userId=${userId.take(8)}, gender=$gender")
        return result
    }
    
    /**
     * Build match response packet (16 bytes)
     */
    fun buildMatchResponse(accepted: Boolean, userId: String, gender: String): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // byte0: messageType
        buffer.put(if (accepted) MSG_MATCH_ACCEPT else MSG_MATCH_REJECT)
        
        // byte1: gender
        buffer.put(getGenderByte(gender))
        
        // bytes2-9: userHash (8 bytes)
        val userHash = getUserHash(userId)
        buffer.put(userHash)
        
        // bytes10-13: nonce (UInt32 timestamp)
        val nonce = (System.currentTimeMillis() / 1000).toInt()
        buffer.putInt(nonce)
        
        // bytes14-15: version/reserved
        buffer.put(PROTOCOL_VERSION)
        buffer.put(0x00.toByte()) // reserved
        
        val result = buffer.array()
        Log.d(TAG, "📤 Built ${if (accepted) "MATCH_ACCEPT" else "MATCH_REJECT"}: ${result.size} bytes")
        return result
    }
    
    /**
     * Parse binary packet (validate size == 16)
     */
    fun parsePacket(value: ByteArray): ParsedPacket? {
        if (value.size != PACKET_SIZE) {
            Log.w(TAG, "❌ Invalid packet size: ${value.size}, expected: $PACKET_SIZE")
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(value)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            val messageType = buffer.get()
            val gender = buffer.get()
            
            val userHash = ByteArray(8)
            buffer.get(userHash)
            
            val nonce = buffer.getInt()
            val version = buffer.get()
            // Skip reserved byte
            
            Log.d(TAG, "📥 Parsed packet: type=${messageType.toInt()}, gender=${gender.toInt()}, nonce=$nonce, version=${version.toInt()}")
            
            return ParsedPacket(messageType, gender, userHash, nonce, version)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse packet", e)
            return null
        }
    }
    
    /**
     * Get 8-byte hash from userId
     */
    private fun getUserHash(userId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userId.toByteArray())
        return fullHash.copyOf(8) // Take first 8 bytes
    }
    
    /**
     * Convert gender string to byte
     */
    private fun getGenderByte(gender: String): Byte {
        return when (gender.uppercase()) {
            "M", "MALE", "ERKEK" -> GENDER_MALE
            "F", "FEMALE", "KADIN", "KADIN" -> GENDER_FEMALE
            else -> GENDER_UNKNOWN
        }
    }
    
    /**
     * Convert gender byte to string
     */
    fun getGenderString(genderByte: Byte): String {
        return when (genderByte) {
            GENDER_MALE -> "M"
            GENDER_FEMALE -> "F"
            else -> "U"
        }
    }
}