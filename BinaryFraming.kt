package com.aura.link

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Binary message framing system for GATT communication
 * Handles chunking, reassembly, and MTU limitations
 */
object BinaryFraming {
    
    private const val TAG = "BinaryFraming"
    
    // Message ID generator
    private val messageIdGenerator = AtomicInteger(1)
    
    // Message reassembly buffers (msgId -> accumulated data)
    // REASSEMBLY BUFFER LOCATION: BinaryFraming.reassemblyBuffers
    private val reassemblyBuffers = ConcurrentHashMap<Int, MessageBuffer>()
    
    data class MessageBuffer(
        val type: Byte,
        val totalLength: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var receivedLength: Int = 0
    )
    
    data class FramedMessage(
        val type: Byte,
        val msgId: Int,
        val payload: ByteArray
    )
    
    /**
     * Create framed chunks for a message
     * MTU CALCULATION: Max chunk size = 20 bytes (DEFAULT_MTU - ATT_OVERHEAD)
     * Frame header = 7 bytes, so max payload per chunk = 13 bytes
     */
    fun createFramedMessage(type: Byte, payload: ByteArray): List<ByteArray> {
        val msgId = messageIdGenerator.getAndIncrement()
        val totalLength = payload.size
        val chunks = mutableListOf<ByteArray>()
        
        Log.d(TAG, "📦 Creating framed message: type=$type, msgId=$msgId, totalLen=$totalLength")
        
        var offset = 0
        while (offset < payload.size) {
            val chunkSize = minOf(AuraProtocol.MAX_CHUNK_SIZE, payload.size - offset)
            val chunk = ByteArray(AuraProtocol.FRAME_HEADER_SIZE + chunkSize)
            
            val buffer = ByteBuffer.wrap(chunk)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: [type:1][msgId:2][totalLen:2][offset:2] (7 bytes)
            buffer.put(type)
            buffer.putShort(msgId.toShort())
            buffer.putShort(totalLength.toShort())
            buffer.putShort(offset.toShort())
            
            // Payload chunk
            buffer.put(payload, offset, chunkSize)
            
            chunks.add(chunk)
            offset += chunkSize
            
            Log.d(TAG, "📦 Created chunk: offset=$offset, chunkSize=$chunkSize, totalChunkSize=${chunk.size}")
        }
        
        Log.d(TAG, "📦 Created ${chunks.size} chunks for message $msgId")
        return chunks
    }
    
    /**
     * Process incoming chunk and return complete message if ready
     */
    fun processChunk(chunk: ByteArray): FramedMessage? {
        if (chunk.size < AuraProtocol.FRAME_HEADER_SIZE) {
            Log.w(TAG, "❌ Chunk too small: ${chunk.size} bytes")
            return null
        }
        
        try {
            val buffer = ByteBuffer.wrap(chunk)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            // Parse header
            val type = buffer.get()
            val msgId = buffer.getShort().toInt()
            val totalLength = buffer.getShort().toInt()
            val offset = buffer.getShort().toInt()
            
            val payloadSize = chunk.size - AuraProtocol.FRAME_HEADER_SIZE
            val payload = ByteArray(payloadSize)
            buffer.get(payload)
            
            Log.d(TAG, "📥 Processing chunk: type=$type, msgId=$msgId, totalLen=$totalLength, offset=$offset, payloadSize=$payloadSize")
            
            // Get or create message buffer
            val messageBuffer = reassemblyBuffers.getOrPut(msgId) {
                MessageBuffer(type, totalLength)
            }
            
            // Validate consistency
            if (messageBuffer.type != type || messageBuffer.totalLength != totalLength) {
                Log.e(TAG, "❌ Message buffer inconsistency for msgId $msgId")
                reassemblyBuffers.remove(msgId)
                return null
            }
            
            // Store chunk
            messageBuffer.chunks[offset] = payload
            messageBuffer.receivedLength += payloadSize
            
            Log.d(TAG, "📥 Stored chunk: msgId=$msgId, receivedLen=${messageBuffer.receivedLength}/${messageBuffer.totalLength}")
            
            // Check if message is complete
            if (messageBuffer.receivedLength >= messageBuffer.totalLength) {
                Log.d(TAG, "✅ Message $msgId complete - reassembling")
                
                // Reassemble message
                val completePayload = ByteArray(messageBuffer.totalLength)
                var currentOffset = 0
                
                // Sort chunks by offset and reassemble
                messageBuffer.chunks.toSortedMap().forEach { (chunkOffset, chunkData) ->
                    if (chunkOffset == currentOffset) {
                        val copySize = minOf(chunkData.size, completePayload.size - currentOffset)
                        System.arraycopy(chunkData, 0, completePayload, currentOffset, copySize)
                        currentOffset += copySize
                    }
                }
                
                // Clean up
                reassemblyBuffers.remove(msgId)
                
                Log.d(TAG, "✅ Reassembled message: type=$type, msgId=$msgId, size=${completePayload.size}")
                return FramedMessage(type, msgId, completePayload)
            }
            
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing chunk", e)
            return null
        }
    }
    
