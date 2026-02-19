package com.aura.link

import android.bluetooth.BluetoothDevice
import android.content.Context

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val userId: String? = null, // DEPRECATED: Use userHashHex instead
    val gender: String? = null,
    val proximity: String? = null,
    val bluetoothDevice: BluetoothDevice? = null,  // Store actual BluetoothDevice object
    val userHashBytes: ByteArray? = null, // NEW: Raw 8-byte hash (protocol identity)
    val userHashHex: String? = null // NEW: Hex string (display/storage key)
) {
    fun getDisplayName(context: Context): String {
        return if (name.isBlank()) context.getString(R.string.aura_user_default) else name
    }
    
    fun getGenderDisplay(context: Context): String {
        return when (gender) {
            "M" -> context.getString(R.string.male_gender)
            "F" -> context.getString(R.string.female_gender)
            else -> context.getString(R.string.unknown_gender)
        }
    }
    
    fun getGenderIcon(): String {
        return when (gender) {
            "M" -> "♂"
            "F" -> "♀"
            else -> "?"
        }
    }
    
    fun getProximityDisplay(context: Context): String {
        return proximity ?: when {
            rssi > -50 -> context.getString(R.string.close_distance)
            rssi > -70 -> context.getString(R.string.medium_distance)
            else -> context.getString(R.string.far_distance)
        }
    }
    
    // Handle ByteArray properly in data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as BleDevice
        
        if (name != other.name) return false
        if (address != other.address) return false
        if (rssi != other.rssi) return false
        if (userId != other.userId) return false
        if (gender != other.gender) return false
        if (proximity != other.proximity) return false
        if (bluetoothDevice != other.bluetoothDevice) return false
        if (userHashBytes != null) {
            if (other.userHashBytes == null) return false
            if (!userHashBytes.contentEquals(other.userHashBytes)) return false
        } else if (other.userHashBytes != null) return false
        if (userHashHex != other.userHashHex) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + rssi
        result = 31 * result + (userId?.hashCode() ?: 0)
        result = 31 * result + (gender?.hashCode() ?: 0)
        result = 31 * result + (proximity?.hashCode() ?: 0)
        result = 31 * result + (bluetoothDevice?.hashCode() ?: 0)
        result = 31 * result + (userHashBytes?.contentHashCode() ?: 0)
        result = 31 * result + (userHashHex?.hashCode() ?: 0)
        return result
    }
}