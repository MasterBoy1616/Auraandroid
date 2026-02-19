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
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton advertising controller with proper state management and debouncing
 */
object AdvertisingController {
    
    private const val TAG = "AdvertisingController"
    
    private var context: Context? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var userPrefs: UserPreferences? = null
    
    // State management with synchronization
    private val _advertisingState = MutableStateFlow(false)
    val advertisingState: StateFlow<Boolean> = _advertisingState.asStateFlow()
    
    private val isStarting = AtomicBoolean(false)
    private var currentAdvertiser: BluetoothLeAdvertiser? = null
    private var currentCallback: AdvertiseCallback? = null
    private var lastError: String? = null
    
    // Listener interface for backward compatibility
    interface StateListener {
        fun onAdvertisingStateChanged(isAdvertising: Boolean, error: String?)
    }
    
    private var stateListener: StateListener? = null
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        userPrefs = UserPreferences(context)
        Log.d(TAG, "AdvertisingController initialized as singleton")
    }
    
    fun setStateListener(listener: StateListener?) {
        stateListener = listener
    }
    
    @Synchronized
    fun startIfPossible(context: Context): Boolean {
        Log.d(TAG, "🚀 startIfPossible() called - current state: ${_advertisingState.value}, isStarting: ${isStarting.get()}")
        
        // Debouncing: if already advertising, return success immediately
        if (_advertisingState.value) {
            Log.d(TAG, "Already advertising - returning success")
            return true
        }
        
        // Debouncing: if start is in progress, wait and return current state
        if (isStarting.get()) {
            Log.d(TAG, "Start already in progress - ignoring duplicate start")
            return _advertisingState.value
        }
        
        isStarting.set(true)
        
        try {
            val prefs = userPrefs ?: UserPreferences(context)
            
            // Log all system status
            logSystemStatus(context)
            
            // Check if user has enabled publishing
            if (!prefs.getVisibilityEnabled()) {
                Log.d(TAG, "❌ Publishing disabled by user")
                lastError = "Publishing disabled"
                updateState(false, lastError)
                return false
            }
            
            if (!hasRequiredPermissions(context)) {
                Log.w(TAG, "❌ Missing required permissions for advertising")
                lastError = "Missing permissions"
                updateState(false, lastError)
                return false
            }
            
            if (!isBluetoothEnabled()) {
                Log.w(TAG, "❌ Bluetooth disabled")
                lastError = "Bluetooth disabled"
                updateState(false, lastError)
                return false
            }
            
            if (!isLocationEnabled(context)) {
                Log.w(TAG, "❌ Location services disabled")
                lastError = "Location disabled"
                updateState(false, lastError)
                return false
            }
            
            if (prefs.getGender() == null) {
                Log.w(TAG, "❌ Gender not selected - cannot start advertising")
                lastError = "Gender not selected"
                updateState(false, lastError)
                return false
            }
            
            Log.d(TAG, "✅ All prerequisites met - starting advertising")
            return startAdvertisingInternal(context)
            
        } finally {
            isStarting.set(false)
        }
    }
    
    @Synchronized
    fun stop(context: Context) {
        Log.d(TAG, "🛑 stop() called - current state: ${_advertisingState.value}")
        
        if (!_advertisingState.value && currentCallback == null) {
            Log.d(TAG, "Not advertising - nothing to stop")
            return
        }
        
        stopAdvertisingInternal()
    }
    
    private fun startAdvertisingInternal(context: Context): Boolean {
        Log.d(TAG, "📡 Starting BLE advertising (internal)")
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "❌ Cannot get BLE advertiser")
            lastError = "BLE advertiser unavailable"
            updateState(false, lastError)
            return false
        }
        
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                super.onStartSuccess(settingsInEffect)
                Log.d(TAG, "✅ Advertising started successfully")
                Log.d(TAG, "📊 AdvertiseSettings: mode=${settingsInEffect.mode}, connectable=${settingsInEffect.isConnectable}")
                
                lastError = null
                updateState(true, null)
            }
            
            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                    else -> "UNKNOWN_ERROR"
                }
                
                Log.e(TAG, "❌ Advertising failed: $errorMessage (code: $errorCode)")
                
                lastError = errorMessage
                currentCallback = null
                currentAdvertiser = null
                updateState(false, errorMessage)
            }
        }
        
        // Store references for proper cleanup
        currentCallback = callback
        currentAdvertiser = bluetoothLeAdvertiser
        
        // Create minimal manufacturer-only advertising data (Samsung A51 compatible)
        val manufacturerData = createManufacturerData(context)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AuraProtocol.MANUFACTURER_ID, manufacturerData)
            .build()
        
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true) // REQUIRED for GATT match requests
            .setTimeout(0)
            .build()
        
        try {
            Log.d(TAG, "🚀 Starting connectable advertising with ${manufacturerData.size + 2} byte payload")
            bluetoothLeAdvertiser?.startAdvertising(advertiseSettings, advertiseData, callback)
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception starting advertising", e)
            lastError = "Security exception"
            currentCallback = null
            currentAdvertiser = null
            updateState(false, lastError)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception starting advertising", e)
            lastError = "Exception: ${e.message}"
            currentCallback = null
            currentAdvertiser = null
            updateState(false, lastError)
            return false
        }
    }
    
    private fun stopAdvertisingInternal() {
        Log.d(TAG, "📡 Stopping BLE advertising (internal)")
        
        try {
            // Use the stored callback and advertiser for proper cleanup
            currentCallback?.let { callback ->
                currentAdvertiser?.stopAdvertising(callback)
                Log.d(TAG, "✅ Advertising stopped using stored callback")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception stopping advertising", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping advertising", e)
        }
        
        currentCallback = null
        currentAdvertiser = null
        lastError = null
        updateState(false, null)
    }
    
    private fun updateState(isAdvertising: Boolean, error: String?) {
        _advertisingState.value = isAdvertising
        lastError = error
        stateListener?.onAdvertisingStateChanged(isAdvertising, error)
        Log.d(TAG, "📊 State updated: isAdvertising=$isAdvertising, error=$error")
    }
    
    private fun createManufacturerData(context: Context): ByteArray {
        val prefs = userPrefs ?: UserPreferences(context)
        val gender = prefs.getGender() ?: "U"
        
        val genderByte = when (gender.uppercase()) {
            "M", "MALE" -> AuraProtocol.GENDER_MALE
            "F", "FEMALE" -> AuraProtocol.GENDER_FEMALE
            else -> AuraProtocol.GENDER_UNKNOWN
        }
        
        val userId = prefs.getUserId()
        val userIdBytes = userId.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val fullHash = digest.digest(userIdBytes)
        val userHash = fullHash.copyOf(8)
        
        val buffer = ByteBuffer.allocate(AuraProtocol.MANUFACTURER_DATA_SIZE)
        // Magic header (3 bytes): "AUR" = [0x41, 0x55, 0x52]
        buffer.put(AuraProtocol.MAGIC_HEADER_BYTES)
        // Protocol version (1 byte)
        buffer.put(AuraProtocol.PROTOCOL_VERSION)
        // Gender (1 byte)
        buffer.put(genderByte)
        // User hash (8 bytes)
        buffer.put(userHash)
        
        return buffer.array()
    }
    
    private fun logSystemStatus(context: Context) {
        Log.d(TAG, "📊 System Status Check:")
        
        // Bluetooth status
        val bluetoothEnabled = isBluetoothEnabled()
        Log.d(TAG, "  Bluetooth enabled: $bluetoothEnabled")
        
        // Location status
        val locationEnabled = isLocationEnabled(context)
        Log.d(TAG, "  Location enabled: $locationEnabled")
        
        // Permission statuses
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            val advertiseGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val locationGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "  BLUETOOTH_ADVERTISE: $advertiseGranted")
            Log.d(TAG, "  BLUETOOTH_CONNECT: $connectGranted")
            Log.d(TAG, "  BLUETOOTH_SCAN: $scanGranted")
            Log.d(TAG, "  ACCESS_FINE_LOCATION: $locationGranted")
        } else {
            // Android 11 and below
            val bluetoothGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdminGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val locationGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "  BLUETOOTH: $bluetoothGranted")
            Log.d(TAG, "  BLUETOOTH_ADMIN: $bluetoothAdminGranted")
            Log.d(TAG, "  ACCESS_FINE_LOCATION: $locationGranted")
            Log.d(TAG, "  ACCESS_COARSE_LOCATION: $coarseLocationGranted")
        }
        
        // User preferences
        val prefs = userPrefs ?: UserPreferences(context)
        Log.d(TAG, "  Publishing enabled: ${prefs.getVisibilityEnabled()}")
        Log.d(TAG, "  Gender selected: ${prefs.getGender()}")
    }
    
    private fun hasRequiredPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val advertiseGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            advertiseGranted && connectGranted
        } else {
            val bluetoothGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdminGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            bluetoothGranted && bluetoothAdminGranted
        }
    }
    
    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
    
    // Public getters
    fun isAdvertising(): Boolean = _advertisingState.value
    fun getLastError(): String? = lastError
}