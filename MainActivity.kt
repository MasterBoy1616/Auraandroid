package com.aura.link

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : BaseThemedActivity(), BleEngine.BleEngineListener {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // UI Components - safe to initialize
    private lateinit var tvStatus: TextView
    private lateinit var tvAdvertisingStatus: TextView
    private lateinit var btnScan: Button
    private lateinit var tvHeaderLogo: TextView
    private lateinit var tvScanStatus: TextView
    private lateinit var tvScanSubtext: TextView
    private lateinit var radarPulse: View
    private lateinit var recyclerViewDevices: RecyclerView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var nearbyUserAdapter: NearbyUserAdapter
    private lateinit var userPrefs: UserPreferences
    
    // Crystal System
    private lateinit var crystalManager: CrystalManager
    private lateinit var tvCrystalBalance: LinearLayout
    private lateinit var tvAuraBalanceText: TextView
    
    // Premium Features System
    private lateinit var spontaneousFeatures: SpontaneousFeatures
    
    // Premium Feature UI Components
    private lateinit var tvAuraBoostStatus: TextView
    private lateinit var tvInstantPulseStatus: TextView
    private lateinit var tvMoodStatus: TextView
    private lateinit var tvGoldenHourStatus: TextView
    
    // YENİ Premium Feature UI Components
    private lateinit var tvQuickReactionsStatus: TextView
    private lateinit var tvProximityAlertsStatus: TextView
    private lateinit var tvVoiceIntroStatus: TextView
    private lateinit var tvPremiumThemeStatus: TextView
    private lateinit var tvRadarEffectStatus: TextView
    
    // BLE Engine - new architecture
    private var isScanning = false
    private var permissionDialogShown = false // Prevent multiple dialogs
    private var advertisePermissionRetryCount = 0 // Track retry attempts
    private val handler = Handler(Looper.getMainLooper())
    
    // Premium features state
    private var currentRadarEffect: String? = null // Aktif radar efekti
    
    // Match request dialog management
    private var currentMatchRequestDialog: AlertDialog? = null
    private val processedMatchRequests = mutableSetOf<String>() // Prevent duplicate dialogs
    
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Bluetooth enabled by user")
            ensurePermissionsForUI()
        } else {
            tvStatus.text = getString(R.string.bluetooth_disabled)
            showTopToast(getString(R.string.turn_on_bluetooth))
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "🔐 Permission result received:")
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "  $permission: $granted")
        }
        
        handlePermissionResult(permissions)
        
        // BOOTSTRAP: After permissions granted, start components
        if (hasCriticalPermissions()) {
            bootstrapAfterPermissions()
        }
    }
    
    private val appSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User returned from app settings, reset state and check permissions again
        Log.d(TAG, "📱 Returned from app settings, rechecking permissions")
        permissionDialogShown = false
        advertisePermissionRetryCount = 0 // Reset retry count
        
        // Bootstrap if permissions are now granted
        if (hasCriticalPermissions()) {
            bootstrapAfterPermissions()
        } else {
            updateUI()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        Log.d(TAG, "🚀 onCreate() called")
        
        // Initialize UI first
        initUI()
        
        // Initialize Crystal System
        initCrystalSystem()
        
        // Initialize Premium Features
        initPremiumFeatures()
        
        // Initialize BLE components
        initBleComponents()
        
        // Setup advertising status listener
        setupAdvertisingStatusListener()
        
        // Check permissions for UI functionality only (advertising handled manually)
        ensurePermissionsForUI()
        
        // CRITICAL: Always start background scanning for receiving messages/matches
        // This should work regardless of visibility setting
        handler.postDelayed({
            if (hasCriticalPermissions()) {
                Log.d(TAG, "🔍 CRITICAL: Starting FORCED background scanning for message reception (always needed)")
                BleEngineManager.forceBackgroundScanningAlways()
                
                // Also start advertising if visibility is enabled
                val userPrefs = getUserPreferences()
                if (userPrefs.getVisibilityEnabled()) {
                    Log.d(TAG, "📡 Auto-starting broadcast on app launch (visibility enabled)")
                    BleEngineManager.startAdvertising()
                    
                    // Set up periodic advertising health check
                    setupAdvertisingHealthCheck()
                    
                    // Force UI update after starting advertising
                    handler.postDelayed({
                        updateAdvertisingStatus()
                    }, 2000) // Reasonable delay for UI update
                }
            } else {
                Log.d(TAG, "⚠️ Permissions not ready, will retry when permissions granted")
            }
        }, 2000) // Reduced back to 2 seconds for faster startup
        
        // Observe match request notifications
        observeMatchRequests()
        
        // Show manufacturer-specific optimization tips for problematic devices
        handler.postDelayed({
            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            if (manufacturer.contains("vivo") || manufacturer.contains("realme") || 
                manufacturer.contains("oppo") || manufacturer.contains("xiaomi") || 
                manufacturer.contains("redmi") || manufacturer.contains("huawei") || 
                manufacturer.contains("honor")) {
                showManufacturerOptimizationTips()
            }
        }, 3000) // Show after 3 seconds
    }
    
    private fun initUI() {
        Log.d(TAG, "🎨 Initializing UI components")
        
        userPrefs = getUserPreferences()
        
        tvStatus = findViewById(R.id.tvStatus)
        tvAdvertisingStatus = findViewById(R.id.tvAdvertisingStatus)
        btnScan = findViewById(R.id.btnScan)
        tvHeaderLogo = findViewById(R.id.tvHeaderLogo)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        tvScanSubtext = findViewById(R.id.tvScanSubtext)
        radarPulse = findViewById(R.id.radarPulse)
        recyclerViewDevices = findViewById(R.id.recyclerViewDevices)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        
        btnScan.setOnClickListener {
            toggleScan()
        }
        
        // Make AURA logo clickable for broadcast toggle
        tvHeaderLogo.setOnClickListener {
            toggleBroadcast()
        }
        
        setupRecyclerView()
        setupBottomNavigation()
        
        // Set initial UI state
        tvStatus.text = getString(R.string.starting)
        tvAdvertisingStatus.text = getString(R.string.broadcast_off)
        
        // Initialize crystal balance display
        tvCrystalBalance = findViewById(R.id.tvCrystalBalance)
        tvAuraBalanceText = findViewById(R.id.tvAuraBalanceText)
        tvCrystalBalance.setOnClickListener {
            openCrystalStore()
        }
        
        // Initialize premium feature indicators
        tvAuraBoostStatus = findViewById(R.id.tvAuraBoostStatus)
        tvInstantPulseStatus = findViewById(R.id.tvInstantPulseStatus)
        tvMoodStatus = findViewById(R.id.tvMoodStatus)
        tvGoldenHourStatus = findViewById(R.id.tvGoldenHourStatus)
        
        // YENİ Premium Feature UI bileşenleri
        tvQuickReactionsStatus = findViewById(R.id.tvQuickReactionsStatus)
        tvProximityAlertsStatus = findViewById(R.id.tvProximityAlertsStatus)
        tvVoiceIntroStatus = findViewById(R.id.tvVoiceIntroStatus)
        tvPremiumThemeStatus = findViewById(R.id.tvPremiumThemeStatus)
        tvRadarEffectStatus = findViewById(R.id.tvRadarEffectStatus)
    }
    
    private fun initCrystalSystem() {
        Log.d(TAG, "Initializing Aura System")
        
        crystalManager = CrystalManager(this)
        
        // Complete daily login task
        val loginReward = crystalManager.completeLoginTask()
        if (loginReward > 0) {
            showTopToast("${getString(R.string.daily_login_reward)}: +$loginReward ${getString(R.string.aura_points)}!")
        }
        
        // İlk kullanım için günlük giriş ödülü yeterli
        
        // Observe crystal balance - REAL-TIME UPDATE FIX
        lifecycleScope.launch {
            crystalManager.crystalBalance.collect { balance ->
                runOnUiThread {
                    tvAuraBalanceText.text = "$balance"
                    Log.d(TAG, "Aura balance updated in UI: $balance")
                }
            }
        }
        
        // Observe daily tasks for notifications
        lifecycleScope.launch {
            crystalManager.dailyTasks.collect { tasks ->
                checkDailyTaskNotifications(tasks)
            }
        }
        
        Log.d(TAG, "✅ Crystal System initialized")
    }
    
    private fun initPremiumFeatures() {
        Log.d(TAG, "🌟 Initializing Premium Features System")
        
        spontaneousFeatures = SpontaneousFeatures.getInstance(this)
        
        // Check initial states
        checkInitialPremiumStates()
        
        // Observe premium feature states and integrate with BLE
        lifecycleScope.launch {
            spontaneousFeatures.auraBoostActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🔥 Aura Boost state changed: $isActive")
                    if (isActive) {
                        tvAuraBoostStatus.visibility = View.VISIBLE
                        tvAuraBoostStatus.text = getString(R.string.aura_boost_active_main)
                        Log.d(TAG, "🔥 Aura Boost activated - increasing BLE power")
                        BleEngineManager.getInstance()?.setHighPowerMode(true)
                        
                        // GERÇEK ENTEGRASYON: Radar animasyonunu hızlandır
                        applyAuraBoostEffects(true)
                        
                        // Kullanıcıya feedback ver
                        showTopToast(getString(R.string.aura_boost_feedback))
                    } else {
                        tvAuraBoostStatus.visibility = View.GONE
                        BleEngineManager.getInstance()?.setHighPowerMode(false)
                        
                        // Efektleri kaldır
                        applyAuraBoostEffects(false)
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.instantPulseActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "⚡ Instant Pulse state changed: $isActive")
                    if (isActive) {
                        tvInstantPulseStatus.visibility = View.VISIBLE
                        tvInstantPulseStatus.text = getString(R.string.instant_pulse_active_main)
                        Log.d(TAG, "⚡ Instant Pulse activated - fast scanning")
                        BleEngineManager.getInstance()?.setFastScanMode(true)
                        
                        // GERÇEK ENTEGRASYON: Hızlı tarama efektleri
                        applyInstantPulseEffects(true)
                        
                        // Kullanıcıya feedback ver
                        showTopToast(getString(R.string.instant_pulse_feedback))
                    } else {
                        tvInstantPulseStatus.visibility = View.GONE
                        BleEngineManager.getInstance()?.setFastScanMode(false)
                        
                        // Efektleri kaldır
                        applyInstantPulseEffects(false)
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.currentMood.collect { mood ->
                runOnUiThread {
                    Log.d(TAG, "😊 Mood state changed: $mood")
                    if (mood != null) {
                        tvMoodStatus.visibility = View.VISIBLE
                        tvMoodStatus.text = getString(R.string.mood_signal_display, mood.type.emoji, mood.message)
                        Log.d(TAG, "😊 Mood signal active: ${mood.type}")
                        BleEngineManager.getInstance()?.setMoodData(mood.type.name, mood.message)
                    } else {
                        tvMoodStatus.visibility = View.GONE
                        BleEngineManager.getInstance()?.clearMoodData()
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.goldenHourActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🌅 Golden Hour state changed: $isActive")
                    if (isActive) {
                        tvGoldenHourStatus.visibility = View.VISIBLE
                        tvGoldenHourStatus.text = getString(R.string.golden_hour_active_main)
                        Log.d(TAG, "🌅 Golden Hour activated - priority mode")
                        BleEngineManager.getInstance()?.setPriorityMode(true)
                    } else {
                        tvGoldenHourStatus.visibility = View.GONE
                        BleEngineManager.getInstance()?.setPriorityMode(false)
                    }
                }
            }
        }
        
        // YENİ: Quick Reactions observer
        lifecycleScope.launch {
            spontaneousFeatures.quickReactionsRemaining.collect { remaining ->
                runOnUiThread {
                    Log.d(TAG, "🎯 Quick Reactions remaining changed: $remaining")
                    if (remaining > 0) {
                        tvQuickReactionsStatus.visibility = View.VISIBLE
                        tvQuickReactionsStatus.text = getString(R.string.quick_reactions_remaining_main, remaining)
                    } else {
                        tvQuickReactionsStatus.visibility = View.GONE
                    }
                }
            }
        }
        
        // YENİ: Proximity Alerts observer
        lifecycleScope.launch {
            spontaneousFeatures.proximityAlertsActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "📍 Proximity Alerts state changed: $isActive")
                    if (isActive) {
                        tvProximityAlertsStatus.visibility = View.VISIBLE
                        tvProximityAlertsStatus.text = getString(R.string.proximity_alerts_active_main)
                    } else {
                        tvProximityAlertsStatus.visibility = View.GONE
                    }
                }
            }
        }
        
        // YENİ: Voice Intro observer
        lifecycleScope.launch {
            spontaneousFeatures.voiceIntroActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🎤 Voice Intro state changed: $isActive")
                    if (isActive) {
                        tvVoiceIntroStatus.visibility = View.VISIBLE
                        tvVoiceIntroStatus.text = getString(R.string.voice_intro_active_main)
                    } else {
                        tvVoiceIntroStatus.visibility = View.GONE
                    }
                }
            }
        }
        
        // YENİ: Premium Theme observer
        lifecycleScope.launch {
            spontaneousFeatures.premiumThemeActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🎨 Premium Theme state changed: $isActive")
                    if (isActive) {
                        val themeName = spontaneousFeatures.getCurrentTheme()
                        tvPremiumThemeStatus.visibility = View.VISIBLE
                        tvPremiumThemeStatus.text = getString(R.string.premium_theme_active_main, themeName)
                        
                        // Temayı uygula
                        applyPremiumTheme(themeName)
                    } else {
                        tvPremiumThemeStatus.visibility = View.GONE
                        applyPremiumTheme(null)
                    }
                }
            }
        }
        
        // YENİ: Radar Effect observer
        lifecycleScope.launch {
            spontaneousFeatures.radarEffectActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🌀 Radar Effect state changed: $isActive")
                    if (isActive) {
                        val effectName = spontaneousFeatures.getCurrentRadarEffect()
                        tvRadarEffectStatus.visibility = View.VISIBLE
                        tvRadarEffectStatus.text = getString(R.string.radar_effect_active_main, effectName)
                        
                        // Radar efektini uygula
                        applyRadarEffect(effectName)
                    } else {
                        tvRadarEffectStatus.visibility = View.GONE
                        applyRadarEffect(null)
                    }
                }
            }
        }
        
        Log.d(TAG, "✅ Premium Features System initialized")
    }
    
    private fun setupAdvertisingHealthCheck() {
        val healthCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val userPrefs = getUserPreferences()
                    if (userPrefs.getVisibilityEnabled()) {
                        val isCurrentlyAdvertising = BleEngineManager.isAdvertisingActive()
                        Log.d(TAG, "🏥 HEALTH_CHECK: Visibility enabled, advertising active: $isCurrentlyAdvertising")
                        
                        // SIMPLIFIED: Only restart if definitely not advertising
                        if (!isCurrentlyAdvertising) {
                            Log.w(TAG, "🔄 HEALTH_CHECK: Advertising stopped, gentle restart...")
                            BleEngineManager.startAdvertising()
                        }
                        
                        // CRITICAL: Always ensure background scanning is active
                        val isBackgroundScanning = BleEngineManager.isBackgroundScanningActive()
                        if (!isBackgroundScanning) {
                            Log.w(TAG, "🔄 HEALTH_CHECK: Background scanning stopped, restarting...")
                            BleEngineManager.ensureBackgroundScanning()
                        }
                        
                        // Update UI
                        updateAdvertisingStatus()
                        
                        // Schedule next check - less frequent for stability
                        handler.postDelayed(this, 25000) // Check every 25 seconds
                    } else {
                        Log.d(TAG, "🏥 HEALTH_CHECK: Visibility disabled, stopping health check")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in advertising health check", e)
                    // Continue checking despite error
                    handler.postDelayed(this, 30000)
                }
            }
        }
        
        // Start health check after initial delay
        handler.postDelayed(healthCheckRunnable, 25000) // First check after 25 seconds
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 onResume() - NOT restarting advertising (Build 32 fix)")
        
        // CRITICAL FIX: Do NOT restart advertising on tab switch!
        // Advertising should only be restarted by:
        // 1. App startup
        // 2. Bluetooth state change
        // 3. Visibility toggle in Profile
        // 4. Health check if advertising is actually dead
        
        // Only update UI status
        updateAdvertisingStatus()
        
        // Ensure background scanning (for message reception)
        BleEngineManager.ensureBackgroundScanning()
        Log.d(TAG, "🔍 onResume: Background scanning ensured")
    }
    
    private fun checkInitialPremiumStates() {
        Log.d(TAG, "🔍 Checking initial premium feature states")
        
        // Force check all premium features
        handler.postDelayed({
            try {
                val auraBoostActive = spontaneousFeatures.auraBoostActive.value
                val instantPulseActive = spontaneousFeatures.instantPulseActive.value
                val currentMood = spontaneousFeatures.currentMood.value
                val goldenHourActive = spontaneousFeatures.goldenHourActive.value
                
                Log.d(TAG, "🔍 Initial states - Aura Boost: $auraBoostActive, Instant Pulse: $instantPulseActive, Mood: $currentMood, Golden Hour: $goldenHourActive")
                
                // Force UI update
                runOnUiThread {
                    tvAuraBoostStatus.visibility = if (auraBoostActive) View.VISIBLE else View.GONE
                    tvInstantPulseStatus.visibility = if (instantPulseActive) View.VISIBLE else View.GONE
                    tvMoodStatus.visibility = if (currentMood != null) View.VISIBLE else View.GONE
                    tvGoldenHourStatus.visibility = if (goldenHourActive) View.VISIBLE else View.GONE
                    
                    if (auraBoostActive) tvAuraBoostStatus.text = getString(R.string.aura_boost_active_main)
                    if (instantPulseActive) tvInstantPulseStatus.text = getString(R.string.instant_pulse_active_main)
                    if (currentMood != null) tvMoodStatus.text = "${currentMood.type.emoji} ${currentMood.message}"
                    if (goldenHourActive) tvGoldenHourStatus.text = getString(R.string.golden_hour_active_main)
                    
                    Log.d(TAG, "🔍 UI updated with initial states")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking initial premium states", e)
            }
        }, 1000) // 1 second delay to ensure everything is initialized
    }
    
    private fun initBleComponents() {
        Log.d(TAG, "📡 Initializing BLE Engine Manager")
        
        try {
            BleEngineManager.initialize(this)
            
            // RELAXED: Always allow app to work, even with limited BLE functionality
            Log.d(TAG, "📡 BLE Engine Manager initialized - allowing all devices")
            
            BleEngineManager.addListener(this)
            
            // Set current user from preferences
            val userId = userPrefs.getUserId()
            BleEngineManager.setCurrentUser(userId)
            
            Log.d(TAG, "✅ BLE Engine Manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize BLE Engine Manager", e)
            tvStatus.text = getString(R.string.device_not_compatible_limited)
            // Don't prevent app from working - allow limited functionality
        }
    }
    
    private fun showManufacturerOptimizationTips() {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val model = android.os.Build.MODEL
        
        val result = when {
            manufacturer.contains("vivo") -> {
                getString(R.string.vivo_device_optimization_title) to 
                getString(R.string.vivo_device_optimization_message, model)
            }
            manufacturer.contains("realme") || manufacturer.contains("oppo") -> {
                getString(R.string.realme_oppo_device_optimization_title) to
                getString(R.string.realme_oppo_device_optimization_message, model)
            }
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                getString(R.string.xiaomi_redmi_device_optimization_title) to
                getString(R.string.xiaomi_redmi_device_optimization_message, model)
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                getString(R.string.huawei_honor_device_optimization_title) to
                getString(R.string.huawei_honor_device_optimization_message, model)
            }
            else -> null
        }
        
        val (title, message) = result ?: (null to null)
        
        if (title != null && message != null) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(getString(R.string.understood_button)) { dialog, _ -> dialog.dismiss() }
                .setNegativeButton(getString(R.string.later_button)) { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }
    
    private fun setupAdvertisingStatusListener() {
        // BLE Engine handles advertising internally
        Log.d(TAG, "BLE Engine manages advertising lifecycle")
    }
    
    private fun setupRecyclerView() {
        nearbyUserAdapter = NearbyUserAdapter()
        nearbyUserAdapter.setOnUserClickListener { userHash ->
            openUserDetail(userHash)
        }
        recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = nearbyUserAdapter
        }
        
        // Observe nearby users from BLE Engine
        lifecycleScope.launch {
            BleEngineManager.getInstance()?.nearbyUsersFlow?.collect { users ->
                runOnUiThread {
                    nearbyUserAdapter.updateUsers(users)
                }
            }
        }
    }
    
    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_discover
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_discover -> {
                    // Already on discover screen
                    true
                }
                R.id.nav_matches -> {
                    startActivity(Intent(this, MatchesActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun observeMatchRequests() {
        try {
            // Observe new request events for in-app notifications only
            lifecycleScope.launch {
                MatchRequestManager.newRequestEvent.collect { userHash ->
                    if (userHash != null) {
                        runOnUiThread {
                            showInAppMatchNotification(userHash)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up match request observers: ${e.message}", e)
            // App continues without notification functionality
        }
    }
    
    // Badge functionality completely removed to prevent crashes
    private fun updateMatchesBadge(count: Int) {
        // No-op: Badge functionality disabled for stability
        Log.d(TAG, "📊 Badge update requested (count: $count) - disabled for stability")
    }
    
    private fun removeBadgeIfExists() {
        // No-op: Badge functionality disabled for stability
        Log.d(TAG, "🧹 Badge removal requested - disabled for stability")
    }
    
    private fun showInAppMatchNotification(userHash: String) {
        // Show a simple toast for now - could be enhanced with a banner
        Toast.makeText(this, getString(R.string.new_match_request_received_toast), Toast.LENGTH_SHORT).show()
        Log.d(TAG, "🔔 Showed in-app notification for: $userHash")
    }
    
    /**
     * Bootstrap function called after permissions are granted
     * Ensures BLE Engine is properly started
     */
    private fun bootstrapAfterPermissions() {
        Log.d(TAG, "🚀 BOOTSTRAP: Starting post-permission bootstrap")
        
        if (!hasCriticalPermissions()) {
            Log.w(TAG, "BOOTSTRAP: Critical permissions still missing")
            return
        }
        
        // Start advertising if visibility is enabled
        val userPrefs = getUserPreferences()
        if (userPrefs.getVisibilityEnabled()) {
            BleEngineManager.startAdvertising()
            BleEngineManager.ensureBackgroundScanning()
            Log.d(TAG, "📡 Started advertising AND background scanning via BLE Engine Manager")
        }
        
        // Update UI to reflect new state
        updateUI()
    }
    
    private fun ensurePermissionsForUI() {
        Log.d(TAG, "🔍 ensurePermissionsForUI() called")
        
        if (!isBluetoothSupported()) {
            Log.e(TAG, "❌ BLE not supported")
            tvStatus.text = getString(R.string.ble_not_supported_status)
            return
        }
        
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "⚠️ Bluetooth disabled, requesting enable")
            requestEnableBluetooth()
            return
        }
        
        // Check permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkAndroid12Permissions()
        } else {
            checkLegacyPermissions()
        }
    }
    
    private fun isBluetoothSupported(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    private fun checkAndroid12Permissions() {
        Log.d(TAG, "🔐 Checking Android 12+ permissions")
        
        // For Android 12+, we need ALL these Bluetooth permissions plus location and notifications
        val requiredPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val requiredPermissionsArray = requiredPermissions.toTypedArray()
        
        val missingPermissions = requiredPermissionsArray.filter { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "  $permission: $granted")
            !granted
        }
        
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "❌ Missing permissions: ${missingPermissions.joinToString(", ")}")
            
            // Request ALL missing permissions at once - no special handling
            Log.d(TAG, "📋 Requesting ALL missing permissions at once: ${missingPermissions.joinToString(", ")}")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "✅ All Android 12+ permissions granted")
            updateUI()
        }
    }
    
    private fun checkLegacyPermissions() {
        Log.d(TAG, "🔐 Checking legacy Android permissions")
        
        val legacyPermissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        val missingPermissions = legacyPermissions.filter { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "  $permission: $granted")
            !granted
        }
        
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "❌ Missing legacy permissions: ${missingPermissions.joinToString(", ")}")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            Log.d(TAG, "✅ All legacy permissions granted")
            updateUI()
        }
    }
    
    private fun handleBluetoothAdvertisePermission() {
        Log.d(TAG, "🎯 Handling BLUETOOTH_ADVERTISE permission specifically")
        
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this, 
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        
        Log.d(TAG, "shouldShowRequestPermissionRationale for BLUETOOTH_ADVERTISE: $shouldShowRationale")
        Log.d(TAG, "advertisePermissionRetryCount: $advertisePermissionRetryCount")
        
        when {
            !shouldShowRationale && advertisePermissionRetryCount > 0 -> {
                // User likely selected "Don't ask again" or this is Samsung's behavior
                Log.w(TAG, "🚫 User likely denied with 'Don't ask again' or Samsung blocking - showing settings dialog")
                showAdvertisePermissionSettingsDialog()
            }
            advertisePermissionRetryCount < 1 -> {
                // First attempt or retry - request the permission
                Log.d(TAG, "🔄 Requesting BLUETOOTH_ADVERTISE permission (attempt ${advertisePermissionRetryCount + 1})")
                advertisePermissionRetryCount++
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE))
            }
            else -> {
                // Multiple attempts failed - show settings dialog
                Log.w(TAG, "🚫 Multiple attempts failed - showing settings dialog")
                showAdvertisePermissionSettingsDialog()
            }
        }
    }
    
    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { granted -> granted }
        
        if (allGranted) {
            Log.d(TAG, "✅ All requested permissions granted")
            permissionDialogShown = false
            advertisePermissionRetryCount = 0 // Reset on success
            
            // Bootstrap after permissions granted
            bootstrapAfterPermissions()
        } else {
            Log.w(TAG, "❌ Some permissions denied")
            
            // Check specifically for BLUETOOTH_ADVERTISE on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val advertiseGranted = permissions[Manifest.permission.BLUETOOTH_ADVERTISE] ?: false
                Log.d(TAG, "BLUETOOTH_ADVERTISE result: $advertiseGranted")
                
                if (!advertiseGranted) {
                    handleBluetoothAdvertisePermission()
                } else {
                    // BLUETOOTH_ADVERTISE granted but others might be missing
                    // Check if we have the minimum required for advertising
                    val connectGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (advertiseGranted && connectGranted) {
                        Log.d(TAG, "✅ Critical advertising permissions granted")
                        // Bootstrap with partial permissions
                        bootstrapAfterPermissions()
                    }
                }
            }
            
            tvStatus.text = getString(R.string.permission_needed_status)
            tvAdvertisingStatus.text = getString(R.string.broadcast_off_status)
            updateUI()
        }
    }
    
    
    private fun showAdvertisePermissionSettingsDialog() {
        if (permissionDialogShown) {
            Log.d(TAG, "Permission dialog already shown, skipping")
            return
        }
        
        permissionDialogShown = true
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.visibility_permission_required_title))
            .setMessage(getString(R.string.visibility_permission_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(getString(R.string.cancel_button)) { dialog, _ ->
                dialog.dismiss()
                tvStatus.text = getString(R.string.permission_needed_status)
                tvAdvertisingStatus.text = getString(R.string.broadcast_off_status)
            }
            .setCancelable(false)
            .show()
    }
    
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            Log.d(TAG, "🔧 Opening app settings for package: $packageName")
            appSettingsLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open app settings", e)
            showTopToast(getString(R.string.settings_could_not_open))
        }
    }
    
    private fun requestEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                == PackageManager.PERMISSION_GRANTED) {
                enableBluetoothLauncher.launch(enableBtIntent)
            } else {
                // Need to request BLUETOOTH_CONNECT first
                ensurePermissionsForUI()
            }
        } else {
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }
    
    private fun updateUI() {
        Log.d(TAG, "🎨 updateUI() called")
        
        when {
            !isBluetoothSupported() -> {
                tvStatus.text = getString(R.string.ble_not_supported)
                btnScan.isEnabled = false
            }
            !isBluetoothEnabled() -> {
                tvStatus.text = getString(R.string.bluetooth_disabled)
                btnScan.isEnabled = false
            }
            !hasCriticalPermissions() -> {
                tvStatus.text = getString(R.string.permission_needed)
                btnScan.isEnabled = false
            }
            !isLocationEnabled() -> {
                tvStatus.text = getString(R.string.location_disabled_scan_wont_work)
                btnScan.isEnabled = true
            }
            else -> {
                tvStatus.text = getString(R.string.bluetooth_ready)
                btnScan.isEnabled = true
                
                // CRITICAL: ALWAYS ensure background scanning is active when UI is ready
                // This is needed for receiving match requests and messages
                BleEngineManager.ensureBackgroundScanning()
                Log.d(TAG, "🔍 updateUI: ALWAYS ensured background scanning is active")
            }
        }
        
        updateAdvertisingStatus()
        updateButtonAppearanceForGender()
    }
    
    private fun hasCriticalPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - Only check critical Bluetooth permissions
            val advertiseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            
            Log.d(TAG, "🔐 Critical permissions check:")
            Log.d(TAG, "  BLUETOOTH_ADVERTISE: $advertiseGranted")
            Log.d(TAG, "  BLUETOOTH_CONNECT: $connectGranted")
            Log.d(TAG, "  BLUETOOTH_SCAN: $scanGranted")
            
            advertiseGranted && connectGranted && scanGranted
        } else {
            // Android 11 and below
            val bluetoothGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val bluetoothAdminGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            bluetoothGranted && bluetoothAdminGranted && locationGranted
        }
    }
    
    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter?.isEnabled == true
    }
    
    private fun updateAdvertisingStatus() {
        val userPrefs = getUserPreferences()
        val visibilityEnabled = userPrefs.getVisibilityEnabled()
        val isAdvertising = BleEngineManager.isAdvertisingActive()
        val isBackgroundScanning = BleEngineManager.isBackgroundScanningActive()
        
        val statusText = when {
            userPrefs.getGender() == null -> getString(R.string.broadcast_off_gender_not_selected)
            !visibilityEnabled -> getString(R.string.broadcast_off_visibility_disabled)
            !hasCriticalPermissions() -> getString(R.string.broadcast_off_permission_required)
            !isBluetoothEnabled() -> getString(R.string.broadcast_off_bluetooth_disabled)
            visibilityEnabled && isAdvertising -> getString(R.string.broadcast_on_receiving_match_requests)
            visibilityEnabled -> getString(R.string.broadcast_starting)
            else -> getString(R.string.broadcast_off_general)
        }
        
        // Add background scanning status for debugging
        val debugStatus = if (isBackgroundScanning) getString(R.string.background_scanning_on_debug) else getString(R.string.background_scanning_off_debug)
        
        tvAdvertisingStatus.text = statusText + debugStatus
        Log.d(TAG, "📊 Updated status: $statusText (visibility=$visibilityEnabled, advertising=$isAdvertising, bgScan=$isBackgroundScanning)")
    }
    
    private fun updateButtonAppearanceForGender() {
        val userPrefs = getUserPreferences()
        val userGender = userPrefs.getGender()
        
        when (userGender?.uppercase()) {
            "M", "MALE" -> {
                // Erkek: Pembe yazı + Mavi zemin
                btnScan.setBackgroundResource(R.drawable.scan_button_male)
                btnScan.setTextColor(ContextCompat.getColor(this, R.color.neon_pink)) // Pembe yazı
                Log.d(TAG, "🎨 Button styled for MALE user: Pink text on Blue background")
            }
            "F", "FEMALE" -> {
                // Kadın: Mavi yazı + Pembe zemin  
                btnScan.setBackgroundResource(R.drawable.scan_button_female)
                btnScan.setTextColor(ContextCompat.getColor(this, R.color.neon_blue)) // Mavi yazı
                Log.d(TAG, "🎨 Button styled for FEMALE user: Blue text on Pink background")
            }
            else -> {
                // Varsayılan: Neon mavi yazı + koyu zemin
                btnScan.setBackgroundResource(R.drawable.scan_button_icon)
                btnScan.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
                Log.d(TAG, "🎨 Button styled for UNKNOWN gender: Default blue theme")
            }
        }
    }
    
    private fun openUserDetail(userHash: String) {
        val intent = Intent(this, UserDetailActivity::class.java)
        intent.putExtra(UserDetailActivity.EXTRA_USER_HASH, userHash)
        startActivity(intent)
    }
    
    private fun toggleScan() {
        if (isScanning) {
            stopBleScan()
        } else {
            startBleScan()
        }
    }
    
    private fun startBleScan() {
        Log.d(TAG, "🔍 startBleScan() called")
        
        // Check location permission first
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        if (!hasLocationPermission) {
            Log.w(TAG, "⚠️ Location permission needed for scanning")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        
        // Check if location is enabled (required for BLE scanning on some devices)
        if (!isLocationEnabled()) {
            Log.w(TAG, "⚠️ Location is disabled - scanning may not work")
            showLocationDisabledDialog()
            return
        }
        
        if (!hasCriticalPermissions()) {
            ensurePermissionsForUI()
            return
        }
        
        if (!isBluetoothEnabled()) {
            requestEnableBluetooth()
            return
        }
        
        nearbyUserAdapter.clearUsers()
        BleEngineManager.startScanning()
        
        // CRITICAL: Also ensure background scanning for message reception
        BleEngineManager.ensureBackgroundScanning()
        
        // Update UI
        isScanning = true
        tvStatus.text = getString(R.string.scanning_status)
        tvScanStatus.text = getString(R.string.aura_open)
        tvScanSubtext.text = getString(R.string.finding_friends)
        startRadarAnimation()
        
        Log.d(TAG, "🔍 Started both UI scanning and background scanning for messages")
    }
    
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
    
    private fun showLocationDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_disabled_title))
            .setMessage(getString(R.string.location_disabled_message))
            .setPositiveButton(getString(R.string.open_settings_action)) { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton(getString(R.string.cancel_button)) { dialog, _ ->
                dialog.dismiss()
                tvStatus.text = getString(R.string.location_disabled_scanning_wont_work)
            }
            .setCancelable(false)
            .show()
    }
    
    private fun openLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open location settings", e)
            showTopToast(getString(R.string.location_settings_could_not_open))
        }
    }
    
    private fun toggleBroadcast() {
        Log.d(TAG, "🎯 AURA logo clicked - toggling broadcast")
        
        val userPrefs = getUserPreferences()
        val currentlyVisible = userPrefs.getVisibilityEnabled()
        
        if (currentlyVisible) {
            // Turn OFF broadcast
            userPrefs.setVisibilityEnabled(false)
            BleEngineManager.stopAdvertising()
            showTopToast("📡 ${getString(R.string.broadcast_off)}", Toast.LENGTH_SHORT)
            Log.d(TAG, "📡 Broadcast turned OFF via logo click")
        } else {
            // Turn ON broadcast
            val message = when {
                userPrefs.getGender() == null -> {
                    getString(R.string.gender_not_selected_message)
                }
                !hasCriticalPermissions() -> {
                    getString(R.string.permission_missing_message)
                }
                !isBluetoothEnabled() -> {
                    getString(R.string.bluetooth_disabled_message)
                }
                !isLocationEnabled() -> {
                    getString(R.string.location_services_disabled_message)
                }
                else -> {
                    // Enable visibility and start advertising
                    userPrefs.setVisibilityEnabled(true)
                    BleEngineManager.startAdvertising()
                    BleEngineManager.ensureBackgroundScanning()
                    Log.d(TAG, "📡 Broadcast turned ON via logo click")
                    "✅ ${getString(R.string.broadcasting_receiving_match_requests)}"
                }
            }
            
            showTopToast(message, Toast.LENGTH_LONG)
            
            // If permissions are missing, try to request them
            if (!hasCriticalPermissions()) {
                ensurePermissionsForUI()
            }
        }
        
        // Update UI
        updateAdvertisingStatus()
    }
    
    private fun stopBleScan() {
        Log.d(TAG, "🛑 stopBleScan() called")
        BleEngineManager.stopScanning()
        
        // Update UI
        isScanning = false
        tvStatus.text = getString(R.string.bluetooth_ready_status)
        tvScanStatus.text = getString(R.string.open_aura)
        tvScanSubtext.text = getString(R.string.find_friends)
        stopRadarAnimation()
    }
    
    private fun startRadarAnimation() {
        radarPulse.visibility = View.VISIBLE
        
        // Önce premium radar efekti var mı kontrol et
        if (currentRadarEffect != null) {
            Log.d(TAG, "🌀 Applying premium radar effect: $currentRadarEffect")
            applyRadarEffectToAnimation(currentRadarEffect!!)
        } else {
            // Varsayılan animasyon - cinsiyet bazlı
            val userGender = userPrefs.getGender()
            startAlternatingRadarAnimation(userGender)
            Log.d(TAG, "🎯 Started default radar animation for gender: $userGender")
        }
    }
    
    private fun startAlternatingRadarAnimation(userGender: String?) {
        var isPink = true
        
        val animationRunnable = object : Runnable {
            override fun run() {
                if (!isScanning) return // Stop if scanning stopped
                
                // Alternate between pink and blue
                val backgroundRes = if (isPink) {
                    R.drawable.radar_pulse_female // Pink
                } else {
                    R.drawable.radar_pulse_male // Blue
                }
                
                val animationRes = if (isPink) {
                    R.anim.radar_pulse_female
                } else {
                    R.anim.radar_pulse_male
                }
                
                // Set background and start animation
                radarPulse.setBackgroundResource(backgroundRes)
                val animation = AnimationUtils.loadAnimation(this@MainActivity, animationRes)
                radarPulse.startAnimation(animation)
                
                // Toggle color for next iteration
                isPink = !isPink
                
                // Schedule next color change in 2 seconds
                if (isScanning) {
                    handler.postDelayed(this, 2000)
                }
            }
        }
        
        // Start immediately
        handler.post(animationRunnable)
    }
    
    private fun stopRadarAnimation() {
        radarPulse.visibility = View.GONE
        radarPulse.clearAnimation()
    }
    
    // BleEngine.BleEngineListener implementation
    override fun onIncomingMatchRequest(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "📥 Incoming match request from: $senderHash")
            showMatchRequestDialog(senderHash)
        }
    }
    
    override fun onMatchAccepted(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "✅ MAIN_ACTIVITY: Match accepted from: $senderHash")
            
            // CRITICAL FIX: Store the match in MatchStore
            try {
                val nearbyUsers = BleEngineManager.getInstance()?.nearbyUsersFlow?.value ?: emptyList()
                val user = nearbyUsers.find { it.userHash == senderHash }
                val userName = user?.userName ?: "User${senderHash.take(4).uppercase()}"
                val userGender = user?.gender ?: "U"
                
                // Store the successful match
                val matchStore = MatchStore(this@MainActivity)
                matchStore.storeMatch(senderHash, userGender)
                Log.d(TAG, "✅ MAIN_ACTIVITY: Match stored successfully for $senderHash")
                
                showTopToast(getString(R.string.match_successful_with_user, getString(R.string.match_successful), userName), Toast.LENGTH_LONG)
                
                // Reward crystals for match
                rewardCrystalForMatch()
                
                Log.d(TAG, "✅ MAIN_ACTIVITY: Success toast shown for match with $userName")
            } catch (e: Exception) {
                Log.e(TAG, "❌ MAIN_ACTIVITY: Error storing match", e)
                showTopToast(getString(R.string.match_successful_simple))
            }
        }
    }
    
    override fun onMatchRejected(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "❌ Match rejected from: $senderHash")
            showTopToast(getString(R.string.match_rejected_simple))
        }
    }
    
    override fun onChatMessage(senderHash: String, message: String) {
        runOnUiThread {
            Log.d(TAG, "💬 Chat message from: $senderHash")
            showTopToast(getString(R.string.new_message_received))
        }
    }
    
    override fun onPhotoReceived(senderHash: String, photoBase64: String) {
        runOnUiThread {
            Log.d(TAG, "📷 Photo received from: $senderHash")
            // Photo is automatically cached by MatchRequestManager
        }
    }
    
    override fun onPhotoRequested(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "📷 Photo requested by: $senderHash")
            // Photo request is automatically handled by MatchRequestManager
        }
    }
    
    override fun onUnmatchReceived(senderHash: String) {
        try {
            // Safety check - ensure activity is not destroyed
            if (isDestroyed || isFinishing) {
                Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring unmatch")
                return
            }
            
            runOnUiThread {
                try {
                    Log.d(TAG, "💔 Unmatch received from: $senderHash")
                    showTopToast(getString(R.string.match_cancelled))
                    
                    // Remove from nearby users if present
                    if (::nearbyUserAdapter.isInitialized) {
                        nearbyUserAdapter.removeUser(senderHash)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling unmatch in UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling unmatch", e)
        }
    }
    
    override fun onBlockReceived(senderHash: String) {
        try {
            // Safety check - ensure activity is not destroyed
            if (isDestroyed || isFinishing) {
                Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring block")
                return
            }
            
            runOnUiThread {
                try {
                    Log.d(TAG, "🚫 Block received from: $senderHash")
                    showTopToast(getString(R.string.user_blocked_you))
                    
                    // Remove from nearby users if present
                    if (::nearbyUserAdapter.isInitialized) {
                        nearbyUserAdapter.removeUser(senderHash)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling block in UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling block", e)
        }
    }
    
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, -300) // Ekranın ortasında, yukarıda
        toast.show()
    }
    
    // Crystal System Functions
    private fun openCrystalStore() {
        try {
            Log.d(TAG, "Opening Aura Store...")
            val intent = Intent(this, CrystalStoreActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error opening Crystal Store", e)
            showTopToast(getString(R.string.aura_store_error, e.message))
        }
    }
    
    private fun checkDailyTaskNotifications(tasks: CrystalManager.DailyTasks) {
        // Görev tamamlama bildirimleri (sadece bir kez göster)
        // Bu fonksiyon günlük görevlerin durumunu kontrol eder
    }
    
    private fun rewardCrystalForMatch() {
        val reward = crystalManager.completeFirstMatchTask()
        crystalManager.updateWeeklyProgress("match")
        
        if (reward > 0) {
            showTopToast(getString(R.string.first_match_reward_toast, reward), Toast.LENGTH_LONG)
        }
    }
    
    private fun rewardCrystalForMessage() {
        val reward = crystalManager.completeFirstMessageTask()
        crystalManager.updateWeeklyProgress("message")
        
        if (reward > 0) {
            showTopToast(getString(R.string.first_message_reward_toast, reward), Toast.LENGTH_LONG)
        }
    }
    
    private fun rewardCrystalForChat() {
        crystalManager.updateWeeklyProgress("chat")
    }
    
    /**
     * Apply Aura Boost visual and functional effects
     */
    private fun applyAuraBoostEffects(enabled: Boolean) {
        Log.d(TAG, "🔥 AURA_BOOST_EFFECTS: Applying effects, enabled=$enabled")
        
        if (enabled) {
            // Visual effects for Aura Boost
            try {
                // 1. Faster radar animation
                if (isScanning) {
                    // Speed up radar pulse animation
                    val fastAnimation = AnimationUtils.loadAnimation(this, R.anim.radar_pulse)
                    fastAnimation.duration = 800 // Faster animation (normal is 1500ms)
                    radarPulse.startAnimation(fastAnimation)
                    Log.d(TAG, "🔥 AURA_BOOST: Radar animation accelerated")
                }
                
                // 2. Enhanced UI feedback
                tvHeaderLogo.alpha = 1.0f
                tvHeaderLogo.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(300)
                    .start()
                
                // 3. Change scan button appearance for boost mode
                btnScan.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(300)
                    .start()
                
                Log.d(TAG, "🔥 AURA_BOOST: Visual effects applied")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error applying Aura Boost effects", e)
            }
        } else {
            // Remove Aura Boost effects
            try {
                // Reset animations to normal speed
                if (isScanning) {
                    val normalAnimation = AnimationUtils.loadAnimation(this, R.anim.radar_pulse)
                    normalAnimation.duration = 1500 // Normal speed
                    radarPulse.startAnimation(normalAnimation)
                }
                
                // Reset UI elements
                tvHeaderLogo.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(300)
                    .start()
                
                btnScan.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(300)
                    .start()
                
                Log.d(TAG, "🔥 AURA_BOOST: Effects removed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error removing Aura Boost effects", e)
            }
        }
    }
    
    /**
     * Apply Instant Pulse visual and functional effects
     */
    private fun applyInstantPulseEffects(enabled: Boolean) {
        Log.d(TAG, "⚡ INSTANT_PULSE_EFFECTS: Applying effects, enabled=$enabled")
        
        if (enabled) {
            // Visual effects for Instant Pulse
            try {
                // 1. Ultra-fast radar animation
                if (isScanning) {
                    val ultraFastAnimation = AnimationUtils.loadAnimation(this, R.anim.radar_pulse)
                    ultraFastAnimation.duration = 500 // Ultra fast (normal is 1500ms)
                    ultraFastAnimation.repeatCount = android.view.animation.Animation.INFINITE
                    radarPulse.startAnimation(ultraFastAnimation)
                    Log.d(TAG, "⚡ INSTANT_PULSE: Ultra-fast radar animation started")
                }
                
                // 2. Pulsing scan status text
                tvScanStatus.animate()
                    .alpha(0.7f)
                    .setDuration(250)
                    .withEndAction {
                        tvScanStatus.animate()
                            .alpha(1.0f)
                            .setDuration(250)
                            .start()
                    }
                    .start()
                
                // 3. Enhanced scan button with pulsing effect
                val pulseRunnable = object : Runnable {
                    override fun run() {
                        if (spontaneousFeatures.instantPulseActive.value) {
                            btnScan.animate()
                                .scaleX(1.1f)
                                .scaleY(1.1f)
                                .setDuration(400)
                                .withEndAction {
                                    btnScan.animate()
                                        .scaleX(1.0f)
                                        .scaleY(1.0f)
                                        .setDuration(400)
                                        .start()
                                }
                                .start()
                            
                            handler.postDelayed(this, 1000) // Pulse every second
                        }
                    }
                }
                handler.post(pulseRunnable)
                
                // 4. Update scan subtext
                tvScanSubtext.text = getString(R.string.fast_scanning_active_subtext)
                
                Log.d(TAG, "⚡ INSTANT_PULSE: Visual effects applied")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error applying Instant Pulse effects", e)
            }
        } else {
            // Remove Instant Pulse effects
            try {
                // Reset radar animation to normal
                if (isScanning) {
                    val normalAnimation = AnimationUtils.loadAnimation(this, R.anim.radar_pulse)
                    normalAnimation.duration = 1500 // Normal speed
                    radarPulse.startAnimation(normalAnimation)
                }
                
                // Reset UI elements
                tvScanStatus.clearAnimation()
                tvScanStatus.alpha = 1.0f
                
                btnScan.clearAnimation()
                btnScan.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(300)
                    .start()
                
                // Reset scan subtext
                tvScanSubtext.text = if (isScanning) getString(R.string.finding_friends) else getString(R.string.find_friends)
                
                Log.d(TAG, "⚡ INSTANT_PULSE: Effects removed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error removing Instant Pulse effects", e)
            }
        }
    }
    
    /**
     * Premium tema uygulama - DRAMATIK VE GÖRSEL EFEKTLER
     */
    private fun applyPremiumTheme(themeName: String?) {
        Log.d(TAG, "🎨 PREMIUM_THEME: Applying theme: $themeName")
        
        // Ana container'ı bul
        val mainContainer = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.mainContainer)
        
        if (themeName != null && mainContainer != null) {
            Log.d(TAG, "🎨 PREMIUM_THEME: Container found, applying theme: $themeName")
            
            when (themeName) {
                "Galaksi" -> {
                    // GALAKSI TEMASI - Dramatik uzay teması
                    window.statusBarColor = 0xFF000011.toInt() // Çok koyu mavi
                    
                    // DRAMATIK GRADYAN - Koyu mavi'den mor'a
                    val galaxyGradient = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            0xFF000011.toInt(), // Çok koyu mavi (üst)
                            0xFF1A0033.toInt(), // Koyu mor (orta)
                            0xFF330066.toInt()  // Mor (alt)
                        )
                    )
                    mainContainer.background = galaxyGradient
                    
                    // Tüm text'leri parlak mor/mavi yap
                    tvHeaderLogo.setTextColor(0xFF9966FF.toInt()) // Parlak mor
                    tvStatus.setTextColor(0xFF6699FF.toInt()) // Parlak mavi
                    tvScanStatus.setTextColor(0xFF9966FF.toInt()) // Parlak mor
                    tvScanSubtext.setTextColor(0xFF6699FF.toInt()) // Parlak mavi
                    btnScan.setTextColor(0xFF9966FF.toInt()) // Parlak mor
                    
                    // Yıldız efekti
                    startStarEffect()
                    
                    Log.d(TAG, getString(R.string.galaxy_theme_applied_log))
                }
                "Gün Batımı" -> {
                    // GÜN BATIMI TEMASI - Çok dramatik turuncu-pembe
                    window.statusBarColor = 0xFFFF4500.toInt() // Turuncu
                    
                    // DRAMATIK GRADYAN - Turuncu'dan pembe'ye
                    val sunsetGradient = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            0xFFFF4500.toInt(), // Parlak turuncu (üst)
                            0xFFFF6347.toInt(), // Domates kırmızısı (orta)
                            0xFFFF1493.toInt()  // Derin pembe (alt)
                        )
                    )
                    mainContainer.background = sunsetGradient
                    
                    // Tüm text'leri beyaz/sarı yap (kontrast için)
                    tvHeaderLogo.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvStatus.setTextColor(0xFFFFD700.toInt()) // Altın sarısı
                    tvScanStatus.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvScanSubtext.setTextColor(0xFFFFD700.toInt()) // Altın sarısı
                    btnScan.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    
                    // Gün batımı efekti
                    startSunsetEffect()
                    
                    Log.d(TAG, getString(R.string.sunset_theme_applied_log))
                }
                "Okyanus" -> {
                    // OKYANUS TEMASI - Dramatik mavi-yeşil
                    window.statusBarColor = 0xFF006994.toInt() // Koyu mavi
                    
                    // DRAMATIK GRADYAN - Koyu mavi'den açık yeşil'e
                    val oceanGradient = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            0xFF006994.toInt(), // Koyu deniz mavisi (üst)
                            0xFF0099CC.toInt(), // Orta mavi (orta)
                            0xFF00CED1.toInt()  // Turkuaz (alt)
                        )
                    )
                    mainContainer.background = oceanGradient
                    
                    // Tüm text'leri beyaz/açık mavi yap
                    tvHeaderLogo.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvStatus.setTextColor(0xFF87CEEB.toInt()) // Açık mavi
                    tvScanStatus.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvScanSubtext.setTextColor(0xFF87CEEB.toInt()) // Açık mavi
                    btnScan.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    
                    // Dalga efekti
                    startOceanWaveEffect()
                    
                    Log.d(TAG, getString(R.string.ocean_theme_applied_log))
                }
                "Ateş" -> {
                    // ATEŞ TEMASI - Çok dramatik kırmızı-turuncu
                    window.statusBarColor = 0xFF8B0000.toInt() // Koyu kırmızı
                    
                    // DRAMATIK GRADYAN - Koyu kırmızı'dan parlak turuncu'ya
                    val fireGradient = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(
                            0xFF8B0000.toInt(), // Koyu kırmızı (alt)
                            0xFFDC143C.toInt(), // Crimson (orta)
                            0xFFFF4500.toInt()  // Parlak turuncu (üst)
                        )
                    )
                    mainContainer.background = fireGradient
                    
                    // Tüm text'leri beyaz/sarı yap (kontrast için)
                    tvHeaderLogo.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvStatus.setTextColor(0xFFFFD700.toInt()) // Altın sarısı
                    tvScanStatus.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    tvScanSubtext.setTextColor(0xFFFFD700.toInt()) // Altın sarısı
                    btnScan.setTextColor(0xFFFFFFFF.toInt()) // Beyaz
                    
                    // Alev efekti
                    startFireEffect()
                    
                    Log.d(TAG, getString(R.string.fire_theme_applied_log))
                }
                "Aurora" -> {
                    // AURORA TEMASI - Çok renkli kutup ışığı
                    window.statusBarColor = 0xFF000033.toInt() // Çok koyu mavi
                    
                    // AURORA İÇİN KOYU ARKA PLAN (renk değişimi için)
                    mainContainer.setBackgroundColor(0xFF000033.toInt()) // Çok koyu mavi
                    
                    // Aurora efektleri - sürekli renk değişimi
                    startAuroraColorEffect()
                    startAuroraUIEffect()
                    startAuroraBackgroundEffect() // Yeni: Arka plan renk değişimi
                    
                    Log.d(TAG, getString(R.string.aurora_theme_applied_log))
                }
            }
            
            // FORCE UI UPDATE - Tüm view'ları yeniden çiz
            mainContainer.invalidate()
            mainContainer.requestLayout()
            
            Log.d(TAG, "🎨 Premium theme '$themeName' applied successfully with DRAMATIC effects!")
        } else {
            if (mainContainer == null) {
                Log.e(TAG, "❌ PREMIUM_THEME: mainContainer not found!")
            }
            // Varsayılan temaya dön
            resetToDefaultTheme()
        }
    }
    
    /**
     * Varsayılan temaya dönüş - GELİŞTİRİLMİŞ
     */
    private fun resetToDefaultTheme() {
        Log.d(TAG, "🎨 RESET: Reverting to default theme")
        
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        
        // Ana container'ı bul ve varsayılan arka planı ayarla
        val mainContainer = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.mainContainer)
        
        if (mainContainer != null) {
            // Varsayılan koyu arka plan
            mainContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_background))
            Log.d(TAG, "🎨 RESET: Default background applied to mainContainer")
        } else {
            Log.e(TAG, "❌ RESET: mainContainer not found!")
        }
        
        // Varsayılan text renkleri
        tvHeaderLogo.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        tvScanStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
        tvScanSubtext.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        
        // Buton görünümünü cinsiyete göre ayarla
        updateButtonAppearanceForGender()
        
        // Tüm efektleri durdur
        stopAllThemeEffects()
        
        Log.d(TAG, "🎨 RESET: Reverted to default theme successfully")
    }
    
    /**
     * Yıldız efekti - Galaksi teması için
     */
    private fun startStarEffect() {
        val starRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Galaksi") {
                    
                    // Header'da parıldama efekti
                    tvHeaderLogo.animate()
                        .alpha(0.7f)
                        .setDuration(800)
                        .withEndAction {
                            tvHeaderLogo.animate()
                                .alpha(1.0f)
                                .setDuration(800)
                                .start()
                        }
                        .start()
                    
                    handler.postDelayed(this, 2000) // Her 2 saniyede parılda
                }
            }
        }
        handler.post(starRunnable)
    }
    
    /**
     * Gün batımı efekti - yumuşak parlaklık değişimi
     */
    private fun startSunsetEffect() {
        val sunsetRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Gün Batımı") {
                    
                    // Yumuşak parlaklık değişimi
                    btnScan.animate()
                        .alpha(0.8f)
                        .setDuration(1500)
                        .withEndAction {
                            btnScan.animate()
                                .alpha(1.0f)
                                .setDuration(1500)
                                .start()
                        }
                        .start()
                    
                    handler.postDelayed(this, 3000) // Her 3 saniyede
                }
            }
        }
        handler.post(sunsetRunnable)
    }
    
    /**
     * Okyanus dalga efekti
     */
    private fun startOceanWaveEffect() {
        val waveRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Okyanus") {
                    
                    // Dalga gibi yumuşak hareket
                    tvHeaderLogo.animate()
                        .translationY(-10f)
                        .setDuration(2000)
                        .withEndAction {
                            tvHeaderLogo.animate()
                                .translationY(0f)
                                .setDuration(2000)
                                .start()
                        }
                        .start()
                    
                    handler.postDelayed(this, 4000) // Her 4 saniyede
                }
            }
        }
        handler.post(waveRunnable)
    }
    
    /**
     * Ateş efekti - titreşimli parlaklık
     */
    private fun startFireEffect() {
        val fireRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Ateş") {
                    
                    // Alev gibi titreşim
                    tvHeaderLogo.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .alpha(0.9f)
                        .setDuration(200)
                        .withEndAction {
                            tvHeaderLogo.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(1.0f)
                                .setDuration(200)
                                .start()
                        }
                        .start()
                    
                    handler.postDelayed(this, 400 + (Math.random() * 600).toLong()) // Rastgele aralık
                }
            }
        }
        handler.post(fireRunnable)
    }
    
    /**
     * Aurora UI efekti - tüm UI elementlerinde renk değişimi
     */
    private fun startAuroraUIEffect() {
        val colors = arrayOf(
            android.R.color.holo_blue_light,
            android.R.color.holo_purple,
            android.R.color.holo_green_light,
            R.color.neon_pink,
            R.color.neon_blue
        )
        var colorIndex = 0
        
        val auroraUIRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Aurora") {
                    
                    val color = ContextCompat.getColor(this@MainActivity, colors[colorIndex])
                    
                    // Tüm UI elementlerini renk değiştir
                    tvHeaderLogo.setTextColor(color)
                    btnScan.setTextColor(color)
                    tvStatus.setTextColor(color)
                    tvScanStatus.setTextColor(color)
                    tvScanSubtext.setTextColor(color)
                    
                    colorIndex = (colorIndex + 1) % colors.size
                    handler.postDelayed(this, 1500) // Her 1.5 saniyede renk değiştir
                }
            }
        }
        handler.post(auroraUIRunnable)
    }
    
    /**
     * Tüm tema efektlerini durdur - GELİŞTİRİLMİŞ
     */
    private fun stopAllThemeEffects() {
        Log.d(TAG, "🛑 EFFECTS: Stopping all theme and radar effects")
        
        // Tüm handler callback'lerini temizle
        handler.removeCallbacksAndMessages(null)
        
        // Animasyonları temizle
        tvHeaderLogo.clearAnimation()
        btnScan.clearAnimation()
        radarPulse.clearAnimation()
        
        // Pozisyon ve ölçekleri sıfırla - Header
        tvHeaderLogo.translationY = 0f
        tvHeaderLogo.scaleX = 1.0f
        tvHeaderLogo.scaleY = 1.0f
        tvHeaderLogo.alpha = 1.0f
        tvHeaderLogo.rotation = 0f
        
        // Pozisyon ve ölçekleri sıfırla - Button
        btnScan.alpha = 1.0f
        btnScan.scaleX = 1.0f
        btnScan.scaleY = 1.0f
        
        // Pozisyon ve ölçekleri sıfırla - Radar (ÖNEMLİ!)
        radarPulse.alpha = 1.0f
        radarPulse.scaleX = 1.0f
        radarPulse.scaleY = 1.0f
        radarPulse.rotation = 0f
        radarPulse.translationX = 0f
        radarPulse.translationY = 0f
        
        // Radar rengini varsayılana döndür
        val userPrefs = getUserPreferences()
        val gender = userPrefs.getGender()
        if (gender == "M") {
            radarPulse.setBackgroundResource(R.drawable.radar_pulse_male)
        } else {
            radarPulse.setBackgroundResource(R.drawable.radar_pulse_female)
        }
        
        // Mevcut radar efektini temizle
        currentRadarEffect = null
        
        Log.d(TAG, "✅ EFFECTS: All theme and radar effects stopped and reset")
    }
    
    /**
     * Aurora teması için renk değişimi efekti
     */
    private fun startAuroraColorEffect() {
        val colors = arrayOf(
            android.R.color.holo_blue_light,
            android.R.color.holo_purple,
            android.R.color.holo_green_light,
            R.color.neon_pink,
            R.color.neon_blue
        )
        var colorIndex = 0
        
        val auroraRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Aurora") {
                    
                    val color = ContextCompat.getColor(this@MainActivity, colors[colorIndex])
                    tvHeaderLogo.setTextColor(color)
                    btnScan.setTextColor(color)
                    
                    colorIndex = (colorIndex + 1) % colors.size
                    handler.postDelayed(this, 2000) // Her 2 saniyede renk değiştir
                }
            }
        }
        handler.post(auroraRunnable)
    }
    
    /**
     * Aurora teması için arka plan renk değişimi efekti - YENİ
     */
    private fun startAuroraBackgroundEffect() {
        val backgroundColors = intArrayOf(
            0xFF000033.toInt(), // Koyu mavi
            0xFF330033.toInt(), // Koyu mor
            0xFF003300.toInt(), // Koyu yeşil
            0xFF330000.toInt(), // Koyu kırmızı
            0xFF003333.toInt()  // Koyu turkuaz
        )
        var colorIndex = 0
        
        val auroraBackgroundRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Aurora") {
                    
                    val mainContainer = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.mainContainer)
                    if (mainContainer != null) {
                        // Yumuşak geçiş ile arka plan rengini değiştir
                        val colorAnimator = android.animation.ValueAnimator.ofArgb(
                            backgroundColors[colorIndex],
                            backgroundColors[(colorIndex + 1) % backgroundColors.size]
                        )
                        colorAnimator.duration = 3000 // 3 saniye geçiş
                        colorAnimator.addUpdateListener { animator ->
                            val color = animator.animatedValue as Int
                            mainContainer.setBackgroundColor(color)
                        }
                        colorAnimator.start()
                        
                        colorIndex = (colorIndex + 1) % backgroundColors.size
                        handler.postDelayed(this, 4000) // Her 4 saniyede renk değiştir
                    }
                }
            }
        }
        handler.post(auroraBackgroundRunnable)
    }
    
    /**
     * Radar efekt uygulama - DRAMATIK VE ETKİLEYİCİ EFEKTLER
     */
    private fun applyRadarEffect(effectName: String?) {
        Log.d(TAG, "🌀 RADAR_EFFECT: Applying DRAMATIC effect: $effectName")
        
        // Önce mevcut efektleri durdur
        stopCurrentRadarEffect()
        
        // Radar efektini kaydet (tarama başladığında uygulanacak)
        currentRadarEffect = effectName
        
        // Eğer şu anda tarama yapılıyorsa efekti hemen uygula
        if (effectName != null && isScanning) {
            applyRadarEffectToAnimation(effectName)
        }
        
        Log.d(TAG, "🌀 RADAR_EFFECT: '$effectName' ready to activate")
    }
    
    // Mevcut radar efektini durdur
    private fun stopCurrentRadarEffect() {
        radarPulse.clearAnimation()
        radarPulse.alpha = 1.0f
        radarPulse.scaleX = 1.0f
        radarPulse.scaleY = 1.0f
        radarPulse.rotation = 0f
        radarPulse.translationX = 0f
        radarPulse.translationY = 0f
    }
    
    // Radar efektini animasyona uygulayan yardımcı metod - DRAMATIK EFEKTLER
    private fun applyRadarEffectToAnimation(effectName: String) {
        Log.d(TAG, "🌀 APPLYING DRAMATIC RADAR EFFECT: $effectName")
        
        when (effectName) {
            "Nabız" -> {
                // KALP ATIŞI EFEKTİ - Gerçek kalp ritmi ile
                startHeartbeatEffect()
            }
            "Dalga" -> {
                // SU DALGASI EFEKTİ - Çok katmanlı dalga
                startRippleWaveEffect()
            }
            "Spiral" -> {
                // DÖNEN SPİRAL EFEKTİ - Hipnotik döngü
                startHypnoticSpiralEffect()
            }
            "Şimşek" -> {
                // ŞİMŞEK EFEKTİ - Elektrik çarpması gibi
                startLightningStormEffect()
            }
            "Gökkuşağı" -> {
                // GÖKKUŞAĞI EFEKTİ - Sürekli renk değişimi
                startRainbowSpectrumEffect()
            }
        }
        Log.d(TAG, "🌀 DRAMATIC radar effect '$effectName' activated!")
    }
    
    /**
     * KALP ATIŞI EFEKTİ - Gerçek kalp ritmi + BÜYÜK YUVARLAK ŞEKIL
     */
    private fun startHeartbeatEffect() {
        val heartbeatRunnable = object : Runnable {
            override fun run() {
                if (isScanning && currentRadarEffect == "Nabız") {
                    // BÜYÜK YUVARLAK ŞEKIL KORUMA - Özel heartbeat drawable
                    radarPulse.setBackgroundResource(R.drawable.radar_pulse_heartbeat)
                    
                    // Gerçek kalp atışı: çift atış deseni
                    radarPulse.animate()
                        .scaleX(1.6f)
                        .scaleY(1.6f)
                        .alpha(0.9f)
                        .setDuration(120) // Hızlı büyüme
                        .withEndAction {
                            radarPulse.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(0.7f)
                                .setDuration(200) // Yavaş küçülme
                                .withEndAction {
                                    // İkinci atış (çift atış)
                                    radarPulse.animate()
                                        .scaleX(1.3f)
                                        .scaleY(1.3f)
                                        .alpha(0.8f)
                                        .setDuration(100)
                                        .withEndAction {
                                            radarPulse.animate()
                                                .scaleX(1.0f)
                                                .scaleY(1.0f)
                                                .alpha(0.6f)
                                                .setDuration(150)
                                                .withEndAction {
                                                    // Kalp atışları arası bekleme
                                                    handler.postDelayed(this, 1000)
                                                }
                                                .start()
                                        }
                                        .start()
                                }
                                .start()
                        }
                        .start()
                }
            }
        }
        handler.post(heartbeatRunnable)
    }
    
    /**
     * SU DALGASI EFEKTİ - Sürekli dalga hareketi + BÜYÜK YUVARLAK ŞEKIL
     */
    private fun startRippleWaveEffect() {
        val waveRunnable = object : Runnable {
            private var wavePhase = 0f
            
            override fun run() {
                if (isScanning && currentRadarEffect == "Dalga") {
                    // BÜYÜK YUVARLAK ŞEKIL KORUMA - Özel wave drawable
                    radarPulse.setBackgroundResource(R.drawable.radar_pulse_wave)
                    
                    // Sürekli dalga hareketi - çok daha dramatik
                    val scaleX = 1.0f + Math.sin(wavePhase.toDouble()).toFloat() * 0.5f
                    val scaleY = 1.0f + Math.cos(wavePhase * 1.2).toFloat() * 0.4f
                    val alpha = 0.5f + Math.abs(Math.sin(wavePhase.toDouble())).toFloat() * 0.5f
                    
                    radarPulse.animate()
                        .scaleX(scaleX)
                        .scaleY(scaleY)
                        .alpha(alpha)
                        .rotation(wavePhase * 10) // Hafif döngü
                        .setDuration(80)
                        .start()
                    
                    wavePhase += 0.2f
                    if (wavePhase > Math.PI * 4) wavePhase = 0f
                    
                    handler.postDelayed(this, 40) // Çok hızlı güncelleme
                }
            }
        }
        handler.post(waveRunnable)
    }
    
    /**
     * HİPNOTİK SPİRAL EFEKTİ - Sürekli dönen spiral + BÜYÜK YUVARLAK ŞEKIL
     */
    private fun startHypnoticSpiralEffect() {
        val spiralRunnable = object : Runnable {
            private var rotation = 0f
            private var scale = 1.0f
            private var growing = true
            
            override fun run() {
                if (isScanning && currentRadarEffect == "Spiral") {
                    // BÜYÜK YUVARLAK ŞEKIL KORUMA - Özel spiral drawable
                    radarPulse.setBackgroundResource(R.drawable.radar_pulse_spiral)
                    
                    // Sürekli dönen spiral - çok daha hızlı ve dramatik
                    rotation += 25f // Daha hızlı döngü
                    if (rotation >= 360f) rotation = 0f
                    
                    // Büyüyüp küçülen efekt - daha dramatik
                    if (growing) {
                        scale += 0.08f
                        if (scale >= 1.8f) growing = false
                    } else {
                        scale -= 0.08f
                        if (scale <= 0.6f) growing = true
                    }
                    
                    radarPulse.animate()
                        .rotation(rotation)
                        .scaleX(scale)
                        .scaleY(scale)
                        .alpha(0.4f + (scale - 0.6f) * 0.6f) // Daha dramatik şeffaflık
                        .setDuration(60) // Daha hızlı
                        .start()
                    
                    handler.postDelayed(this, 50) // Çok hızlı döngü
                }
            }
        }
        handler.post(spiralRunnable)
    }
    
    /**
     * ŞİMŞEK FIRTINASI EFEKTİ - Rastgele elektrik çarpması + BÜYÜK YUVARLAK ŞEKIL
     */
    private fun startLightningStormEffect() {
        val lightningRunnable = object : Runnable {
            override fun run() {
                if (isScanning && currentRadarEffect == "Şimşek") {
                    // BÜYÜK YUVARLAK ŞEKIL KORUMA - Özel lightning drawable
                    radarPulse.setBackgroundResource(R.drawable.radar_pulse_lightning)
                    
                    // Rastgele şimşek çarpması - çok daha dramatik
                    val intensity = Math.random().toFloat()
                    val maxScale = 2.2f + intensity * 1.0f // Çok büyük şimşek
                    
                    // Ani parlaklık - şimşek çarpması
                    radarPulse.animate()
                        .scaleX(maxScale)
                        .scaleY(maxScale)
                        .alpha(1.0f)
                        .rotation((Math.random() * 20 - 10).toFloat()) // Daha fazla titreşim
                        .setDuration(20) // Çok hızlı büyüme
                        .withEndAction {
                            // Ani küçülme
                            radarPulse.animate()
                                .scaleX(0.5f)
                                .scaleY(0.5f)
                                .alpha(0.2f)
                                .rotation(0f)
                                .setDuration(40)
                                .withEndAction {
                                    // Normal boyuta dönüş
                                    radarPulse.animate()
                                        .scaleX(1.0f)
                                        .scaleY(1.0f)
                                        .alpha(0.7f)
                                        .setDuration(80)
                                        .start()
                                }
                                .start()
                        }
                        .start()
                    
                    // Rastgele aralıklarla şimşek - daha sık
                    val nextDelay = (100 + Math.random() * 600).toLong()
                    handler.postDelayed(this, nextDelay)
                }
            }
        }
        handler.post(lightningRunnable)
    }
    
    /**
     * GÖKKUŞAĞI SPEKTRUM EFEKTİ - Sürekli renk değişimi + BÜYÜK YUVARLAK ŞEKIL
     */
    private fun startRainbowSpectrumEffect() {
        val rainbowRunnable = object : Runnable {
            private var colorIndex = 0
            private var pulsePhase = 0f
            
            // Yuvarlak şekilli renkli drawable'lar - Çok daha fazla renk
            private val rainbowDrawables = arrayOf(
                R.drawable.radar_pulse_rainbow,    // Kırmızı
                R.drawable.radar_pulse_lightning,  // Sarı
                R.drawable.radar_pulse_wave,       // Turkuaz
                R.drawable.radar_pulse_male,       // Mavi
                R.drawable.radar_pulse_spiral,     // Mor
                R.drawable.radar_pulse_female,     // Pembe
                R.drawable.radar_pulse_heartbeat   // Kırmızı-turuncu
            )
            
            override fun run() {
                if (isScanning && currentRadarEffect == "Gökkuşağı") {
                    // BÜYÜK YUVARLAK ŞEKIL İLE RENK DEĞİŞİMİ
                    radarPulse.setBackgroundResource(rainbowDrawables[colorIndex])
                    
                    // Çok dramatik nabız efekti - BÜYÜK YUVARLAK ŞEKILDE
                    val pulseScale = 1.0f + Math.sin(pulsePhase.toDouble()).toFloat() * 0.7f
                    val pulseAlpha = 0.5f + Math.abs(Math.sin(pulsePhase.toDouble())).toFloat() * 0.5f
                    
                    radarPulse.animate()
                        .scaleX(pulseScale)
                        .scaleY(pulseScale)
                        .alpha(pulseAlpha)
                        .rotation(colorIndex * 51f) // Her renk değişiminde farklı açı (51 = 360/7)
                        .setDuration(80)
                        .start()
                    
                    colorIndex = (colorIndex + 1) % rainbowDrawables.size
                    pulsePhase += 0.4f
                    if (pulsePhase > Math.PI * 2) pulsePhase = 0f
                    
                    handler.postDelayed(this, 200) // Daha hızlı renk değişimi
                }
            }
        }
        handler.post(rainbowRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy() called")
        
        // Remove listener to prevent memory leaks
        BleEngineManager.removeListener(this)
        
        // Clean up BLE Engine Manager only if this is the last activity
        // (In practice, this should be handled by Application lifecycle)
    }
    
    private fun showMatchRequestDialog(senderHash: String) {
        // ENHANCED: Prevent duplicate dialogs with stricter controls
        if (processedMatchRequests.contains(senderHash)) {
            Log.d(TAG, "🚫 DIALOG: Duplicate match request dialog prevented for: $senderHash")
            return
        }
        
        // ENHANCED: Close any existing dialog and clear its state
        currentMatchRequestDialog?.let { dialog ->
            Log.d(TAG, "🔄 DIALOG: Closing existing dialog before showing new one")
            dialog.dismiss()
            currentMatchRequestDialog = null
        }
        
        // ENHANCED: Add to processed set immediately to prevent race conditions
        processedMatchRequests.add(senderHash)
        
        // Get user info from BLE Engine
        val nearbyUsers = BleEngineManager.getInstance()?.nearbyUsersFlow?.value ?: emptyList()
        val user = nearbyUsers.find { it.userHash == senderHash }
        val userName = user?.userName ?: "User${senderHash.take(4).uppercase()}"
        val genderIcon = when (user?.gender) {
            "M" -> "👨"
            "F" -> "👩"
            else -> "👤"
        }
        
        Log.d(TAG, "📱 DIALOG: Showing match request dialog for: $userName ($senderHash)")
        
        // ENHANCED: Create dialog with better error handling
        try {
            currentMatchRequestDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.match_request_dialog_title))
                .setMessage(getString(R.string.match_request_dialog_message, userName))
                .setPositiveButton(getString(R.string.accept_match_button)) { dialog, _ ->
                    Log.d(TAG, "✅ DIALOG: Match request ACCEPTED for: $senderHash")
                    
                    // ENHANCED: Send response and handle match storage
                    try {
                        BleEngineManager.getInstance()?.enqueueMatchResponse(true, senderHash)
                        
                        // CRITICAL: Store match immediately on acceptance
                        val matchStore = MatchStore(this@MainActivity)
                        val userGender = user?.gender ?: "U"
                        matchStore.storeMatch(senderHash, userGender)
                        Log.d(TAG, "✅ DIALOG: Match stored successfully for $senderHash")
                        
                        showTopToast(getString(R.string.match_accepted_toast, userName), Toast.LENGTH_LONG)
                        rewardCrystalForMatch()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ DIALOG: Error handling match acceptance", e)
                        showTopToast(getString(R.string.match_accepted_simple_toast))
                    }
                    
                    dialog.dismiss()
                    processedMatchRequests.remove(senderHash)
                    currentMatchRequestDialog = null
                }
                .setNegativeButton(getString(R.string.reject_match_button)) { dialog, _ ->
                    Log.d(TAG, "❌ DIALOG: Match request REJECTED for: $senderHash")
                    
                    try {
                        BleEngineManager.getInstance()?.enqueueMatchResponse(false, senderHash)
                        showTopToast(getString(R.string.match_rejected_toast))
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ DIALOG: Error handling match rejection", e)
                    }
                    
                    dialog.dismiss()
                    processedMatchRequests.remove(senderHash)
                    currentMatchRequestDialog = null
                }
                .setOnCancelListener { dialog ->
                    Log.d(TAG, "🚫 DIALOG: Match request dialog cancelled for: $senderHash")
                    processedMatchRequests.remove(senderHash)
                    currentMatchRequestDialog = null
                }
                .setCancelable(true)
                .show()
                
            Log.d(TAG, "✅ DIALOG: Match request dialog shown successfully for: $userName")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ DIALOG: Error creating match request dialog", e)
            processedMatchRequests.remove(senderHash) // Remove from processed set on error
            currentMatchRequestDialog = null
        }
    }
    
    // Remove old AdvertisingController.StateListener implementation
    // BLE Engine handles advertising internally
}