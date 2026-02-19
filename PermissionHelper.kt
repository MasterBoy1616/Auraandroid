package com.aura.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object PermissionHelper {
    
    private const val TAG = "PermissionHelper"
    
    /**
     * Get all required permissions for the current Android version
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - All three Bluetooth permissions required
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, // Critical for advertising
                Manifest.permission.ACCESS_FINE_LOCATION // Still needed for scanning
            )
        } else {
            // Android 11 and below
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    
    /**
     * Get critical Bluetooth permissions for advertising (Android 12+)
     */
    fun getCriticalBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - Only critical Bluetooth permissions, NO location
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            // Android 11 and below - include location as it's required
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    
    /**
     * Check if critical Bluetooth permissions are granted (for advertising)
     */
    fun hasCriticalBluetoothPermissions(context: Context): Boolean {
        val criticalPermissions = getCriticalBluetoothPermissions()
        return criticalPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get only the scanning-specific permissions
     */
    fun getScanningPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if advertising permissions are granted
     */
    fun hasAdvertisingPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val advertiseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            advertiseGranted && connectGranted
        } else {
            val bluetoothGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdminGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            bluetoothGranted && bluetoothAdminGranted
        }
    }
    
    /**
     * Check if scanning permissions are granted
     */
    fun hasScanningPermissions(context: Context): Boolean {
        val scanningPermissions = getScanningPermissions()
        return scanningPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Get missing permissions from the required set
     */
    fun getMissingPermissions(context: Context): List<String> {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Log detailed permission status for debugging
     */
    fun logPermissionStatus(context: Context) {
        Log.d(TAG, "=== Permission Status ===")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "Android 12+ permissions:")
            val advertise = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "  BLUETOOTH_ADVERTISE: $advertise")
            Log.d(TAG, "  BLUETOOTH_CONNECT: $connect")
            Log.d(TAG, "  BLUETOOTH_SCAN: $scan")
            Log.d(TAG, "  ACCESS_FINE_LOCATION: $location")
        } else {
            Log.d(TAG, "Android <=11 permissions:")
            val bluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdmin = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "  BLUETOOTH: $bluetooth")
            Log.d(TAG, "  BLUETOOTH_ADMIN: $bluetoothAdmin")
            Log.d(TAG, "  ACCESS_FINE_LOCATION: $fineLocation")
            Log.d(TAG, "  ACCESS_COARSE_LOCATION: $coarseLocation")
        }
        
        Log.d(TAG, "All required permissions granted: ${hasAllRequiredPermissions(context)}")
        Log.d(TAG, "Advertising permissions granted: ${hasAdvertisingPermissions(context)}")
        Log.d(TAG, "Scanning permissions granted: ${hasScanningPermissions(context)}")
        Log.d(TAG, "========================")
    }
    
    /**
     * Check if BLUETOOTH_ADVERTISE specifically is missing (Android 12+ only)
     */
    fun isBluetoothAdvertiseMissing(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        } else {
            false // Not applicable for older Android versions
        }
    }
}