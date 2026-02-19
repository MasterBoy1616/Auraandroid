package com.aura.link

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE-only packet framing and chunking for manufacturer data
 * Max payload: 24 bytes after companyId (0xFFFF)
 * 
 * Packet format:
 * Byte0: version (0x01)
 * Byte1: type (0x01=PRESENCE, 0x02=MATCH_REQ, 0x03=MATCH_ACC, 0x04=MATCH_REJ, 0x05=CHAT)
 * Byte2..5: senderHash (4 bytes)
 * Byte6..9: targetHash (4 bytes) (0x00000000 if broadcast/presence)
 * Byte10: msgId (0..255 rolling)
 * Byte11: chunkIndex
 * Byte12: chunkTotal
 * Byte13..(end): chunkData (UTF-8 bytes for chat OR reserved for match)
 */
object BLEPacket {
    
    private const val TAG = "BLEPacket"
    
    // Company ID for manufacturer data
    const val COMPANY_ID = 0xFFFF
    
    // Protocol version
    private const val VERSION = 0x01.toByte()
    
    // Packet types
    const val TYPE_PRESENCE = 0x01.toByte()
    const val TYPE_MATCH_REQ = 0x02.toByte()
    const val TYPE_MATCH_ACC = 0x03.toByte()
    const val TYPE_MATCH_REJ = 0x04.toByte()
    const val TYPE_CHAT = 0x05.toByte()
    const val TYPE_UNMATCH = 0x06.toByte()
    const val TYPE_BLOCK = 0x07.toByte()
    const val TYPE_PHOTO = 0x08.toByte() // Photo sharing
    const val TYPE_PHOTO_REQUEST = 0x09.toByte() // NEW: Photo request
    
    // Packet structure constants
    private const val HEADER_SIZE = 13 // version + type + senderHash + targetHash + msgId + chunkIndex + chunkTotal
    private const val MAX_PAYLOAD_SIZE = 24 // Max manufacturer data payload after companyId
    private const val MAX_CHUNK_DATA = MAX_PAYLOAD_SIZE - HEADER_SIZE // 11 bytes per chunk
    
    // Reassembly cache for multi-chunk messages
    private val reassemblyCache = ConcurrentHashMap<String, ChunkCollector>()
    private const val REASSEMBLY_TIMEOUT_MS = 10000L // 10 seconds
    
