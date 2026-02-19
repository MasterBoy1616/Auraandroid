package com.aura.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer

class BleScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "BleScanner"
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private val discoveredDevices = mutableMapOf<Int, BleDevice>() // Key by userHashHex.hashCode() for deduplication
    
    interface ScanListener {
        fun onDeviceFound(device: BleDevice)
        fun onScanStarted()
        fun onScanStopped()
        fun onError(error: String)
    }
    
    private var scanListener: ScanListener? = null
    
    fun setScanListener(listener: ScanListener) {
        this.scanListener = listener
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null && 
               context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 and below
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    
    private fun mapRssiToProximity(rssi: Int): String {
        return when {
            rssi > -50 -> "Yakın"      // Very close
            rssi > -70 -> "Orta"       // Medium
            else -> "Uzak"             // Far
        }
    }
    
    private fun isAuraDevice(result: ScanResult): Boolean {
        val scanRecord = result.scanRecord ?: return false
        
        // CRITICAL: Samsung Galaxy A51 fix - NO Service UUID filtering
        // Only check manufacturer data for our company ID
        val manufacturerData = scanRecord.getManufacturerSpecificData(AuraProtocol.MANUFACTURER_ID)
        if (manufacturerData != null && manufacturerData.size >= AuraProtocol.MANUFACTURER_DATA_SIZE) {
            // Validate magic header to prevent false positives
            if (manufacturerData.size >= 3) {
                val magicBytes = manufacturerData.copyOf(3)
                if (magicBytes.contentEquals(AuraProtocol.MAGIC_HEADER_BYTES)) {
                    // Valid Aura device with correct magic header
                    return true
                } else {
                    Log.d(TAG, "❌ Invalid magic header: ${magicBytes.joinToString("") { "%02x".format(it) }}, expected: ${AuraProtocol.MAGIC_HEADER_BYTES.joinToString("") { "%02x".format(it) }}")
                }
            }
        }
        
        return false
    }
    
    private fun parseAuraDevice(result: ScanResult): BleDevice? {
        val scanRecord = result.scanRecord ?: return null
        
        // Parse manufacturer data to extract user information
        val manufacturerData = scanRecord.getManufacturerSpecificData(AuraProtocol.MANUFACTURER_ID)
        var userHashHex: String? = null
        var gender: String? = null
        
        if (manufacturerData != null && manufacturerData.size >= AuraProtocol.MANUFACTURER_DATA_SIZE) {
            try {
                val buffer = ByteBuffer.wrap(manufacturerData)
                
                // Validate magic header (3 bytes)
                val magicBytes = ByteArray(3)
                buffer.get(magicBytes)
                if (!magicBytes.contentEquals(AuraProtocol.MAGIC_HEADER_BYTES)) {
                    Log.w(TAG, "Invalid magic header in manufacturer data")
                    return null
                }
                
                // Read protocol version (1 byte)
                val version = buffer.get()
                if (version != AuraProtocol.PROTOCOL_VERSION) {
                    Log.w(TAG, "Unsupported protocol version: $version")
                    return null
                }
                
                // Read gender (1 byte)
                val genderByte = buffer.get()
                
                // Read 8-byte hash
                val hashBytes = ByteArray(8)
                buffer.get(hashBytes)
                
                // Map gender byte back to string
                gender = when (genderByte) {
                    AuraProtocol.GENDER_MALE -> "M"
                    AuraProtocol.GENDER_FEMALE -> "F"
                    else -> "U"
                }
                
                // Convert hash to hex string for display/storage
                userHashHex = hashBytes.joinToString("") { "%02x".format(it) }
                
                Log.d(TAG, "Parsed Aura device: magic=OK, version=$version, gender=$gender($genderByte), hashHex=$userHashHex, rssi=${result.rssi}")
                
                val proximity = mapRssiToProximity(result.rssi)
                
                return BleDevice(
                    name = "Aura Kullanıcısı",
                    address = result.device.address,
                    rssi = result.rssi,
                    userId = userHashHex, // DEPRECATED: Keep for compatibility
                    gender = gender,
                    proximity = proximity,
                    bluetoothDevice = result.device,  // Pass the actual BluetoothDevice object
                    userHashBytes = hashBytes, // NEW: Raw 8-byte hash (protocol identity)
                    userHashHex = userHashHex // NEW: Hex string (display/storage key)
                )
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse manufacturer data", e)
                return null
            }
        } else {
            Log.d(TAG, "Invalid or missing manufacturer data, size: ${manufacturerData?.size ?: 0}")
            return null
        }
    }
    
    fun startScan() {
        Log.d(TAG, "🔍 startScan() called")
        
        if (!isBluetoothSupported()) {
            val error = "BLE not supported"
            Log.e(TAG, error)
            scanListener?.onError(error)
            return
        }
        
        if (!isBluetoothEnabled()) {
            val error = "Bluetooth is disabled"
            Log.e(TAG, error)
            scanListener?.onError(error)
            return
        }
        
        if (!hasRequiredPermissions()) {
            val error = "Required permissions not granted"
            Log.e(TAG, error)
            scanListener?.onError(error)
            return
        }
        
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }
        
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            val error = "Cannot get BLE scanner"
            Log.e(TAG, error)
            scanListener?.onError(error)
            return
        }
        
        // Clear previous discoveries
        discoveredDevices.clear()
        
        // Start manufacturer-only scan (Samsung Galaxy A51 fix)
        startManufacturerOnlyScan()
    }
    
    private fun startManufacturerOnlyScan() {
        Log.d(TAG, "🎯 Starting MANUFACTURER-ONLY scan (no UUID filter - Samsung Galaxy A51 fix)")
        
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                
                // Log every device found with manufacturer data details
                val manufacturerData = result.scanRecord?.getManufacturerSpecificData(AuraProtocol.MANUFACTURER_ID)
                Log.d(TAG, "📱 SCAN_RESULT: address=${result.device.address}, RSSI=${result.rssi}, manufacturer_bytes_length=${manufacturerData?.size ?: 0}")
                
                // Manually check if this is an Aura device by manufacturer data
                if (isAuraDevice(result)) {
                    Log.d(TAG, "📱 Found Aura device: ${result.device.address}")
                    handleScanResult(result)
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                isScanning = false
                val errorMessage = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                    else -> "Unknown error: $errorCode"
                }
                Log.e(TAG, "Manufacturer-only scan failed: $errorMessage (code: $errorCode)")
                scanListener?.onError("Scan failed: $errorMessage")
            }
        }
        
        // NO FILTERS - scan all devices and manually detect Aura by manufacturer data
        // This is required because we removed Service UUID from advertising
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
        
        // Try to set callback type if available, but don't fail if not supported
        try {
            scanSettings.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        } catch (e: Exception) {
            Log.w(TAG, "CALLBACK_TYPE_ALL_MATCHES not supported, using default")
        }
        
        val finalScanSettings = scanSettings.build()
        
        try {
            // Start scan with NO filters - we'll detect Aura devices manually
            bluetoothLeScanner?.startScan(emptyList(), finalScanSettings, scanCallback)
            isScanning = true
            scanListener?.onScanStarted()
            
            Log.d(TAG, "📊 Final ScanSettings:")
            Log.d(TAG, "  - Mode: ${finalScanSettings.scanMode}")
            Log.d(TAG, "  - Report delay: ${finalScanSettings.reportDelayMillis}ms")
            try {
                Log.d(TAG, "  - Callback type: ${finalScanSettings.callbackType}")
            } catch (e: Exception) {
                Log.d(TAG, "  - Callback type: default (not accessible)")
            }
            
            // Auto-stop scan after 30 seconds
            scanHandler.postDelayed({
                if (isScanning) {
                    Log.d(TAG, "Auto-stopping scan after 30 seconds")
                    stopScan()
                }
            }, 30000)
            
        } catch (e: SecurityException) {
            val error = "Security exception: ${e.message}"
            Log.e(TAG, error, e)
            scanListener?.onError(error)
        } catch (e: Exception) {
            val error = "Failed to start manufacturer-only scan: ${e.message}"
            Log.e(TAG, error, e)
            scanListener?.onError(error)
        }
    }
    
    private fun handleScanResult(result: ScanResult) {
        val device = parseAuraDevice(result)
        if (device != null) {
            val userHashHex = device.userId // This is already the hex string from parsing
            if (userHashHex != null) {
                // BLOCKING FILTER: Skip blocked users
                val blockStore = BlockStore(context)
                if (blockStore.isBlocked(userHashHex)) {
                    Log.d(TAG, "🚫 Skipping blocked user: $userHashHex")
                    return
                }
                
                // Deduplicate by userHashHex (not just MAC address) for better reliability
                val existingDevice = discoveredDevices[userHashHex.hashCode()]
                if (existingDevice == null || existingDevice.rssi < result.rssi) {
                    // New device or better signal strength
                    discoveredDevices[userHashHex.hashCode()] = device
                    scanListener?.onDeviceFound(device)
                    Log.d(TAG, "✅ Found Aura device: hashHex=$userHashHex, gender=${device.gender}, proximity=${device.proximity}, rssi=${result.rssi}")
                }
            }
        }
    }
    
    private fun stopCurrentScan() {
        try {
            scanCallback?.let { callback ->
                bluetoothLeScanner?.stopScan(callback)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception stopping current scan", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping current scan", e)
        }
    }
    
    fun stopScan() {
        Log.d(TAG, "🛑 stopScan() called, current state: isScanning=$isScanning")
        
        if (!isScanning || scanCallback == null) {
            Log.d(TAG, "Not scanning or no callback, nothing to stop")
            return
        }
        
        stopCurrentScan()
        
        isScanning = false
        scanHandler.removeCallbacksAndMessages(null)
        scanListener?.onScanStopped()
        
        Log.d(TAG, "Scan stopped successfully")
    }
    
    fun isScanning(): Boolean {
        return isScanning
    }
    
    fun cleanup() {
        Log.d(TAG, "cleanup() called")
        stopScan()
        discoveredDevices.clear()
        scanListener = null
    }
}