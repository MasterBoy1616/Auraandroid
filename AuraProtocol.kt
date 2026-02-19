package com.aura.link

import java.util.UUID

/**
 * Unified protocol constants for Aura BLE communication
 * All UUIDs and protocol constants are defined here to ensure consistency
 */
object AuraProtocol {
    
    // Aura GATT Service UUID - used for GATT server/client communication
    val AURA_GATT_SERVICE_UUID: UUID = UUID.fromString("8c2d4b2a-9a1e-4f0b-9f9a-2e0b9b0f1a01")
    
    // GATT Characteristics for match flow and chat
    val MATCH_REQUEST_CHARACTERISTIC_UUID: UUID = UUID.fromString("8c2d4b2a-9a1e-4f0b-9f9a-2e0b9b0f1a02")
    val MATCH_RESPONSE_CHARACTERISTIC_UUID: UUID = UUID.fromString("8c2d4b2a-9a1e-4f0b-9f9a-2e0b9b0f1a03")
    val CHAT_CHARACTERISTIC_UUID: UUID = UUID.fromString("8c2d4b2a-9a1e-4f0b-9f9a-2e0b9b0f1a04")
    
    // CCCD descriptor UUID for notifications (standard BLE UUID)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    // Manufacturer data constants
    const val MANUFACTURER_ID = 0x0A0A
    const val PROTOCOL_VERSION: Byte = 1
    
    // Magic header to prevent false-positive discovery
    const val MAGIC_HEADER = "AUR" // 3-byte ASCII magic header
    val MAGIC_HEADER_BYTES = MAGIC_HEADER.toByteArray() // [0x41, 0x55, 0x52]
    
    // Gender encoding for manufacturer data
    const val GENDER_MALE: Byte = 0x01
    const val GENDER_FEMALE: Byte = 0x02
    const val GENDER_UNKNOWN: Byte = 0x03
    
    // Payload size constants
    const val MANUFACTURER_DATA_SIZE = 13 // [magic:3][version:1][gender:1][hash:8] = 13 bytes
    const val LEGACY_ADVERTISING_LIMIT = 31 // Samsung compatibility
    
    // Binary GATT message types (avoid JSON fragmentation)
    const val MSG_MATCH_REQUEST: Byte = 0x01
    const val MSG_MATCH_ACCEPT: Byte = 0x02
    const val MSG_MATCH_REJECT: Byte = 0x03
    const val MSG_SYNC_PLAY_AT: Byte = 0x04
    const val MSG_SYNC_READY: Byte = 0x05
    const val MSG_PLAY_AT_DATA: Byte = 0x06
    const val MSG_CHAT_MESSAGE: Byte = 0x07
    const val MSG_ACK: Byte = 0x08
    const val MSG_UNMATCH: Byte = 0x09
    const val MSG_BLOCK: Byte = 0x0A
    
    // Binary packet constants
    const val GATT_PACKET_SIZE = 16 // Fixed 16-byte packets for all GATT messages
    const val PROTOCOL_VERSION_GATT: Byte = 0x01
    
    // Binary message framing (length-prefixed with chunking support)
    // Header: [type:1][msgId:2][totalLen:2][offset:2] (7 bytes)
    // Payload chunk follows
    const val FRAME_HEADER_SIZE = 7
    const val MAX_MTU = 247
    const val MAX_CHUNK_SIZE = MAX_MTU - 3 - FRAME_HEADER_SIZE // MTU - ATT overhead - header
}