    data class DecodedFrame(
        val type: Byte,
        val senderHash: ByteArray,
        val targetHash: ByteArray,
        val msgId: Int,
        val chunkIndex: Int,
        val chunkTotal: Int,
        val chunkData: ByteArray,
        val isComplete: Boolean = false,
        val completeMessage: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodedFrame
            return type == other.type &&
                   senderHash.contentEquals(other.senderHash) &&
                   targetHash.contentEquals(other.targetHash) &&
                   msgId == other.msgId &&
                   chunkIndex == other.chunkIndex &&
                   chunkTotal == other.chunkTotal &&
                   chunkData.contentEquals(other.chunkData) &&
                   isComplete == other.isComplete &&
                   completeMessage == other.completeMessage
        }
        
        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + senderHash.contentHashCode()
            result = 31 * result + targetHash.contentHashCode()
            result = 31 * result + msgId
            result = 31 * result + chunkIndex
            result = 31 * result + chunkTotal
            result = 31 * result + chunkData.contentHashCode()
            result = 31 * result + isComplete.hashCode()
            result = 31 * result + (completeMessage?.hashCode() ?: 0)
            return result
        }
    }
    
    private data class ChunkCollector(
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val totalChunks: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Hash userId to stable 4-byte hash
     */
    fun hashUserIdTo4Bytes(userId: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userId.toByteArray())
        return fullHash.copyOf(4)
    }
    
    /**
     * Encode presence broadcast (simple version without name)
     */
    fun encodePresence(senderHash: ByteArray): ByteArray {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        
        val packet = ByteArray(13)
        packet[0] = VERSION
        packet[1] = TYPE_PRESENCE
        System.arraycopy(senderHash, 0, packet, 2, 4)
        // targetHash = 0x00000000 for broadcast
        packet[10] = 0 // msgId
        packet[11] = 0 // chunkIndex
        packet[12] = 1 // chunkTotal
        
        return packet
    }
    
    /**
     * Encode presence broadcast with user name, gender AND mood - ENHANCED
     */
    fun encodePresenceWithMood(senderHash: ByteArray, userName: String, gender: Byte, moodData: ByteArray): ByteArray {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        
        // ENHANCED: Better mood data encoding
        // Format: [gender][userName][separator][moodData]
        val separator = "|".toByteArray(Charsets.UTF_8)
        val nameBytes = userName.take(15).toByteArray(Charsets.UTF_8) // Limit name to 15 chars
        val limitedMoodData = if (moodData.size > 10) moodData.copyOf(10) else moodData // Limit mood to 10 bytes
        
        val combinedData = byteArrayOf(gender) + nameBytes + separator + limitedMoodData
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // Create packet that fits in BLE advertising data
        val maxDataSize = minOf(combinedData.size, MAX_CHUNK_DATA)
        val packet = ByteArray(HEADER_SIZE + maxDataSize)
        
        packet[0] = VERSION
        packet[1] = TYPE_PRESENCE
        System.arraycopy(senderHash, 0, packet, 2, 4)
        // targetHash = 0x00000000 for broadcast
        packet[10] = msgId
        packet[11] = 0 // chunkIndex
        packet[12] = 1 // chunkTotal
        
        System.arraycopy(combinedData, 0, packet, HEADER_SIZE, maxDataSize)
        
        Log.d(TAG, "📦 MOOD_PACKET: Created presence with mood, size: ${packet.size} bytes")
        return packet
    }

    /**
     * Encode presence broadcast with user name and gender
     */
    fun encodePresenceWithNameAndGender(senderHash: ByteArray, userName: String, gender: Byte): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        
        // Combine user name and gender: [gender][userName]
        val genderAndNameBytes = byteArrayOf(gender) + userName.toByteArray(Charsets.UTF_8)
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // If data fits in single packet
        if (genderAndNameBytes.size <= MAX_CHUNK_DATA) {
            val packet = ByteArray(HEADER_SIZE + genderAndNameBytes.size)
            packet[0] = VERSION
            packet[1] = TYPE_PRESENCE
            System.arraycopy(senderHash, 0, packet, 2, 4)
            // targetHash = 0x00000000 for broadcast
            packet[10] = msgId
            packet[11] = 0 // chunkIndex
            packet[12] = 1 // chunkTotal
            System.arraycopy(genderAndNameBytes, 0, packet, HEADER_SIZE, genderAndNameBytes.size)
            
            return listOf(packet)
        } else {
            // Multi-chunk data (unlikely but handle it)
            val totalChunks = (genderAndNameBytes.size + MAX_CHUNK_DATA - 1) / MAX_CHUNK_DATA
            val chunks = mutableListOf<ByteArray>()
            
            for (chunkIndex in 0 until totalChunks) {
                val startOffset = chunkIndex * MAX_CHUNK_DATA
                val endOffset = minOf(startOffset + MAX_CHUNK_DATA, genderAndNameBytes.size)
                val chunkDataSize = endOffset - startOffset
                
                val packet = ByteArray(HEADER_SIZE + chunkDataSize)
                packet[0] = VERSION
                packet[1] = TYPE_PRESENCE
                System.arraycopy(senderHash, 0, packet, 2, 4)
                // targetHash = 0x00000000 for broadcast
                packet[10] = msgId
                packet[11] = chunkIndex.toByte()
                packet[12] = totalChunks.toByte()
                
                System.arraycopy(genderAndNameBytes, startOffset, packet, HEADER_SIZE, chunkDataSize)
                chunks.add(packet)
            }
            
            return chunks
        }
    }
    
    /**
     * Encode match request
     * PROTOCOL FIX: Request payload contains SENDER identity, targetHash only for routing
     */
    fun encodeMatchReq(senderHash: ByteArray, senderGender: Byte, targetHash: ByteArray): ByteArray {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val packet = ByteArray(14) // version + type + senderHash + senderGender + targetHash + msgId + chunkIndex + chunkTotal
        packet[0] = VERSION
        packet[1] = TYPE_MATCH_REQ
        System.arraycopy(senderHash, 0, packet, 2, 4)
        packet[6] = senderGender // SENDER gender in payload
        System.arraycopy(targetHash, 0, packet, 7, 4) // target for routing only
        packet[11] = (System.currentTimeMillis() % 256).toByte() // msgId
        packet[12] = 0 // chunkIndex
        packet[13] = 1 // chunkTotal
        
        return packet
    }
    
    /**
     * Encode match response (accept/reject)
     * PROTOCOL FIX: Response payload contains RESPONDER identity, targetHash only for routing
     */
    fun encodeMatchResp(type: Byte, responderHash: ByteArray, responderGender: Byte, targetHash: ByteArray): ByteArray {
        require(type == TYPE_MATCH_ACC || type == TYPE_MATCH_REJ) { "Invalid response type" }
        require(responderHash.size == 4) { "responderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val packet = ByteArray(14) // version + type + responderHash + responderGender + targetHash + msgId + chunkIndex + chunkTotal
        packet[0] = VERSION
        packet[1] = type
        System.arraycopy(responderHash, 0, packet, 2, 4)
        packet[6] = responderGender // RESPONDER gender in payload
        System.arraycopy(targetHash, 0, packet, 7, 4) // target for routing only
        packet[11] = (System.currentTimeMillis() % 256).toByte() // msgId
        packet[12] = 0 // chunkIndex
        packet[13] = 1 // chunkTotal
        
        return packet
    }
    
    /**
     * Encode chat message (with chunking if needed)
     */
    fun encodeChat(senderHash: ByteArray, targetHash: ByteArray, message: String): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // Calculate number of chunks needed
        val totalChunks = (messageBytes.size + MAX_CHUNK_DATA - 1) / MAX_CHUNK_DATA
        val chunks = mutableListOf<ByteArray>()
        
        for (chunkIndex in 0 until totalChunks) {
            val startOffset = chunkIndex * MAX_CHUNK_DATA
            val endOffset = minOf(startOffset + MAX_CHUNK_DATA, messageBytes.size)
            val chunkDataSize = endOffset - startOffset
            
            val packet = ByteArray(HEADER_SIZE + chunkDataSize)
            packet[0] = VERSION
            packet[1] = TYPE_CHAT
            System.arraycopy(senderHash, 0, packet, 2, 4)
            System.arraycopy(targetHash, 0, packet, 6, 4)
            packet[10] = msgId
            packet[11] = chunkIndex.toByte()
            packet[12] = totalChunks.toByte()
            
            // Copy chunk data
            System.arraycopy(messageBytes, startOffset, packet, HEADER_SIZE, chunkDataSize)
            
            chunks.add(packet)
        }
        
        Log.d(TAG, "Encoded chat message: ${message.length} chars -> ${chunks.size} chunks")
        return chunks
    }
    
    /**
     * Encode unmatch message
     */
    fun encodeUnmatch(senderHash: ByteArray, targetHash: ByteArray): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // Unmatch message is simple - no additional data needed
        val packet = ByteArray(HEADER_SIZE)
        packet[0] = VERSION
        packet[1] = TYPE_UNMATCH
        System.arraycopy(senderHash, 0, packet, 2, 4)
        System.arraycopy(targetHash, 0, packet, 6, 4)
        packet[10] = msgId
        packet[11] = 0 // Single chunk
        packet[12] = 1 // Total chunks = 1
        
        Log.d(TAG, "Encoded unmatch message from ${senderHash.toHex()} to ${targetHash.toHex()}")
        return listOf(packet)
    }
    
    /**
     * Encode photo request message
     */
    fun encodePhotoRequest(senderHash: ByteArray, targetHash: ByteArray): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // Photo request is simple - no additional data needed
        val packet = ByteArray(HEADER_SIZE)
        packet[0] = VERSION
        packet[1] = TYPE_PHOTO_REQUEST
        System.arraycopy(senderHash, 0, packet, 2, 4)
        System.arraycopy(targetHash, 0, packet, 6, 4)
        packet[10] = msgId
        packet[11] = 0 // Single chunk
        packet[12] = 1 // Total chunks = 1
        
        Log.d(TAG, "Encoded photo request from ${senderHash.toHex()} to ${targetHash.toHex()}")
        return listOf(packet)
    }

    /**
     * Encode photo message (with chunking for base64 data)
     */
    fun encodePhoto(senderHash: ByteArray, targetHash: ByteArray, photoBase64: String): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val msgId = (System.currentTimeMillis() % 256).toByte()
        val messageBytes = photoBase64.toByteArray(Charsets.UTF_8)
        
        // SAFETY: Limit photo size to prevent BLE overload (max 50 chunks = ~550 bytes)
        val maxPhotoSize = MAX_CHUNK_DATA * 50 // About 550 bytes
        if (messageBytes.size > maxPhotoSize) {
            Log.w(TAG, "Photo too large for BLE transmission: ${messageBytes.size} bytes, max: $maxPhotoSize")
            return emptyList() // Return empty list to prevent transmission
        }
        
        // Calculate chunks needed
        val totalChunks = ((messageBytes.size + MAX_CHUNK_DATA - 1) / MAX_CHUNK_DATA).coerceAtMost(255)
        val packets = mutableListOf<ByteArray>()
        
        for (chunkIndex in 0 until totalChunks) {
            val startIndex = chunkIndex * MAX_CHUNK_DATA
            val endIndex = minOf(startIndex + MAX_CHUNK_DATA, messageBytes.size)
            val chunkData = messageBytes.copyOfRange(startIndex, endIndex)
            
            val packet = ByteArray(HEADER_SIZE + chunkData.size)
            packet[0] = VERSION
            packet[1] = TYPE_PHOTO
            System.arraycopy(senderHash, 0, packet, 2, 4)
            System.arraycopy(targetHash, 0, packet, 6, 4)
            packet[10] = msgId
            packet[11] = chunkIndex.toByte()
            packet[12] = totalChunks.toByte()
            System.arraycopy(chunkData, 0, packet, HEADER_SIZE, chunkData.size)
            
            packets.add(packet)
        }
        
        Log.d(TAG, "Encoded photo message: ${packets.size} chunks, ${messageBytes.size} bytes")
        return packets
    }

    /**
     * Encode block message
     */
    fun encodeBlock(senderHash: ByteArray, targetHash: ByteArray): List<ByteArray> {
        require(senderHash.size == 4) { "senderHash must be 4 bytes" }
        require(targetHash.size == 4) { "targetHash must be 4 bytes" }
        
        val msgId = (System.currentTimeMillis() % 256).toByte()
        
        // Block message is simple - no additional data needed
        val packet = ByteArray(HEADER_SIZE)
        packet[0] = VERSION
        packet[1] = TYPE_BLOCK
        System.arraycopy(senderHash, 0, packet, 2, 4)
        System.arraycopy(targetHash, 0, packet, 6, 4)
        packet[10] = msgId
        packet[11] = 0 // Single chunk
        packet[12] = 1 // Total chunks = 1
        
        Log.d(TAG, "Encoded block message from ${senderHash.toHex()} to ${targetHash.toHex()}")
        return listOf(packet)
    }
    
    // Helper data class for decode function
    private data class Tuple6<A, B, C, D, E, F>(
        val first: A,
        val second: B, 
        val third: C,
        val fourth: D,
        val fifth: E,
        val sixth: F
    )
    
    /**
     * Decode manufacturer data packet
     * PROTOCOL FIX: Handle new format with gender byte
     */
    fun decode(data: ByteArray): DecodedFrame? {
        // Minimum size check - now 14 bytes for match packets
        val minSize = if (data.size >= 2 && (data[1] == TYPE_MATCH_REQ || data[1] == TYPE_MATCH_ACC || data[1] == TYPE_MATCH_REJ)) 14 else 13
        
        if (data.size < minSize) {
            Log.w(TAG, "Packet too small: ${data.size} bytes, expected: $minSize")
            return null
        }
        
        // Check version
        if (data[0] != VERSION) {
            Log.w(TAG, "Unknown version: ${data[0]}")
            return null
        }
        
        val type = data[1]
        
        // Parse based on packet type
        val (senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData) = when (type) {
            TYPE_MATCH_REQ, TYPE_MATCH_ACC, TYPE_MATCH_REJ -> {
                // New format: [version][type][senderHash:4][senderGender:1][targetHash:4][msgId][chunkIndex][chunkTotal]
                val senderHash = data.copyOfRange(2, 6)
                val senderGender = data[6] // Available for parsing if needed
                val targetHash = data.copyOfRange(7, 11)
                val msgId = data[11].toInt() and 0xFF
                val chunkIndex = data[12].toInt() and 0xFF
                val chunkTotal = data[13].toInt() and 0xFF
                val chunkData = ByteArray(1) // Store gender as chunk data for compatibility
                chunkData[0] = senderGender
                
                Tuple6(senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData)
            }
            else -> {
                // Old format for other packet types
                val senderHash = data.copyOfRange(2, 6)
                val targetHash = data.copyOfRange(6, 10)
                val msgId = data[10].toInt() and 0xFF
                val chunkIndex = data[11].toInt() and 0xFF
                val chunkTotal = data[12].toInt() and 0xFF
                val chunkData = if (data.size > 13) data.copyOfRange(13, data.size) else ByteArray(0)
                
                Tuple6(senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData)
            }
        }
        
        Log.d(TAG, "PACKET: rx type=${String.format("%02x", type)} sender=${senderHash.toHex()} target=${targetHash.toHex()} msgId=$msgId chunk $chunkIndex/$chunkTotal data=${chunkData.size}bytes")
        
        // Handle single-chunk messages
        if (chunkTotal == 1) {
            val completeMessage = if (type == TYPE_CHAT) String(chunkData, Charsets.UTF_8) else null
            return DecodedFrame(type, senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData, true, completeMessage)
        }
        
        // Handle multi-chunk messages (chat only)
        if (type == TYPE_CHAT) {
            val cacheKey = "${senderHash.toHex()}-${targetHash.toHex()}-$msgId"
            
            // Clean up old entries
            cleanupReassemblyCache()
            
            val collector = reassemblyCache.getOrPut(cacheKey) {
                ChunkCollector(totalChunks = chunkTotal)
            }
            
            // Add chunk
            collector.chunks[chunkIndex] = chunkData
            
            // Check if complete
            if (collector.chunks.size == chunkTotal) {
                // Reassemble message
                val completeData = ByteArray(collector.chunks.values.sumOf { it.size })
                var offset = 0
                for (i in 0 until chunkTotal) {
                    val chunk = collector.chunks[i]
                    if (chunk != null) {
                        System.arraycopy(chunk, 0, completeData, offset, chunk.size)
                        offset += chunk.size
                    }
                }
                
                val completeMessage = String(completeData, Charsets.UTF_8)
                reassemblyCache.remove(cacheKey)
                
                Log.d(TAG, "CHAT: reassembled complete message from ${senderHash.toHex()}: ${completeMessage.take(50)}...")
                return DecodedFrame(type, senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData, true, completeMessage)
            } else {
                Log.d(TAG, "CHAT: partial chunk ${collector.chunks.size}/$chunkTotal from ${senderHash.toHex()}")
                return DecodedFrame(type, senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData, false, null)
            }
        }
        
        // Return incomplete frame for non-chat multi-chunk (shouldn't happen)
        return DecodedFrame(type, senderHash, targetHash, msgId, chunkIndex, chunkTotal, chunkData, false, null)
    }
    
    private fun cleanupReassemblyCache() {
        val now = System.currentTimeMillis()
        val iterator = reassemblyCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > REASSEMBLY_TIMEOUT_MS) {
                Log.d(TAG, "Cleaning up expired reassembly entry: ${entry.key}")
                iterator.remove()
            }
        }
    }
    
    // ========== PREMIUM FEATURE PACKETS ==========
    
    /**
     * Create Quick Reaction packet for premium feature
     */
    fun createQuickReactionPacket(senderHash: String, targetHash: String, emoji: String, message: String): ByteArray {
        val senderHashBytes = senderHash.take(8).toByteArray(Charsets.UTF_8).copyOf(4)
        val targetHashBytes = targetHash.take(8).toByteArray(Charsets.UTF_8).copyOf(4)
        
        // Create reaction data: emoji + message (limited to fit in one chunk)
        val reactionData = "$emoji:$message"
        val reactionBytes = reactionData.toByteArray(Charsets.UTF_8)
        
        // Limit to max chunk size
        val limitedReactionBytes = if (reactionBytes.size > MAX_CHUNK_DATA) {
            reactionBytes.copyOf(MAX_CHUNK_DATA)
        } else {
            reactionBytes
        }
        
        // Create single-chunk packet
        val packet = ByteArray(HEADER_SIZE + limitedReactionBytes.size)
        var offset = 0
        
        // Header
        packet[offset++] = VERSION
        packet[offset++] = TYPE_CHAT // Reuse chat type for reactions
        System.arraycopy(senderHashBytes, 0, packet, offset, 4)
        offset += 4
        System.arraycopy(targetHashBytes, 0, packet, offset, 4)
        offset += 4
        packet[offset++] = (System.currentTimeMillis() % 256).toByte() // msgId
        packet[offset++] = 0 // chunkIndex
        packet[offset++] = 1 // chunkTotal
        
        // Reaction data
        System.arraycopy(limitedReactionBytes, 0, packet, offset, limitedReactionBytes.size)
        
        Log.d(TAG, "Created quick reaction packet: $emoji for $targetHash")
        return packet
    }
    
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}