package com.aura.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat

class DiagnosticsActivity : BaseThemedActivity() {
    
    companion object {
        private const val TAG = "DiagnosticsActivity"
    }
    
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvPermissionsStatus: TextView
    private lateinit var tvBatteryOptimization: TextView
    private lateinit var tvLocationScanHint: TextView
    private lateinit var btnOpenAppSettings: Button
    private lateinit var btnOpenLocationSettings: Button
    private lateinit var btnOpenBatterySettings: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnBack: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        
        initViews()
        setupClickListeners()
        refreshDiagnostics()
    }
    
    private fun initViews() {
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvPermissionsStatus = findViewById(R.id.tvPermissionsStatus)
        tvBatteryOptimization = findViewById(R.id.tvBatteryOptimization)
        tvLocationScanHint = findViewById(R.id.tvLocationScanHint)
        btnOpenAppSettings = findViewById(R.id.btnOpenAppSettings)
        btnOpenLocationSettings = findViewById(R.id.btnOpenLocationSettings)
        btnOpenBatterySettings = findViewById(R.id.btnOpenBatterySettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnBack = findViewById(R.id.btnBack)
    }
    
    private fun setupClickListeners() {
        btnOpenAppSettings.setOnClickListener {
            openAppDetailsSettings()
        }
        
        btnOpenLocationSettings.setOnClickListener {
            openLocationSettings()
        }
        
        btnOpenBatterySettings.setOnClickListener {
            openBatteryOptimizationSettings()
        }
        
        btnRefresh.setOnClickListener {
            refreshDiagnostics()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun refreshDiagnostics() {
        Log.d(TAG, "Refreshing diagnostics...")
        
        checkBluetoothStatus()
        checkLocationStatus()
        checkPermissionsStatus()
        checkBatteryOptimization()
        showLocationScanHint()
    }
    
    private fun checkBluetoothStatus() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        val isEnabled = bluetoothAdapter?.isEnabled == true
        val status = if (isEnabled) "✅ Enabled" else "❌ Disabled"
        val color = if (isEnabled) getColor(R.color.success_green) else getColor(R.color.error_red)
        
        tvBluetoothStatus.text = "Bluetooth: $status"
        tvBluetoothStatus.setTextColor(color)
        
        Log.d(TAG, "Bluetooth status: $isEnabled")
    }
    
    private fun checkLocationStatus() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        val isEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        
        val status = if (isEnabled) "✅ Enabled" else "❌ Disabled"
        val color = if (isEnabled) getColor(R.color.success_green) else getColor(R.color.error_red)
        
        tvLocationStatus.text = "Location Services: $status"
        tvLocationStatus.setTextColor(color)
        
        Log.d(TAG, "Location status: $isEnabled")
    }
    
    private fun checkPermissionsStatus() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ permissions
            val scanPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            val connectPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            val advertisePermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
            
            permissions.add("BLUETOOTH_SCAN: ${if (scanPermission == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}")
            permissions.add("BLUETOOTH_CONNECT: ${if (connectPermission == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}")
            permissions.add("BLUETOOTH_ADVERTISE: ${if (advertisePermission == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}")
            
            Log.d(TAG, "Android 12+ permissions - SCAN: $scanPermission, CONNECT: $connectPermission, ADVERTISE: $advertisePermission")
        } else {
            // Legacy permissions
            val locationPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add("ACCESS_FINE_LOCATION: ${if (locationPermission == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}")
            
            Log.d(TAG, "Legacy permissions - LOCATION: $locationPermission")
        }
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            permissions.add("POST_NOTIFICATIONS: ${if (notificationPermission == PackageManager.PERMISSION_GRANTED) "✅" else "❌"}")
        }
        
        val permissionsText = "Permissions:\n${permissions.joinToString("\n")}"
        tvPermissionsStatus.text = permissionsText
        
        // Set color based on whether all permissions are granted
        val allGranted = permissions.all { it.contains("✅") }
        val color = if (allGranted) getColor(R.color.success_green) else getColor(R.color.error_red)
        tvPermissionsStatus.setTextColor(color)
    }
    
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        val isIgnoringOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Not applicable on older versions
        }
        
        val status = if (isIgnoringOptimizations) {
            "✅ Battery optimization disabled (recommended)"
        } else {
            "⚠️ Battery optimization enabled (may affect background operation)"
        }
        
        val color = if (isIgnoringOptimizations) {
            getColor(R.color.success_green)
        } else {
            getColor(R.color.warning_orange)
        }
        
        tvBatteryOptimization.text = "Battery Optimization: $status"
        tvBatteryOptimization.setTextColor(color)
        
        Log.d(TAG, "Battery optimization ignored: $isIgnoringOptimizations")
    }
    
    private fun showLocationScanHint() {
        val hintText = """
            📍 Location Scan Settings:
            
            For optimal BLE discovery, ensure these are enabled in your device settings:
            
            • Wi-Fi scanning: Settings → Location → Location Services → Wi-Fi scanning
            • Bluetooth scanning: Settings → Location → Location Services → Bluetooth scanning
            
            Note: Exact paths vary by device manufacturer (Samsung, Pixel, etc.)
            
            These settings help improve location accuracy and BLE device discovery even when Wi-Fi/Bluetooth are off.
        """.trimIndent()
        
        tvLocationScanHint.text = hintText
        tvLocationScanHint.setTextColor(getColor(R.color.text_secondary))
    }
    
    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Log.d(TAG, "Opened app details settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
        }
    }
    
    private fun openLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Log.d(TAG, "Opened location settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open location settings", e)
        }
    }
    
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                // Fallback to general battery settings on older versions
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            startActivity(intent)
            Log.d(TAG, "Opened battery optimization settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh diagnostics when returning from settings
        refreshDiagnostics()
    }
}