    /**
     * Create match request message
     */
    fun createMatchRequest(userHash: ByteArray, gender: Byte): List<ByteArray> {
        val payload = ByteBuffer.allocate(9)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        payload.put(userHash) // 8 bytes
        payload.put(gender) // 1 byte
        
        return createFramedMessage(AuraProtocol.MSG_MATCH_REQUEST, payload.array())
    }
    
    /**
     * Create match response message
     */
    fun createMatchResponse(accepted: Boolean, userHash: ByteArray, gender: Byte): List<ByteArray> {
        val payload = ByteBuffer.allocate(10)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        val acceptedByte = if (accepted) 1.toByte() else 0.toByte()
        payload.put(acceptedByte) // 1 byte
        payload.put(userHash) // 8 bytes
        payload.put(gender) // 1 byte
        
        Log.d(TAG, "📦 buildMatchResponse: accepted=$accepted -> payload[0]=$acceptedByte")
        
        val type = if (accepted) AuraProtocol.MSG_MATCH_ACCEPT else AuraProtocol.MSG_MATCH_REJECT
        Log.d(TAG, "📦 buildMatchResponse: messageType=$type (ACCEPT=${AuraProtocol.MSG_MATCH_ACCEPT}, REJECT=${AuraProtocol.MSG_MATCH_REJECT})")
        
        return createFramedMessage(type, payload.array())
    }
    
    /**
     * Create chat message
     */
    fun createChatMessage(text: String, userHash: ByteArray): List<ByteArray> {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(8 + textBytes.size)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        payload.put(userHash) // 8 bytes
        payload.put(textBytes) // variable length
        
        return createFramedMessage(AuraProtocol.MSG_CHAT_MESSAGE, payload.array())
    }
    
    /**
     * Create acknowledgment message
     */
    fun createAck(msgId: Int): List<ByteArray> {
        val payload = ByteBuffer.allocate(2)
        payload.order(ByteOrder.LITTLE_ENDIAN)
        payload.putShort(msgId.toShort())
        
        return createFramedMessage(AuraProtocol.MSG_ACK, payload.array())
    }
    
    /**
     * Parse match request payload
     */
    fun parseMatchRequest(payload: ByteArray): Pair<ByteArray, Byte>? {
        if (payload.size < 9) return null
        
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val userHash = ByteArray(8)
        buffer.get(userHash)
        val gender = buffer.get()
        
        return Pair(userHash, gender)
    }
    
    /**
     * Parse match response payload
     */
    fun parseMatchResponse(payload: ByteArray): Triple<Boolean, ByteArray, Byte>? {
        if (payload.size < 10) return null
        
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val acceptedByte = buffer.get()
        val accepted = acceptedByte == 1.toByte()
        val userHash = ByteArray(8)
        buffer.get(userHash)
        val gender = buffer.get()
        
        Log.d(TAG, "📥 parseMatchResponse: payload[0]=$acceptedByte -> accepted=$accepted")
        
        return Triple(accepted, userHash, gender)
    }
    
    /**
     * Parse chat message payload
     */
    fun parseChatMessage(payload: ByteArray): Pair<ByteArray, String>? {
        if (payload.size < 8) return null
        
        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val userHash = ByteArray(8)
        buffer.get(userHash)
        
        val textBytes = ByteArray(payload.size - 8)
        buffer.get(textBytes)
        
        val text = String(textBytes, Charsets.UTF_8)
        return Pair(userHash, text)
    }
    
    /**
     * Create unmatch message
     */
    fun createUnmatchMessage(userHash: ByteArray): List<ByteArray> {
        // Payload is just the user hash (8 bytes)
        return createFramedMessage(AuraProtocol.MSG_UNMATCH, userHash)
    }
    
    /**
     * Create block message
     */
    fun createBlockMessage(userHash: ByteArray): List<ByteArray> {
        // Payload is just the user hash (8 bytes)
        return createFramedMessage(AuraProtocol.MSG_BLOCK, userHash)
    }
    
    /**
     * Clean up old reassembly buffers
     */
    fun cleanup() {
        reassemblyBuffers.clear()
        Log.d(TAG, "🧹 Cleaned up reassembly buffers")
    }
}