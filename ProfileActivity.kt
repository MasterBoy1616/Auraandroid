package com.aura.link

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ProfileActivity : BaseThemedActivity(), AdvertisingController.StateListener, BleEngine.BleEngineListener {
    
    companion object {
        private const val TAG = "ProfileActivity"
    }
    
    private lateinit var tvGender: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvAdvertisingStatus: TextView
    private lateinit var btnChangeGender: Button
    private lateinit var btnChangeUserName: Button
    private lateinit var btnChangePhoto: Button
    private lateinit var btnOpenAuraStore: Button
    private lateinit var btnChangeLanguage: Button
    private lateinit var tvCurrentLanguage: TextView
    private lateinit var switchVisibility: Switch
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var userPrefs: UserPreferences
    
    // Premium Features UI
    private lateinit var tvNoActiveFeatures: TextView
    private lateinit var activeFeaturesList: android.widget.LinearLayout
    private lateinit var tvActiveAuraBoost: TextView
    private lateinit var tvActiveInstantPulse: TextView
    private lateinit var tvActiveMood: TextView
    private lateinit var tvActiveGoldenHour: TextView
    private lateinit var spontaneousFeatures: SpontaneousFeatures
    
    // YENİ Premium Features UI
    private lateinit var tvActiveQuickReactions: TextView
    private lateinit var tvActiveProximityAlerts: TextView
    private lateinit var tvActiveVoiceIntro: TextView
    private lateinit var tvActivePremiumTheme: TextView
    private lateinit var tvActiveRadarEffect: TextView
    
    // TEMA YÖNETİMİ UI - YENİ
    private lateinit var tvCurrentTheme: TextView
    private lateinit var tvAvailableThemes: TextView
    private lateinit var themesList: android.widget.LinearLayout
    private lateinit var tvNoThemes: TextView
    private lateinit var btnManageThemes: Button
    
    // RADAR EFEKTİ YÖNETİMİ UI - YENİ
    private lateinit var tvCurrentRadarEffect: TextView
    private lateinit var tvAvailableRadarEffects: TextView
    private lateinit var radarEffectsList: android.widget.LinearLayout
    private lateinit var tvNoRadarEffects: TextView
    private lateinit var btnManageRadarEffects: Button
    
    // Handler for UI updates
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleImageSelection(uri)
            }
        }
    }
    
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, -300) // Ekranın ortasında, yukarıda
        toast.show()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        
        userPrefs = getUserPreferences()
        spontaneousFeatures = SpontaneousFeatures.getInstance(this)
        
        initViews()
        setupBottomNavigation()
        setupAdvertisingController()
        setupLifecycleObserver()
        setupPremiumFeaturesObserver()
        loadProfile()
        
        // Add BLE listener for match requests
        BleEngineManager.addListener(this)
        
        // Start GATT server when profile opens
        AuraCore.startGattServer()
    }
    
    private fun initViews() {
        tvGender = findViewById(R.id.tvGender)
        tvUserName = findViewById(R.id.tvUserName)
        tvAdvertisingStatus = findViewById(R.id.tvAdvertisingStatus)
        btnChangeGender = findViewById(R.id.btnChangeGender)
        btnChangeUserName = findViewById(R.id.btnChangeUserName)
        btnChangePhoto = findViewById(R.id.btnChangePhoto)
        btnOpenAuraStore = findViewById(R.id.btnOpenAuraStore)
        btnChangeLanguage = findViewById(R.id.btnChangeLanguage)
        tvCurrentLanguage = findViewById(R.id.tvCurrentLanguage)
        switchVisibility = findViewById(R.id.switchVisibility)
        ivProfilePhoto = findViewById(R.id.ivProfilePhoto)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        
        // Premium Features UI
        tvNoActiveFeatures = findViewById(R.id.tvNoActiveFeatures)
        activeFeaturesList = findViewById(R.id.activeFeaturesList)
        tvActiveAuraBoost = findViewById(R.id.tvActiveAuraBoost)
        tvActiveInstantPulse = findViewById(R.id.tvActiveInstantPulse)
        tvActiveMood = findViewById(R.id.tvActiveMood)
        tvActiveGoldenHour = findViewById(R.id.tvActiveGoldenHour)
        
        // YENİ Premium Features UI - Artık layout'da mevcut
        tvActiveQuickReactions = findViewById(R.id.tvActiveQuickReactions)
        tvActiveProximityAlerts = findViewById(R.id.tvActiveProximityAlerts)
        tvActiveVoiceIntro = findViewById(R.id.tvActiveVoiceIntro)
        tvActivePremiumTheme = findViewById(R.id.tvActivePremiumTheme)
        tvActiveRadarEffect = findViewById(R.id.tvActiveRadarEffect)
        
        // TEMA YÖNETİMİ UI - YENİ
        tvCurrentTheme = findViewById(R.id.tvCurrentTheme)
        tvAvailableThemes = findViewById(R.id.tvAvailableThemes)
        themesList = findViewById(R.id.themesList)
        tvNoThemes = findViewById(R.id.tvNoThemes)
        btnManageThemes = findViewById(R.id.btnManageThemes)
        
        // RADAR EFEKTİ YÖNETİMİ UI - YENİ
        tvCurrentRadarEffect = findViewById(R.id.tvCurrentRadarEffect)
        tvAvailableRadarEffects = findViewById(R.id.tvAvailableRadarEffects)
        radarEffectsList = findViewById(R.id.radarEffectsList)
        tvNoRadarEffects = findViewById(R.id.tvNoRadarEffects)
        btnManageRadarEffects = findViewById(R.id.btnManageRadarEffects)
        
        // Voice Intro UI
        val tvVoiceIntroStatus = findViewById<TextView>(R.id.tvVoiceIntroStatus)
        val btnRecordVoiceIntro = findViewById<Button>(R.id.btnRecordVoiceIntro)
        
        btnRecordVoiceIntro.setOnClickListener {
            showVoiceRecordingDialog()
        }
        
        btnChangeGender.setOnClickListener {
            val intent = Intent(this, GenderSelectActivity::class.java)
            intent.putExtra("from_profile", true)
            startActivity(intent)
        }
        
        btnChangeUserName.setOnClickListener {
            showChangeUserNameDialog()
        }
        
        btnChangePhoto.setOnClickListener {
            openImagePicker()
        }
        
        btnOpenAuraStore.setOnClickListener {
            val intent = Intent(this, CrystalStoreActivity::class.java)
            startActivity(intent)
        }
        
        btnChangeLanguage.setOnClickListener {
            showLanguageSelectionDialog()
        }
        
        // TEMA YÖNETİMİ - YENİ
        btnManageThemes.setOnClickListener {
            val intent = Intent(this, CrystalStoreActivity::class.java)
            intent.putExtra("open_tab", "themes") // Tema sekmesini aç
            startActivity(intent)
        }
        
        // RADAR EFEKTİ YÖNETİMİ - YENİ
        btnManageRadarEffects.setOnClickListener {
            val intent = Intent(this, CrystalStoreActivity::class.java)
            intent.putExtra("open_tab", "radar_effects") // Radar efekt sekmesini aç
            startActivity(intent)
        }
        
        // Ses test butonu - geçici olarak fotoğraf butonuna uzun basma ile ekleyelim
        btnChangePhoto.setOnLongClickListener {
            showSoundTestDialog()
            true
        }
        
        switchVisibility.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "🔄 Visibility toggle changed: $isChecked")
            userPrefs.setVisibilityEnabled(isChecked)
            
            // Use AuraCore to handle visibility change
            AuraCore.onVisibilityChanged(applicationContext, isChecked)
            
            // Update UI immediately
            updateAdvertisingStatus()
        }
    }
    
    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_profile
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_discover -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_matches -> {
                    startActivity(Intent(this, MatchesActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_profile -> {
                    // Already on profile screen
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupAdvertisingController() {
        AdvertisingController.setStateListener(this)
    }
    
    private fun setupLifecycleObserver() {
        // Lifecycle is now handled by AuraApp and AuraCore
        // No need for duplicate lifecycle observers in activities
        Log.d(TAG, "Lifecycle management delegated to AuraApp/AuraCore")
    }
    
    private fun setupPremiumFeaturesObserver() {
        Log.d(TAG, "🌟 Setting up premium features observer")
        
        // ENHANCED: Add more debugging and force UI updates
        lifecycleScope.launch {
            spontaneousFeatures.auraBoostActive.collect { isActive ->
                Log.d(TAG, "🔥 Aura Boost state changed: $isActive")
                runOnUiThread {
                    if (isActive) {
                        tvActiveAuraBoost.visibility = android.view.View.VISIBLE
                        tvActiveAuraBoost.text = getString(R.string.aura_boost_active)
                        Log.d(TAG, "🔥 Aura Boost UI updated: VISIBLE")
                    } else {
                        tvActiveAuraBoost.visibility = android.view.View.GONE
                        Log.d(TAG, "🔥 Aura Boost UI updated: GONE")
                    }
                    updateActiveFeaturesVisibility()
                    
                    // FORCE UI refresh
                    tvActiveAuraBoost.invalidate()
                    activeFeaturesList.invalidate()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.instantPulseActive.collect { isActive ->
                Log.d(TAG, "⚡ Instant Pulse state changed: $isActive")
                runOnUiThread {
                    if (isActive) {
                        tvActiveInstantPulse.visibility = android.view.View.VISIBLE
                        tvActiveInstantPulse.text = getString(R.string.instant_pulse_active)
                        Log.d(TAG, "⚡ Instant Pulse UI updated: VISIBLE")
                    } else {
                        tvActiveInstantPulse.visibility = android.view.View.GONE
                        Log.d(TAG, "⚡ Instant Pulse UI updated: GONE")
                    }
                    updateActiveFeaturesVisibility()
                    
                    // FORCE UI refresh
                    tvActiveInstantPulse.invalidate()
                    activeFeaturesList.invalidate()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.currentMood.collect { mood ->
                Log.d(TAG, "😊 Mood state changed: $mood")
                runOnUiThread {
                    if (mood != null) {
                        tvActiveMood.visibility = android.view.View.VISIBLE
                        tvActiveMood.text = getString(R.string.mood_signal_active, mood.type.emoji, mood.type.description)
                        Log.d(TAG, "😊 Mood UI updated: VISIBLE - ${mood.type.description}")
                    } else {
                        tvActiveMood.visibility = android.view.View.GONE
                        Log.d(TAG, "😊 Mood UI updated: GONE")
                    }
                    updateActiveFeaturesVisibility()
                    
                    // FORCE UI refresh
                    tvActiveMood.invalidate()
                    activeFeaturesList.invalidate()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.goldenHourActive.collect { isActive ->
                Log.d(TAG, "🌅 Golden Hour state changed: $isActive")
                runOnUiThread {
                    if (isActive) {
                        tvActiveGoldenHour.visibility = android.view.View.VISIBLE
                        tvActiveGoldenHour.text = getString(R.string.golden_hour_active)
                        Log.d(TAG, "🌅 Golden Hour UI updated: VISIBLE")
                    } else {
                        tvActiveGoldenHour.visibility = android.view.View.GONE
                        Log.d(TAG, "🌅 Golden Hour UI updated: GONE")
                    }
                    updateActiveFeaturesVisibility()
                    
                    // FORCE UI refresh
                    tvActiveGoldenHour.invalidate()
                    activeFeaturesList.invalidate()
                }
            }
        }
        
        // YENİ Premium Features Observers
        lifecycleScope.launch {
            spontaneousFeatures.quickReactionsRemaining.collect { remaining ->
                runOnUiThread {
                    Log.d(TAG, "🎯 Quick Reactions remaining changed: $remaining")
                    if (remaining > 0) {
                        tvActiveQuickReactions.visibility = android.view.View.VISIBLE
                        tvActiveQuickReactions.text = getString(R.string.quick_reactions_remaining, remaining)
                    } else {
                        tvActiveQuickReactions.visibility = android.view.View.GONE
                    }
                    updateActiveFeaturesVisibility()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.proximityAlertsActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "📍 Proximity Alerts state changed: $isActive")
                    if (isActive) {
                        tvActiveProximityAlerts.visibility = android.view.View.VISIBLE
                        tvActiveProximityAlerts.text = getString(R.string.proximity_alerts_active)
                    } else {
                        tvActiveProximityAlerts.visibility = android.view.View.GONE
                    }
                    updateActiveFeaturesVisibility()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.voiceIntroActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🎤 Voice Intro state changed: $isActive")
                    if (isActive) {
                        tvActiveVoiceIntro.visibility = android.view.View.VISIBLE
                        tvActiveVoiceIntro.text = getString(R.string.voice_intro_active)
                        
                        // Voice Intro status güncelle
                        findViewById<TextView>(R.id.tvVoiceIntroStatus)?.text = getString(R.string.voice_intro_status_active)
                    } else {
                        tvActiveVoiceIntro.visibility = android.view.View.GONE
                        findViewById<TextView>(R.id.tvVoiceIntroStatus)?.text = getString(R.string.voice_intro_status_not_recorded)
                    }
                    updateActiveFeaturesVisibility()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.premiumThemeActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🎨 Premium Theme state changed: $isActive")
                    if (isActive) {
                        val themeName = spontaneousFeatures.getCurrentTheme()
                        tvActivePremiumTheme.visibility = android.view.View.VISIBLE
                        tvActivePremiumTheme.text = getString(R.string.premium_theme_active, themeName)
                    } else {
                        tvActivePremiumTheme.visibility = android.view.View.GONE
                    }
                    updateActiveFeaturesVisibility()
                }
            }
        }
        
        lifecycleScope.launch {
            spontaneousFeatures.radarEffectActive.collect { isActive ->
                runOnUiThread {
                    Log.d(TAG, "🌀 Radar Effect state changed: $isActive")
                    if (isActive) {
                        val effectName = spontaneousFeatures.getCurrentRadarEffect()
                        tvActiveRadarEffect.visibility = android.view.View.VISIBLE
                        tvActiveRadarEffect.text = getString(R.string.radar_effect_active, effectName)
                    } else {
                        tvActiveRadarEffect.visibility = android.view.View.GONE
                    }
                    updateActiveFeaturesVisibility()
                }
            }
        }
        
        // ENHANCED: Force initial state check after delay
        handler.postDelayed({
            Log.d(TAG, "🔍 FORCE_CHECK: Checking initial premium feature states...")
            val auraBoostActive = spontaneousFeatures.auraBoostActive.value
            val instantPulseActive = spontaneousFeatures.instantPulseActive.value
            val currentMood = spontaneousFeatures.currentMood.value
            val goldenHourActive = spontaneousFeatures.goldenHourActive.value
            
            Log.d(TAG, "🔍 FORCE_CHECK: AuraBoost=$auraBoostActive, InstantPulse=$instantPulseActive, Mood=$currentMood, GoldenHour=$goldenHourActive")
            
            runOnUiThread {
                updateActiveFeaturesVisibility()
            }
        }, 2000)
        
        Log.d(TAG, "✅ Premium features observer setup complete")
    }
    
    private fun updateActiveFeaturesVisibility() {
        val hasActiveFeatures = tvActiveAuraBoost.visibility == android.view.View.VISIBLE ||
                tvActiveInstantPulse.visibility == android.view.View.VISIBLE ||
                tvActiveMood.visibility == android.view.View.VISIBLE ||
                tvActiveGoldenHour.visibility == android.view.View.VISIBLE ||
                tvActiveQuickReactions.visibility == android.view.View.VISIBLE ||
                tvActiveProximityAlerts.visibility == android.view.View.VISIBLE ||
                tvActiveVoiceIntro.visibility == android.view.View.VISIBLE ||
                tvActivePremiumTheme.visibility == android.view.View.VISIBLE ||
                tvActiveRadarEffect.visibility == android.view.View.VISIBLE
        
        Log.d(TAG, "🔍 Updating active features visibility: hasActiveFeatures=$hasActiveFeatures")
        Log.d(TAG, "  - AuraBoost: ${tvActiveAuraBoost.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - InstantPulse: ${tvActiveInstantPulse.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - Mood: ${tvActiveMood.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - GoldenHour: ${tvActiveGoldenHour.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - QuickReactions: ${tvActiveQuickReactions.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - ProximityAlerts: ${tvActiveProximityAlerts.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - VoiceIntro: ${tvActiveVoiceIntro.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - PremiumTheme: ${tvActivePremiumTheme.visibility == android.view.View.VISIBLE}")
        Log.d(TAG, "  - RadarEffect: ${tvActiveRadarEffect.visibility == android.view.View.VISIBLE}")
        
        if (hasActiveFeatures) {
            tvNoActiveFeatures.visibility = android.view.View.GONE
            activeFeaturesList.visibility = android.view.View.VISIBLE
            Log.d(TAG, "✅ Showing active features list")
        } else {
            tvNoActiveFeatures.visibility = android.view.View.VISIBLE
            activeFeaturesList.visibility = android.view.View.GONE
            Log.d(TAG, "❌ Showing 'no active features' message")
        }
    }
    
    private fun loadProfile() {
        val gender = userPrefs.getGender()
        val genderDisplay = when (gender) {
            UserPreferences.GENDER_MALE -> getString(R.string.gender_male_display)
            UserPreferences.GENDER_FEMALE -> getString(R.string.gender_female_display)
            else -> getString(R.string.gender_unspecified)
        }
        tvGender.text = getString(R.string.gender_label, genderDisplay)
        
        // Load user name
        val userName = userPrefs.getUserName()
        tvUserName.text = getString(R.string.username_label, userName)
        
        // Load current language
        val currentLanguageDisplay = LanguageManager.getCurrentLanguageDisplayName(this)
        tvCurrentLanguage.text = getString(R.string.current_language, currentLanguageDisplay)
        
        // Load profile photo
        loadProfilePhoto()
        
        // Load visibility toggle state
        val visibilityEnabled = userPrefs.getVisibilityEnabled()
        switchVisibility.isChecked = visibilityEnabled
        
        // Update advertising status
        updateAdvertisingStatus()
        
        // Start advertising if visibility is enabled (AuraCore will handle this)
        if (visibilityEnabled) {
            Log.d(TAG, "📡 Visibility enabled - AuraCore will manage advertising")
        }
        
        // TEMA YÖNETİMİ - YENİ
        loadThemeManagement()
        
        // RADAR EFEKTİ YÖNETİMİ - YENİ
        loadRadarEffectManagement()
    }
    
    /**
     * Tema yönetimi bölümünü yükle - YENİ (CRASH-SAFE)
     */
    private fun loadThemeManagement() {
        Log.d(TAG, "🎨 Loading theme management")
        
        try {
            // Aktif temayı göster - SAFE CHECK
            val currentTheme = try {
                spontaneousFeatures.getCurrentTheme()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting current theme", e)
                null
            }
            
            if (currentTheme != null) {
                tvCurrentTheme.text = getString(R.string.active_theme_label, currentTheme)
                tvCurrentTheme.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
            } else {
                tvCurrentTheme.text = getString(R.string.active_theme_label, getString(R.string.default_theme))
                tvCurrentTheme.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            
            // Sahip olunan temaları listele - SAFE
            try {
                loadOwnedThemes()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading owned themes", e)
                tvNoThemes.visibility = android.view.View.VISIBLE
                tvNoThemes.text = getString(R.string.theme_loading_error_message)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading theme management", e)
            // UI'yi güvenli duruma getir
            tvCurrentTheme.text = getString(R.string.active_theme_label, getString(R.string.default_theme))
            tvCurrentTheme.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvNoThemes.visibility = android.view.View.VISIBLE
            tvNoThemes.text = getString(R.string.theme_system_loading_error)
        }
    }
    
    /**
     * Sahip olunan temaları listele - CRASH-SAFE
     */
    private fun loadOwnedThemes() {
        try {
            themesList.removeAllViews()
            
            // SharedPreferences'dan sahip olunan temaları al - SAFE
            val prefs = try {
                getSharedPreferences("purchased_themes", MODE_PRIVATE)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error accessing purchased_themes prefs", e)
                return
            }
            
            val ownedThemes = mutableListOf<String>()
            
            // Tema listesi
            val allThemes = listOf("Galaksi", "Gün Batımı", "Okyanus", "Ateş", "Aurora")
            
            for (theme in allThemes) {
                try {
                    if (prefs.getBoolean("theme_$theme", false)) {
                        ownedThemes.add(theme)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error checking theme ownership: $theme", e)
                }
            }
            
            if (ownedThemes.isEmpty()) {
                tvNoThemes.visibility = android.view.View.VISIBLE
                tvNoThemes.text = getString(R.string.no_premium_themes_purchased)
            } else {
                tvNoThemes.visibility = android.view.View.GONE
                
                // Her tema için buton oluştur - SAFE
                for (theme in ownedThemes) {
                    try {
                        val themeButton = Button(this)
                        themeButton.text = "🎨 $theme"
                        themeButton.setBackgroundResource(R.drawable.secondary_button_background)
                        themeButton.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
                        themeButton.textSize = 12f
                        themeButton.setPadding(24, 12, 24, 12)
                        
                        val layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        layoutParams.setMargins(0, 8, 0, 0)
                        themeButton.layoutParams = layoutParams
                        
                        // Aktif tema ise farklı renk - SAFE
                        try {
                            val currentTheme = spontaneousFeatures.getCurrentTheme()
                            if (currentTheme == theme) {
                                themeButton.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                                themeButton.text = "✅ $theme (Aktif)"
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error checking current theme", e)
                        }
                        
                        themeButton.setOnClickListener {
                            changeTheme(theme)
                        }
                        
                        themesList.addView(themeButton)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error creating theme button: $theme", e)
                    }
                }
                
                // Varsayılan tema butonu - SAFE
                try {
                    val defaultButton = Button(this)
                    defaultButton.text = getString(R.string.default_theme_button)
                    defaultButton.setBackgroundResource(R.drawable.secondary_button_background)
                    defaultButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    defaultButton.textSize = 12f
                    defaultButton.setPadding(24, 12, 24, 12)
                    
                    val defaultLayoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    defaultLayoutParams.setMargins(0, 8, 0, 0)
                    defaultButton.layoutParams = defaultLayoutParams
                    
                    // Varsayılan tema aktif ise - SAFE
                    try {
                        val currentTheme = spontaneousFeatures.getCurrentTheme()
                        if (currentTheme == null) {
                            defaultButton.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                            defaultButton.text = getString(R.string.default_theme_active)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error checking default theme state", e)
                    }
                    
                    defaultButton.setOnClickListener {
                        changeTheme(null) // Varsayılan tema
                    }
                    
                    themesList.addView(defaultButton)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error creating default theme button", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in loadOwnedThemes", e)
            // UI'yi güvenli duruma getir
            tvNoThemes.visibility = android.view.View.VISIBLE
            tvNoThemes.text = "Tema listesi yüklenemedi"
        }
    }
    
    /**
     * Tema değiştir
     */
    private fun changeTheme(themeName: String?) {
        Log.d(TAG, "🎨 Changing theme to: $themeName")
        
        try {
            if (themeName == null) {
                // Varsayılan temaya dön
                val prefs = getSharedPreferences("spontaneous_features_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("premium_theme_active", false)
                    .putString("premium_theme_name", "")
                    .apply()
                
                Toast.makeText(this, getString(R.string.default_theme_applied), Toast.LENGTH_SHORT).show()
            } else {
                // Premium temayı aktif et
                val prefs = getSharedPreferences("spontaneous_features_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("premium_theme_active", true)
                    .putString("premium_theme_name", themeName)
                    .apply()
                
                Toast.makeText(this, getString(R.string.theme_applied, themeName), Toast.LENGTH_SHORT).show()
            }
            
            // UI'yi güncelle
            loadThemeManagement()
            
            // Tema değişikliğini tüm aktivitelere bildir
            recreate() // Activity'yi yeniden başlat
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error changing theme", e)
            Toast.makeText(this, getString(R.string.theme_could_not_be_changed), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Radar efekt yönetimi bölümünü yükle - YENİ (CRASH-SAFE)
     */
    private fun loadRadarEffectManagement() {
        Log.d(TAG, "🌀 Loading radar effect management")
        
        try {
            // Aktif radar efektini göster - SAFE CHECK
            val currentEffect = try {
                spontaneousFeatures.getCurrentRadarEffect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting current radar effect", e)
                null
            }
            
            if (currentEffect != null) {
                tvCurrentRadarEffect.text = getString(R.string.active_radar_effect_label, currentEffect)
                tvCurrentRadarEffect.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
            } else {
                tvCurrentRadarEffect.text = getString(R.string.active_radar_effect_label, getString(R.string.default_theme))
                tvCurrentRadarEffect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            
            // Sahip olunan radar efektlerini listele - SAFE
            try {
                loadOwnedRadarEffects()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading owned radar effects", e)
                tvNoRadarEffects.visibility = android.view.View.VISIBLE
                tvNoRadarEffects.text = "Radar efekt yükleme hatası"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading radar effect management", e)
            // UI'yi güvenli duruma getir
            tvCurrentRadarEffect.text = getString(R.string.active_radar_effect_label, getString(R.string.default_theme))
            tvCurrentRadarEffect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvNoRadarEffects.visibility = android.view.View.VISIBLE
            tvNoRadarEffects.text = "Radar efekt sistemi yüklenemedi"
        }
    }
    
    /**
     * Sahip olunan radar efektlerini listele
     */
    private fun loadOwnedRadarEffects() {
        radarEffectsList.removeAllViews()
        
        // SharedPreferences'dan sahip olunan radar efektlerini al
        val prefs = getSharedPreferences("purchased_radar_effects", MODE_PRIVATE)
        val ownedEffects = mutableListOf<String>()
        
        // Radar efekt listesi
        val allEffects = listOf("Nabız", "Dalga", "Spiral", "Şimşek", "Gökkuşağı")
        
        for (effect in allEffects) {
            if (prefs.getBoolean("effect_$effect", false)) {
                ownedEffects.add(effect)
            }
        }
        
        if (ownedEffects.isEmpty()) {
            tvNoRadarEffects.visibility = android.view.View.VISIBLE
            tvNoRadarEffects.text = getString(R.string.no_premium_radar_effects_purchased)
        } else {
            tvNoRadarEffects.visibility = android.view.View.GONE
            
            // Her radar efekt için buton oluştur
            for (effect in ownedEffects) {
                val effectButton = Button(this)
                effectButton.text = "🌀 $effect"
                effectButton.setBackgroundResource(R.drawable.secondary_button_background)
                effectButton.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
                effectButton.textSize = 12f
                effectButton.setPadding(24, 12, 24, 12)
                
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams.setMargins(0, 8, 0, 0)
                effectButton.layoutParams = layoutParams
                
                // Aktif efekt ise farklı renk
                val currentEffect = spontaneousFeatures.getCurrentRadarEffect()
                if (currentEffect == effect) {
                    effectButton.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                    effectButton.text = "✅ $effect (Aktif)"
                }
                
                effectButton.setOnClickListener {
                    changeRadarEffect(effect)
                }
                
                radarEffectsList.addView(effectButton)
            }
            
            // Varsayılan radar efekt butonu
            val defaultButton = Button(this)
            defaultButton.text = getString(R.string.default_effect_button)
            defaultButton.setBackgroundResource(R.drawable.secondary_button_background)
            defaultButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            defaultButton.textSize = 12f
            defaultButton.setPadding(24, 12, 24, 12)
            
            val defaultLayoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            defaultLayoutParams.setMargins(0, 8, 0, 0)
            defaultButton.layoutParams = defaultLayoutParams
            
            // Varsayılan efekt aktif ise
            val currentEffect = spontaneousFeatures.getCurrentRadarEffect()
            if (currentEffect == null) {
                defaultButton.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                defaultButton.text = getString(R.string.default_effect_active)
            }
            
            defaultButton.setOnClickListener {
                changeRadarEffect(null) // Varsayılan efekt
            }
            
            radarEffectsList.addView(defaultButton)
        }
    }
    
    /**
     * Radar efekt değiştir
     */
    private fun changeRadarEffect(effectName: String?) {
        Log.d(TAG, "🌀 Changing radar effect to: $effectName")
        
        try {
            if (effectName == null) {
                // Varsayılan efekte dön
                val prefs = getSharedPreferences("spontaneous_features_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("radar_effect_active", false)
                    .putString("radar_effect_name", "")
                    .apply()
                
                Toast.makeText(this, getString(R.string.default_radar_effect_applied), Toast.LENGTH_SHORT).show()
            } else {
                // Premium radar efektini aktif et
                val prefs = getSharedPreferences("spontaneous_features_prefs", MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("radar_effect_active", true)
                    .putString("radar_effect_name", effectName)
                    .apply()
                
                Toast.makeText(this, getString(R.string.radar_effect_applied, effectName), Toast.LENGTH_SHORT).show()
            }
            
            // UI'yi güncelle
            loadRadarEffectManagement()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error changing radar effect", e)
            Toast.makeText(this, getString(R.string.radar_effect_could_not_be_changed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateAdvertisingStatus() {
        val isAdvertising = AdvertisingController.isAdvertising()
        val lastError = AdvertisingController.getLastError()
        val visibilityEnabled = userPrefs.getVisibilityEnabled()
        
        val statusText = when {
            !visibilityEnabled -> getString(R.string.visibility_off)
            isAdvertising -> getString(R.string.visibility_on)
            lastError != null -> getString(R.string.visibility_off_with_error, lastError)
            else -> getString(R.string.visibility_off)
        }
        
        tvAdvertisingStatus.text = statusText
        Log.d(TAG, "📊 Updated advertising status: $statusText")
    }
    
    override fun onResume() {
        super.onResume()
        loadProfile()
        
        // ENHANCED: Force premium features check on resume
        Log.d(TAG, "📱 RESUME: Forcing premium features check...")
        
        // Test StateFlow connections manually
        handler.postDelayed({
            Log.d(TAG, "🔍 MANUAL_CHECK: Testing premium feature states...")
            
            val auraBoostActive = spontaneousFeatures.auraBoostActive.value
            val instantPulseActive = spontaneousFeatures.instantPulseActive.value
            val currentMood = spontaneousFeatures.currentMood.value
            val goldenHourActive = spontaneousFeatures.goldenHourActive.value
            
            Log.d(TAG, "🔍 MANUAL_CHECK: AuraBoost=$auraBoostActive")
            Log.d(TAG, "🔍 MANUAL_CHECK: InstantPulse=$instantPulseActive")
            Log.d(TAG, "🔍 MANUAL_CHECK: Mood=$currentMood")
            Log.d(TAG, "🔍 MANUAL_CHECK: GoldenHour=$goldenHourActive")
            
            // Force UI update based on current states
            runOnUiThread {
                // Aura Boost
                if (auraBoostActive) {
                    tvActiveAuraBoost.visibility = android.view.View.VISIBLE
                    tvActiveAuraBoost.text = "🔥 Aura Boost - Aktif"
                    Log.d(TAG, "🔥 MANUAL: Aura Boost set to VISIBLE")
                } else {
                    tvActiveAuraBoost.visibility = android.view.View.GONE
                    Log.d(TAG, "🔥 MANUAL: Aura Boost set to GONE")
                }
                
                // Instant Pulse
                if (instantPulseActive) {
                    tvActiveInstantPulse.visibility = android.view.View.VISIBLE
                    tvActiveInstantPulse.text = "⚡ Instant Pulse - Aktif"
                    Log.d(TAG, "⚡ MANUAL: Instant Pulse set to VISIBLE")
                } else {
                    tvActiveInstantPulse.visibility = android.view.View.GONE
                    Log.d(TAG, "⚡ MANUAL: Instant Pulse set to GONE")
                }
                
                // Mood
                if (currentMood != null) {
                    tvActiveMood.visibility = android.view.View.VISIBLE
                    tvActiveMood.text = "${currentMood.type.emoji} Mood Signal - ${currentMood.type.description}"
                    Log.d(TAG, "😊 MANUAL: Mood set to VISIBLE - ${currentMood.type.description}")
                } else {
                    tvActiveMood.visibility = android.view.View.GONE
                    Log.d(TAG, "😊 MANUAL: Mood set to GONE")
                }
                
                // Golden Hour
                if (goldenHourActive) {
                    tvActiveGoldenHour.visibility = android.view.View.VISIBLE
                    tvActiveGoldenHour.text = "🌅 Golden Hour - Aktif"
                    Log.d(TAG, "🌅 MANUAL: Golden Hour set to VISIBLE")
                } else {
                    tvActiveGoldenHour.visibility = android.view.View.GONE
                    Log.d(TAG, "🌅 MANUAL: Golden Hour set to GONE")
                }
                
                updateActiveFeaturesVisibility()
                Log.d(TAG, "✅ MANUAL: All premium features UI updated manually")
            }
        }, 1000)
        
        // AuraCore handles advertising lifecycle - no manual restart needed
        Log.d(TAG, "📡 onResume - AuraCore manages advertising lifecycle")
    }
    
    override fun onStop() {
        super.onStop()
        
        // AuraCore handles advertising lifecycle - no manual stop needed
        Log.d(TAG, "📡 onStop - AuraCore manages advertising lifecycle")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AdvertisingController.setStateListener(null)
        // Remove BLE listener to prevent memory leaks
        BleEngineManager.removeListener(this)
    }
    
    // AdvertisingController.StateListener implementation
    override fun onAdvertisingStateChanged(isAdvertising: Boolean, error: String?) {
        runOnUiThread {
            Log.d(TAG, "📡 Advertising state changed: isAdvertising=$isAdvertising, error=$error")
            updateAdvertisingStatus()
        }
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
            Log.d(TAG, "✅ Match accepted from: $senderHash")
            // Could show a toast or navigate to matches
        }
    }
    
    override fun onMatchRejected(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "❌ Match rejected from: $senderHash")
        }
    }
    
    override fun onChatMessage(senderHash: String, message: String) {
        runOnUiThread {
            Log.d(TAG, "💬 Chat message from: $senderHash")
        }
    }
    
    override fun onPhotoReceived(senderHash: String, photoBase64: String) {
        // Not relevant for profile activity
    }
    
    override fun onPhotoRequested(senderHash: String) {
        // Not relevant for profile activity
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
                    // Could show a toast or update UI if needed
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
                    // Could show a toast or update UI if needed
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling block in UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling block", e)
        }
    }
    
    private fun showMatchRequestDialog(senderHash: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.match_request_title))
            .setMessage(getString(R.string.match_request_message))
            .setPositiveButton(getString(R.string.accept)) { _, _ ->
                BleEngineManager.getInstance()?.enqueueMatchResponse(true, senderHash)
                Log.d(TAG, "✅ Match request accepted for: $senderHash")
            }
            .setNegativeButton(getString(R.string.reject)) { _, _ ->
                BleEngineManager.getInstance()?.enqueueMatchResponse(false, senderHash)
                Log.d(TAG, "❌ Match request rejected for: $senderHash")
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showChangeUserNameDialog() {
        val currentName = userPrefs.getUserName()
        val editText = android.widget.EditText(this)
        editText.setText(currentName)
        editText.hint = getString(R.string.username_hint)
        editText.maxLines = 1
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.change_username_title))
            .setMessage(getString(R.string.change_username_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName.length <= 20) {
                    userPrefs.setUserName(newName)
                    loadProfile() // Refresh UI
                    
                    // CRITICAL: Restart advertising with new name
                    if (userPrefs.getVisibilityEnabled()) {
                        Log.d(TAG, "🔄 Restarting advertising with new user name: $newName")
                        
                        // Force stop and restart advertising
                        BleEngineManager.stopAdvertising()
                        
                        // Also update the current user in BLE Engine
                        val userId = userPrefs.getUserId()
                        BleEngineManager.setCurrentUser(userId)
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            BleEngineManager.startAdvertising()
                            BleEngineManager.ensureBackgroundScanning()
                            Log.d(TAG, "✅ Advertising restarted with new name AND background scanning ensured")
                        }, 1000) // Longer delay to ensure complete restart
                    }
                    
                    showTopToast(getString(R.string.username_updated_toast))
                } else {
                    showTopToast(getString(R.string.invalid_username_toast))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        imagePickerLauncher.launch(intent)
    }
    
    private fun handleImageSelection(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (bitmap != null) {
                // Resize bitmap to reasonable size (200x200)
                val resizedBitmap = ProfilePhotoManager.resizeBitmap(bitmap, 200, 200)
                
                // Save using ProfilePhotoManager
                val success = ProfilePhotoManager.saveCurrentUserPhoto(this, resizedBitmap)
                if (success) {
                    loadProfilePhoto()
                    showTopToast(getString(R.string.profile_photo_updated))
                } else {
                    showTopToast(getString(R.string.photo_could_not_be_saved))
                }
            } else {
                showTopToast(getString(R.string.photo_could_not_be_loaded))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling image selection", e)
            showTopToast(getString(R.string.error_processing_photo))
        }
    }
    
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    private fun saveProfilePhoto(bitmap: Bitmap): Boolean {
        return try {
            val file = File(filesDir, "profile_photo.jpg")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.close()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error saving profile photo", e)
            false
        }
    }
    
    private fun loadProfilePhoto() {
        Log.d(TAG, "🖼️ Loading profile photo...")
        val bitmap = ProfilePhotoManager.loadCurrentUserPhoto(this)
        if (bitmap != null) {
            Log.d(TAG, "✅ Profile photo loaded successfully")
            ivProfilePhoto.setImageBitmap(bitmap)
            ivProfilePhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            Log.d(TAG, "❌ No profile photo found, using default")
            setDefaultProfilePhoto()
        }
    }
    
    private fun setDefaultProfilePhoto() {
        // Set default person icon
        ivProfilePhoto.setImageResource(R.drawable.ic_person)
        ivProfilePhoto.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    
    private fun showSoundTestDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.aura_notification_sounds))
            .setMessage(getString(R.string.which_notification_sound_test))
            .setPositiveButton(getString(R.string.match_sound)) { _, _ ->
                val soundEngine = AuraSoundEngine(this)
                soundEngine.playMatchNotification()
                showTopToast(getString(R.string.match_notification_playing))
            }
            .setNeutralButton(getString(R.string.message_sound)) { _, _ ->
                val soundEngine = AuraSoundEngine(this)
                soundEngine.playMessageNotification()
                showTopToast(getString(R.string.message_notification_playing))
            }
            .setNegativeButton(getString(R.string.test_sound)) { _, _ ->
                val soundEngine = AuraSoundEngine(this)
                soundEngine.playTestNotification()
                showTopToast(getString(R.string.test_notification_playing))
            }
            .create()
        
        dialog.show()
    }
    
    private fun showVoiceRecordingDialog() {
        // Check if Voice Intro feature is purchased
        val crystalManager = CrystalManager(this)
        val currentBalance = crystalManager.getCrystalBalance()
        
        // Check if already active
        if (spontaneousFeatures.voiceIntroActive.value) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.voice_intro_title))
                .setMessage(getString(R.string.voice_intro_already_active))
                .setPositiveButton(getString(R.string.make_new_recording)) { _, _ ->
                    startVoiceRecording()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else if (currentBalance >= CrystalManager.VOICE_INTRO_COST) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.voice_intro_title))
                .setMessage(getString(R.string.voice_intro_cost_info, CrystalManager.VOICE_INTRO_COST))
                .setPositiveButton(getString(R.string.start_recording)) { _, _ ->
                    startVoiceRecording()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            val needed = CrystalManager.VOICE_INTRO_COST - currentBalance
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.voice_intro_title))
                .setMessage(getString(R.string.voice_intro_insufficient_balance, CrystalManager.VOICE_INTRO_COST, currentBalance, needed))
                .setPositiveButton(getString(R.string.aura_store_button)) { _, _ ->
                    val intent = Intent(this, CrystalStoreActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }
    
    private fun startVoiceRecording() {
        // For now, simulate voice recording with a demo implementation
        // In a real app, you would use MediaRecorder to record audio
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_recording_title))
            .setMessage(getString(R.string.voice_recording_demo_message))
            .setPositiveButton(getString(R.string.make_demo_recording)) { _, _ ->
                // Simulate recording process
                showTopToast(getString(R.string.demo_voice_recording))
                
                // Simulate 3 second recording
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // Create demo voice intro
                    val demoAudioBase64 = "demo_voice_intro_base64_data_here"
                    val success = spontaneousFeatures.setVoiceIntro(demoAudioBase64, 30)
                    
                    if (success) {
                        showTopToast(getString(R.string.voice_intro_recorded_success))
                        
                        // Update UI
                        findViewById<TextView>(R.id.tvVoiceIntroStatus)?.text = getString(R.string.voice_intro_status_active)
                    } else {
                        showTopToast(getString(R.string.voice_intro_recording_failed))
                    }
                }, 3000)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * Profil sayfasında tema uygulama
     */
    private fun applyThemeToProfile(themeName: String?) {
        if (themeName != null) {
            when (themeName) {
                "Galaksi" -> {
                    window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
                    findViewById<TextView>(R.id.tvHeader)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_purple))
                    showTopToast(getString(R.string.galaxy_theme_active_profile), Toast.LENGTH_SHORT)
                }
                "Gün Batımı" -> {
                    window.statusBarColor = ContextCompat.getColor(this, R.color.neon_pink)
                    findViewById<TextView>(R.id.tvHeader)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                    showTopToast(getString(R.string.sunset_theme_active_profile), Toast.LENGTH_SHORT)
                }
                "Okyanus" -> {
                    window.statusBarColor = ContextCompat.getColor(this, R.color.neon_blue)
                    findViewById<TextView>(R.id.tvHeader)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
                    showTopToast(getString(R.string.ocean_theme_active_profile), Toast.LENGTH_SHORT)
                }
                "Ateş" -> {
                    window.statusBarColor = ContextCompat.getColor(this, android.R.color.holo_red_dark)
                    findViewById<TextView>(R.id.tvHeader)?.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                    showTopToast(getString(R.string.fire_theme_active_profile), Toast.LENGTH_SHORT)
                }
                "Aurora" -> {
                    window.statusBarColor = ContextCompat.getColor(this, R.color.neon_blue)
                    startProfileAuroraEffect()
                    showTopToast(getString(R.string.aurora_theme_active_profile), Toast.LENGTH_SHORT)
                }
            }
        } else {
            // Varsayılan tema
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
            findViewById<TextView>(R.id.tvHeader)?.setTextColor(ContextCompat.getColor(this, R.color.neon_blue))
        }
    }
    
    /**
     * Profil sayfasında Aurora efekti
     */
    private fun startProfileAuroraEffect() {
        val colors = arrayOf(
            android.R.color.holo_blue_light,
            android.R.color.holo_purple,
            android.R.color.holo_green_light,
            R.color.neon_pink
        )
        var colorIndex = 0
        
        val auroraRunnable = object : Runnable {
            override fun run() {
                if (spontaneousFeatures.premiumThemeActive.value && 
                    spontaneousFeatures.getCurrentTheme() == "Aurora") {
                    
                    val color = ContextCompat.getColor(this@ProfileActivity, colors[colorIndex])
                    findViewById<TextView>(R.id.tvHeader)?.setTextColor(color)
                    
                    colorIndex = (colorIndex + 1) % colors.size
                    handler.postDelayed(this, 2000)
                }
            }
        }
        handler.post(auroraRunnable)
    }
    
    /**
     * Show language selection dialog
     */
    private fun showLanguageSelectionDialog() {
        val languages = LanguageManager.SUPPORTED_LANGUAGES
        val languageNames = languages.map { "${it.flag} ${it.nativeName}" }.toTypedArray()
        val currentLanguage = LanguageManager.getSavedLanguage(this)
        val currentIndex = languages.indexOfFirst { it.code == currentLanguage }
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language))
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                val selectedLanguage = languages[which]
                
                // Save language preference
                LanguageManager.saveLanguage(this, selectedLanguage.code)
                
                // Update UI immediately
                val newLanguageDisplay = "${selectedLanguage.flag} ${selectedLanguage.nativeName}"
                tvCurrentLanguage.text = getString(R.string.current_language, newLanguageDisplay)
                
                // Show confirmation
                showTopToast(getString(R.string.language_changed))
                
                dialog.dismiss()
                
                // Show restart dialog
                showRestartDialog()
            }
            .setNegativeButton("İptal", null)
            .show()
    }
    
    /**
     * Show restart confirmation dialog
     */
    private fun showRestartDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.language_change_title))
            .setMessage(getString(R.string.restart_required))
            .setPositiveButton(getString(R.string.restart_now)) { _, _ ->
                // Force recreate all activities by restarting the app completely
                val intent = Intent(this, SplashActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finishAffinity()
                
                // Kill the process to ensure complete restart
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton(getString(R.string.later)) { _, _ ->
                // Still recreate this activity to show immediate changes
                recreate()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Radar efekt bilgisi göster
     */
    private fun showRadarEffectInfo(effectName: String?) {
        if (effectName != null) {
            val description = when (effectName) {
                "Nabız" -> getString(R.string.heartbeat_radar_description)
                "Dalga" -> getString(R.string.wave_radar_description)
                "Spiral" -> getString(R.string.spiral_radar_description)
                "Şimşek" -> getString(R.string.lightning_radar_description)
                "Gökkuşağı" -> getString(R.string.rainbow_radar_description)
                else -> getString(R.string.special_radar_effect)
            }
            
            // Radar efekt açıklamasını göster
            handler.postDelayed({
                showTopToast("🌀 $effectName: $description", Toast.LENGTH_LONG)
            }, 1000)
        }
    }
}