package com.aura.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

class AuraAdvertiser(private val context: Context) {
    
    companion object {
        private const val TAG = "AuraAdvertiser"
        
        // DEPRECATED: Use AuraProtocol constants instead
        @Deprecated("Use AuraProtocol.AURA_GATT_SERVICE_UUID")
        val AURA_SERVICE_UUID: UUID = AuraProtocol.AURA_GATT_SERVICE_UUID
        @Deprecated("Use AuraProtocol.AURA_GATT_SERVICE_UUID")
        val AURA_GATT_SERVICE_UUID: UUID = AuraProtocol.AURA_GATT_SERVICE_UUID
        @Deprecated("Use AuraProtocol.MATCH_REQUEST_CHARACTERISTIC_UUID")
        val MATCH_REQUEST_CHARACTERISTIC_UUID: UUID = AuraProtocol.MATCH_REQUEST_CHARACTERISTIC_UUID
        @Deprecated("Use AuraProtocol.MATCH_RESPONSE_CHARACTERISTIC_UUID")
        val MATCH_RESPONSE_CHARACTERISTIC_UUID: UUID = AuraProtocol.MATCH_RESPONSE_CHARACTERISTIC_UUID
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    private var advertiseCallback: AdvertiseCallback? = null
    private val userPrefs = UserPreferences(context)
    
    interface AdvertiseListener {
        fun onAdvertiseStarted()
        fun onAdvertiseStopped()
        fun onAdvertiseError(error: String)
    }
    
    private var advertiseListener: AdvertiseListener? = null
    
    fun setAdvertiseListener(listener: AdvertiseListener) {
        this.advertiseListener = listener
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    fun isAdvertisingSupported(): Boolean {
        return bluetoothAdapter?.isMultipleAdvertisementSupported == true
    }
    
    fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val advertiseGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission check (Android 12+): BLUETOOTH_ADVERTISE=$advertiseGranted, BLUETOOTH_CONNECT=$connectGranted")
            advertiseGranted && connectGranted
        } else {
            val bluetoothGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdminGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission check (Android <=11): BLUETOOTH=$bluetoothGranted, BLUETOOTH_ADMIN=$bluetoothAdminGranted")
            bluetoothGranted && bluetoothAdminGranted
        }
    }
    
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }
    
    private fun createMinimalManufacturerData(): ByteArray {
        val gender = userPrefs.getGender() ?: "U" // Unknown if not set
        
        // Map gender to byte using AuraProtocol constants
        val genderByte: Byte = when (gender.uppercase()) {
            "M", "MALE" -> AuraProtocol.GENDER_MALE
            "F", "FEMALE" -> AuraProtocol.GENDER_FEMALE
            else -> AuraProtocol.GENDER_UNKNOWN
        }
        
        // Create 8-byte SHA-256 hash of userId (first 8 bytes only)
        val userId = userPrefs.getUserId()
        val userIdBytes = userId.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userIdBytes)
        val userHash = fullHash.copyOf(8) // Take first 8 bytes only
        
        // Create manufacturer data with magic header: [magic:3][version:1][gender:1][hash:8] = 13 bytes
        val buffer = ByteBuffer.allocate(AuraProtocol.MANUFACTURER_DATA_SIZE)
        buffer.put(AuraProtocol.MAGIC_HEADER_BYTES)  // [0..2] magic "AUR"
        buffer.put(AuraProtocol.PROTOCOL_VERSION)    // [3] version = 1
        buffer.put(genderByte)                       // [4] gender
        buffer.put(userHash)                         // [5..12] 8-byte user hash
        
        val manufacturerData = buffer.array()
        Log.d(TAG, "Created manufacturer data with magic header: ${manufacturerData.size} bytes")
        Log.d(TAG, "- Magic: ${AuraProtocol.MAGIC_HEADER}")
        Log.d(TAG, "- Version: ${AuraProtocol.PROTOCOL_VERSION}")
        Log.d(TAG, "- Gender: $gender(0x${genderByte.toString(16).uppercase()})")
        Log.d(TAG, "- User hash: ${userHash.joinToString("") { "%02x".format(it) }}")
        return manufacturerData
    }
    
    fun startAdvertising() {
        Log.d(TAG, "startAdvertising() called, current state: isAdvertising=$isAdvertising")
        
        // CRITICAL: Always stop existing advertising first to avoid conflicts
        if (isAdvertising) {
            Log.d(TAG, "Stopping existing advertising before starting new one")
            stopAdvertising()
            // Small delay to ensure cleanup
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        
        if (!isBluetoothEnabled()) {
            val error = "Bluetooth is disabled"
            Log.e(TAG, error)
            advertiseListener?.onAdvertiseError(error)
            return
        }
        
        if (!isAdvertisingSupported()) {
            val error = "BLE advertising not supported"
            Log.e(TAG, error)
            advertiseListener?.onAdvertiseError(error)
            return
        }
        
        if (!hasAdvertisePermission()) {
            val error = "Bluetooth advertise permissions not granted"
            Log.e(TAG, error)
            val requiredPerms = getRequiredPermissions()
            Log.e(TAG, "Required permissions: ${requiredPerms.joinToString(", ")}")
            
            // Log current permission status for debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val advertiseStatus = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                val connectStatus = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                Log.e(TAG, "BLUETOOTH_ADVERTISE status: $advertiseStatus")
                Log.e(TAG, "BLUETOOTH_CONNECT status: $connectStatus")
            } else {
                val bluetoothStatus = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH)
                val bluetoothAdminStatus = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN)
                Log.e(TAG, "BLUETOOTH status: $bluetoothStatus")
                Log.e(TAG, "BLUETOOTH_ADMIN status: $bluetoothAdminStatus")
            }
            
            advertiseListener?.onAdvertiseError(error)
            return
        }
        
        if (userPrefs.getGender() == null) {
            val error = "Gender not selected"
            Log.e(TAG, error)
            advertiseListener?.onAdvertiseError(error)
            return
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            val error = "Cannot get BLE advertiser"
            Log.e(TAG, error)
            advertiseListener?.onAdvertiseError(error)
            return
        }
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                Log.d(TAG, "Advertising started successfully")
                isAdvertising = true
                advertiseListener?.onAdvertiseStarted()
            }
            
            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                isAdvertising = false
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertise data too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Advertising feature unsupported"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    else -> "Unknown error: $errorCode"
                }
                
                Log.e(TAG, "Advertising failed: $errorMessage (code: $errorCode)")
                
                // Log payload details if data too large
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    val manufacturerData = createMinimalManufacturerData()
                    Log.e(TAG, "ADVERTISE_DATA_TOO_LARGE details:")
                    Log.e(TAG, "- Service UUID: REMOVED (Samsung compatibility)")
                    Log.e(TAG, "- Manufacturer data size: ${manufacturerData.size} bytes")
                    Log.e(TAG, "- Manufacturer ID: 0x0A0A (2 bytes)")
                    Log.e(TAG, "- Include device name: false")
                    Log.e(TAG, "- Include TX power: false")
                    Log.e(TAG, "- Scan response: none")
                    Log.e(TAG, "- Total payload: ${2 + manufacturerData.size} bytes")
                    Log.e(TAG, "- Should be well under 31-byte limit")
                }
                
                advertiseListener?.onAdvertiseError("Advertise failed: $errorMessage")
            }
        }
        
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true) // Enable connections for GATT
            .setTimeout(0) // Advertise indefinitely
            .build()
        
        // Create minimal manufacturer-only advertising data (Samsung A51 compatible)
        val manufacturerData = createMinimalManufacturerData()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AuraProtocol.MANUFACTURER_ID, manufacturerData)
            .build()
        
        // Use legacy advertising - NO scan response parameter at all
        
        try {
            Log.d(TAG, "🚀 Starting MANUFACTURER-ONLY BLE advertising with magic header (Samsung Galaxy A51 fix):")
            Log.d(TAG, "- Magic header: ${AuraProtocol.MAGIC_HEADER}")
            Log.d(TAG, "- Manufacturer data (0x${AuraProtocol.MANUFACTURER_ID.toString(16).uppercase()}): ${manufacturerData.size} bytes + 2 byte header = ${manufacturerData.size + 2} bytes")
            Log.d(TAG, "- Total payload: ${manufacturerData.size + 2} bytes")
            Log.d(TAG, "- Device name: disabled (saves ~10-20 bytes)")
            Log.d(TAG, "- TX power: disabled (saves ~3 bytes)")
            Log.d(TAG, "- Scan response: none (legacy mode)")
            Log.d(TAG, "- SAFETY MARGIN: ${31 - (manufacturerData.size + 2)} bytes under 31-byte limit")
            
            // Legacy advertising call - only advertiseData and callback
            bluetoothLeAdvertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        } catch (e: SecurityException) {
            val error = "Security exception: ${e.message}"
            Log.e(TAG, error, e)
            advertiseListener?.onAdvertiseError(error)
        } catch (e: Exception) {
            val error = "Failed to start advertising: ${e.message}"
            Log.e(TAG, error, e)
            advertiseListener?.onAdvertiseError(error)
        }
    }
    
    fun stopAdvertising() {
        Log.d(TAG, "stopAdvertising() called, current state: isAdvertising=$isAdvertising")
        
        if (!isAdvertising || advertiseCallback == null) {
            Log.d(TAG, "Not advertising or no callback, nothing to stop")
            return
        }
        
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d(TAG, "Advertising stopped successfully")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception stopping advertising", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping advertising", e)
        }
        
        isAdvertising = false
        advertiseCallback = null
        advertiseListener?.onAdvertiseStopped()
    }
    
    fun isAdvertising(): Boolean {
        return isAdvertising
    }
    
    fun cleanup() {
        Log.d(TAG, "cleanup() called")
        stopAdvertising()
        advertiseListener = null
    }
}