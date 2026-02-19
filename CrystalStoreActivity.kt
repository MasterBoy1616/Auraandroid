package com.aura.link

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

class CrystalStoreActivity : BaseThemedActivity() {
    
    private lateinit var crystalManager: CrystalManager
    private lateinit var spontaneousFeatures: SpontaneousFeatures
    private lateinit var featuresAdapter: CrystalFeaturesAdapter
    private lateinit var packsAdapter: CrystalPacksAdapter
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_crystal_store)
            
            crystalManager = CrystalManager(this)
            spontaneousFeatures = SpontaneousFeatures.getInstance(this)
            
            setupUI()
            setupRecyclerViews()
            observeCrystalBalance()
            loadStoreItems()
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in onCreate", e)
            Toast.makeText(this, getString(R.string.crystal_store_loading_error, e.message), Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    private fun setupUI() {
        try {
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.setNavigationOnClickListener { finish() }
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.let { toolbar ->
                toolbar.title = getString(R.string.crystal_store_title)
            }
            
            // Header
            findViewById<android.widget.TextView>(R.id.storeTitle)?.text = getString(R.string.crystal_points_title)
            findViewById<android.widget.TextView>(R.id.storeSubtitle)?.text = getString(R.string.crystal_points_subtitle)
            
            // Tabs
            findViewById<android.widget.Button>(R.id.tabFeatures)?.setOnClickListener { showFeaturesTab() }
            findViewById<android.widget.Button>(R.id.tabPacks)?.setOnClickListener { showPacksTab() }
            findViewById<android.widget.Button>(R.id.tabTasks)?.setOnClickListener { showTasksTab() }
            
            // Default tab
            showFeaturesTab()
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in setupUI", e)
            throw e
        }
    }
    
    private fun setupRecyclerViews() {
        try {
            // Features RecyclerView
            featuresAdapter = CrystalFeaturesAdapter { feature ->
                // BASIT TEST: Önce sadece Toast göster
                Toast.makeText(this, "🔥 TIKLANDI: ${feature.title}", Toast.LENGTH_LONG).show()
                android.util.Log.d("CrystalStore", "🔥 ADAPTER CLICK: ${feature.id}")
                
                // Sonra purchaseFeature çağır
                purchaseFeature(feature)
            }
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewFeatures)?.apply {
                layoutManager = LinearLayoutManager(this@CrystalStoreActivity)
                adapter = featuresAdapter
            }
            
            // Packs RecyclerView
            packsAdapter = CrystalPacksAdapter { pack ->
                purchasePack(pack)
            }
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPacks)?.apply {
                layoutManager = GridLayoutManager(this@CrystalStoreActivity, 2)
                adapter = packsAdapter
            }
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in setupRecyclerViews", e)
            throw e
        }
    }
    
    private fun observeCrystalBalance() {
        try {
            lifecycleScope.launch {
                crystalManager.crystalBalance.collect { balance ->
                    findViewById<android.widget.TextView>(R.id.crystalBalanceText)?.text = "$balance"
                    
                    // Balance animasyonu
                    findViewById<android.widget.TextView>(R.id.crystalBalanceText)?.let { crystalBalanceView ->
                        crystalBalanceView.animate()
                            .scaleX(1.2f)
                            .scaleY(1.2f)
                            .setDuration(200)
                            .withEndAction {
                                crystalBalanceView.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(200)
                                    .start()
                            }
                            .start()
                    }
                }
            }
            
            lifecycleScope.launch {
                crystalManager.dailyTasks.collect { tasks ->
                    updateDailyTasksUI(tasks)
                }
            }
            
            lifecycleScope.launch {
                crystalManager.achievements.collect { achievements ->
                    updateAchievementsUI(achievements)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in observeCrystalBalance", e)
        }
    }
    
    private fun loadStoreItems() {
        // Premium özellikler
        val features = listOf(
            CrystalFeature(
                id = "aura_boost",
                title = "🔥 Aura Boost",
                description = getString(R.string.aura_boost_description),
                cost = CrystalManager.AURA_BOOST_COST,
                duration = getString(R.string.duration_30_minutes),
                category = getString(R.string.category_instant_boost),
                isPopular = true
            ),
            CrystalFeature(
                id = "instant_pulse",
                title = "⚡ Instant Pulse",
                description = getString(R.string.instant_pulse_description),
                cost = CrystalManager.INSTANT_PULSE_COST,
                duration = getString(R.string.duration_5_minutes),
                category = getString(R.string.category_instant_boost),
                isPopular = false
            ),
            CrystalFeature(
                id = "mood_signal",
                title = "☕ Mood Signal",
                description = getString(R.string.mood_signal_description),
                cost = CrystalManager.MOOD_SIGNAL_COST,
                duration = getString(R.string.duration_30_minutes),
                category = getString(R.string.category_spontaneous_expression),
                isPopular = false
            ),
            CrystalFeature(
                id = "quick_reaction",
                title = "😊 Quick Reaction",
                description = getString(R.string.quick_reaction_description),
                cost = CrystalManager.QUICK_REACTION_COST,
                duration = getString(R.string.duration_1_use),
                category = getString(R.string.category_spontaneous_expression),
                isPopular = false
            ),
            CrystalFeature(
                id = "golden_hour",
                title = "🌅 Golden Hour",
                description = getString(R.string.golden_hour_description),
                cost = CrystalManager.GOLDEN_HOUR_COST,
                duration = getString(R.string.duration_1_day),
                category = getString(R.string.category_time_based),
                isPopular = true
            ),
            CrystalFeature(
                id = "flash_event",
                title = "🎪 Flash Event",
                description = getString(R.string.flash_event_description),
                cost = CrystalManager.FLASH_EVENT_COST,
                duration = getString(R.string.duration_1_event),
                category = getString(R.string.category_spontaneous_event),
                isPopular = false
            ),
            CrystalFeature(
                id = "proximity_alert",
                title = "📍 Proximity Alert",
                description = getString(R.string.proximity_alert_description),
                cost = CrystalManager.PROXIMITY_ALERT_COST,
                duration = getString(R.string.duration_1_month),
                category = getString(R.string.category_smart_matching),
                isPopular = false
            ),
            CrystalFeature(
                id = "voice_intro",
                title = "🎤 Voice Intro",
                description = getString(R.string.voice_intro_description),
                cost = CrystalManager.VOICE_INTRO_COST,
                duration = getString(R.string.duration_7_days),
                category = getString(R.string.category_personal_expression),
                isPopular = false
            ),
            CrystalFeature(
                id = "premium_theme",
                title = "🎨 Premium Tema",
                description = getString(R.string.premium_theme_description),
                cost = CrystalManager.THEME_COST,
                duration = getString(R.string.duration_permanent),
                category = getString(R.string.category_visual_customization),
                isPopular = true
            ),
            CrystalFeature(
                id = "radar_effect",
                title = getString(R.string.radar_effect),
                description = getString(R.string.radar_effect_description),
                cost = CrystalManager.RADAR_EFFECT_COST,
                duration = getString(R.string.duration_permanent),
                category = getString(R.string.category_visual_customization),
                isPopular = false
            )
        )
        
        featuresAdapter.submitList(features)
        
        // Aura paketleri - UPDATED WITH NEW USD PRICING
        val packs = listOf(
            CrystalPack(
                id = "starter",
                title = getString(R.string.coin_package_starter),
                crystals = 100,
                bonus = 0,
                price = getString(R.string.coin_price_starter),
                isPopular = false
            ),
            CrystalPack(
                id = "boost",
                title = getString(R.string.coin_package_boost),
                crystals = 250,
                bonus = 25,
                price = getString(R.string.coin_price_boost),
                isPopular = false
            ),
            CrystalPack(
                id = "popular",
                title = getString(R.string.coin_package_popular),
                crystals = 500,
                bonus = 75,
                price = getString(R.string.coin_price_popular),
                isPopular = true
            ),
            CrystalPack(
                id = "power",
                title = getString(R.string.coin_package_power),
                crystals = 1000,
                bonus = 200,
                price = getString(R.string.coin_price_power),
                isPopular = false
            ),
            CrystalPack(
                id = "pro",
                title = getString(R.string.coin_package_pro),
                crystals = 2500,
                bonus = 500,
                price = getString(R.string.coin_price_pro),
                isPopular = true
            ),
            CrystalPack(
                id = "elite",
                title = getString(R.string.coin_package_elite),
                crystals = 5000,
                bonus = 1000,
                price = getString(R.string.coin_price_elite),
                isPopular = false
            )
        )
        
        packsAdapter.submitList(packs)
    }
    
    private fun purchaseFeature(feature: CrystalFeature) {
        Log.d("CrystalStore", "🛒 purchaseFeature called for: ${feature.id}")
        Toast.makeText(this, getString(R.string.purchasing_feature, feature.title), Toast.LENGTH_SHORT).show()
        
        val currentBalance = crystalManager.getCrystalBalance()
        Log.d("CrystalStore", "💰 Current balance: $currentBalance, Feature cost: ${feature.cost}")
        
        if (currentBalance >= feature.cost) {
            Log.d("CrystalStore", "✅ Sufficient balance, processing feature: ${feature.id}")
            when (feature.id) {
                "aura_boost" -> {
                    if (crystalManager.purchaseAuraBoost()) {
                        Log.d("CrystalStore", "🔥 Aura Boost purchased successfully")
                        if (spontaneousFeatures.activateAuraBoost()) {
                            Log.d("CrystalStore", "🔥 Aura Boost activated successfully")
                            showSuccessMessage(getString(R.string.aura_boost_feedback))
                            
                            // Force check the state
                            handler.postDelayed({
                                val isActive = spontaneousFeatures.auraBoostActive.value
                                Log.d("CrystalStore", "🔥 Aura Boost state after activation: $isActive")
                            }, 1000)
                        } else {
                            Log.e("CrystalStore", "❌ Aura Boost activation failed")
                        }
                    } else {
                        Log.e("CrystalStore", "❌ Aura Boost purchase failed")
                    }
                }
                "instant_pulse" -> {
                    if (crystalManager.purchaseInstantPulse()) {
                        Log.d("CrystalStore", "⚡ Instant Pulse purchased successfully")
                        if (spontaneousFeatures.activateInstantPulse()) {
                            Log.d("CrystalStore", "⚡ Instant Pulse activated successfully")
                            showSuccessMessage(getString(R.string.instant_pulse_feedback))
                            
                            // Force check the state
                            handler.postDelayed({
                                val isActive = spontaneousFeatures.instantPulseActive.value
                                Log.d("CrystalStore", "⚡ Instant Pulse state after activation: $isActive")
                            }, 1000)
                        } else {
                            Log.e("CrystalStore", "❌ Instant Pulse activation failed")
                        }
                    } else {
                        Log.e("CrystalStore", "❌ Instant Pulse purchase failed")
                    }
                }
                "mood_signal" -> {
                    if (crystalManager.purchaseMoodSignal()) {
                        showMoodSelectionDialog()
                    }
                }
                "quick_reaction" -> {
                    Log.d("CrystalStore", "🎯 Processing Quick Reaction purchase")
                    Toast.makeText(this, "🎯 Quick Reaction işleniyor...", Toast.LENGTH_SHORT).show()
                    
                    // DOĞRUDAN DIALOG ÇAĞIR - TEST
                    try {
                        showQuickReactionPackDialog()
                    } catch (e: Exception) {
                        Log.e("CrystalStore", "❌ Quick Reaction dialog error", e)
                        Toast.makeText(this, "❌ Dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                "golden_hour" -> {
                    if (crystalManager.purchaseGoldenHour()) {
                        if (spontaneousFeatures.activateGoldenHour()) {
                            showSuccessMessage(getString(R.string.golden_hour_active_feedback))
                        } else {
                            showErrorMessage(getString(R.string.golden_hour_time_restriction))
                        }
                    }
                }
                "flash_event" -> {
                    if (crystalManager.purchaseFlashEvent()) {
                        showFlashEventCreationDialog()
                    }
                }
                "proximity_alert" -> {
                    // FIX: Proximity Alert activation bug - check balance first, then purchase and activate
                    val currentBalance = crystalManager.getCrystalBalance()
                    if (currentBalance >= CrystalManager.PROXIMITY_ALERT_COST) {
                        // First try to activate (this will handle the purchase internally)
                        if (spontaneousFeatures.activateProximityAlerts()) {
                            showSuccessMessage(getString(R.string.proximity_alert_active_feedback))
                        } else {
                            showErrorMessage(getString(R.string.proximity_alert_activation_failed))
                        }
                    } else {
                        val needed = CrystalManager.PROXIMITY_ALERT_COST - currentBalance
                        showErrorMessage("Yetersiz aura! $needed aura daha gerekli.")
                    }
                }
                "voice_intro" -> {
                    if (crystalManager.purchaseVoiceIntro()) {
                        showVoiceIntroDialog()
                    }
                }
                "premium_theme" -> {
                    Log.d("CrystalStore", "🎨 Processing Premium Theme purchase")
                    Toast.makeText(this, "🎨 Premium Tema işleniyor...", Toast.LENGTH_SHORT).show()
                    
                    // DOĞRUDAN DIALOG ÇAĞIR - TEST
                    try {
                        showThemeSelectionDialog()
                    } catch (e: Exception) {
                        Log.e("CrystalStore", "❌ Theme dialog error", e)
                        Toast.makeText(this, "❌ Tema dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                "radar_effect" -> {
                    Log.d("CrystalStore", "🌀 Processing Radar Effect purchase")
                    Toast.makeText(this, getString(R.string.radar_effect_processing), Toast.LENGTH_SHORT).show()
                    
                    // DOĞRUDAN DIALOG ÇAĞIR - TEST
                    try {
                        showRadarEffectSelectionDialog()
                    } catch (e: Exception) {
                        Log.e("CrystalStore", "❌ Radar effect dialog error", e)
                        Toast.makeText(this, "❌ Radar efekt dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            val needed = feature.cost - currentBalance
            showErrorMessage("Yetersiz aura! $needed aura daha gerekli.")
        }
    }
    
    private fun purchasePack(pack: CrystalPack) {
        // Google Play Billing ile satın alma (şimdilik demo)
        Toast.makeText(this, getString(R.string.purchasing_feature, pack.title), Toast.LENGTH_SHORT).show()
        
        // Demo amaçlı direkt aura ekle
        val totalCrystals = crystalManager.purchaseCrystalPack(pack.id)
        if (totalCrystals > 0) {
            showSuccessMessage("$totalCrystals aura eklendi!")
        }
    }
    
    private fun showFeaturesTab() {
        try {
            findViewById<android.widget.Button>(R.id.tabFeatures)?.setBackgroundResource(R.drawable.primary_button_background)
            findViewById<android.widget.Button>(R.id.tabPacks)?.setBackgroundResource(R.drawable.secondary_button_background)
            findViewById<android.widget.Button>(R.id.tabTasks)?.setBackgroundResource(R.drawable.secondary_button_background)
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewFeatures)?.visibility = android.view.View.VISIBLE
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPacks)?.visibility = android.view.View.GONE
            findViewById<android.widget.ScrollView>(R.id.tasksLayout)?.visibility = android.view.View.GONE
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in showFeaturesTab", e)
        }
    }
    
    private fun showPacksTab() {
        try {
            findViewById<android.widget.Button>(R.id.tabFeatures)?.setBackgroundResource(R.drawable.secondary_button_background)
            findViewById<android.widget.Button>(R.id.tabPacks)?.setBackgroundResource(R.drawable.primary_button_background)
            findViewById<android.widget.Button>(R.id.tabTasks)?.setBackgroundResource(R.drawable.secondary_button_background)
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewFeatures)?.visibility = android.view.View.GONE
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPacks)?.visibility = android.view.View.VISIBLE
            findViewById<android.widget.ScrollView>(R.id.tasksLayout)?.visibility = android.view.View.GONE
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in showPacksTab", e)
        }
    }
    
    private fun showTasksTab() {
        try {
            findViewById<android.widget.Button>(R.id.tabFeatures)?.setBackgroundResource(R.drawable.secondary_button_background)
            findViewById<android.widget.Button>(R.id.tabPacks)?.setBackgroundResource(R.drawable.secondary_button_background)
            findViewById<android.widget.Button>(R.id.tabTasks)?.setBackgroundResource(R.drawable.primary_button_background)
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewFeatures)?.visibility = android.view.View.GONE
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewPacks)?.visibility = android.view.View.GONE
            findViewById<android.widget.ScrollView>(R.id.tasksLayout)?.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in showTasksTab", e)
        }
    }
    
    private fun updateDailyTasksUI(tasks: CrystalManager.DailyTasks) {
        try {
            findViewById<android.widget.CheckBox>(R.id.taskLogin)?.isChecked = tasks.loginCompleted
            findViewById<android.widget.CheckBox>(R.id.taskFirstMatch)?.isChecked = tasks.firstMatchCompleted
            findViewById<android.widget.CheckBox>(R.id.taskFirstMessage)?.isChecked = tasks.firstMessageCompleted
            findViewById<android.widget.CheckBox>(R.id.taskActive30Min)?.isChecked = tasks.active30MinCompleted
            findViewById<android.widget.CheckBox>(R.id.taskProfileUpdate)?.isChecked = tasks.profileUpdateCompleted
            
            // Progress bar
            val completedTasks = listOf(
                tasks.loginCompleted,
                tasks.firstMatchCompleted,
                tasks.firstMessageCompleted,
                tasks.active30MinCompleted,
                tasks.profileUpdateCompleted
            ).count { it }
            
            findViewById<android.widget.ProgressBar>(R.id.dailyProgress)?.progress = (completedTasks * 100) / 5
            findViewById<android.widget.TextView>(R.id.dailyProgressText)?.text = getString(R.string.daily_progress_text, completedTasks)
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in updateDailyTasksUI", e)
        }
    }
    
    private fun updateAchievementsUI(achievements: Set<CrystalManager.Achievement>) {
        try {
            findViewById<android.widget.TextView>(R.id.achievementsCount)?.text = getString(R.string.achievements_count, achievements.size, CrystalManager.Achievement.values().size)
            
            // Son kazanılan başarımı göster
            if (achievements.isNotEmpty()) {
                val latest = achievements.maxByOrNull { it.reward }
                findViewById<android.widget.TextView>(R.id.latestAchievement)?.let { textView ->
                    textView.text = "${latest?.icon} ${latest?.title}"
                    textView.visibility = android.view.View.VISIBLE
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CrystalStore", "Error in updateAchievementsUI", e)
        }
    }
    
    private fun showMoodSelectionDialog() {
        val moods = SpontaneousFeatures.MoodType.values()
        val moodNames = moods.map { "${it.emoji} ${it.description}" }.toTypedArray()
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_mood_title))
            .setItems(moodNames) { _, which ->
                val selectedMood = moods[which]
                if (spontaneousFeatures.setMoodSignal(selectedMood)) {
                    showSuccessMessage("${selectedMood.emoji} Ruh halin paylaşıldı: ${selectedMood.description}")
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }
    
    private fun showFlashEventCreationDialog() {
        // Basit flash event oluşturma
        val eventTitles = arrayOf(
            getString(R.string.coffee_event),
            getString(R.string.walk_event), 
            getString(R.string.food_event),
            getString(R.string.music_event),
            getString(R.string.game_event),
            getString(R.string.study_event),
            getString(R.string.fun_event)
        )
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.create_flash_event_title))
            .setItems(eventTitles) { _, which ->
                val title = eventTitles[which]
                val event = spontaneousFeatures.createFlashEvent(
                    title = title,
                    description = "Spontane etkinlik - hemen katıl!",
                    location = "Yakın çevre"
                )
                if (event != null) {
                    showSuccessMessage(getString(R.string.flash_event_created, title))
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }
    
    private fun showSuccessMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    private fun showErrorMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    // YENİ DIALOG METODLARİ
    
    private fun showQuickReactionPackDialog() {
        Toast.makeText(this, "🎯 Quick Reaction Dialog Açılıyor", Toast.LENGTH_SHORT).show()
        
        try {
            Log.d("CrystalStore", "🎯 showQuickReactionPackDialog called")
            
            // İlk dialog: Paket boyutu seçimi
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.quick_reaction_pack_title))
                .setMessage(getString(R.string.quick_reaction_pack_message))
                .setPositiveButton(getString(R.string.quick_reaction_5_pack)) { _, _ ->
                    purchaseQuickReactionPack(5, 50)
                }
                .setNeutralButton(getString(R.string.quick_reaction_10_pack)) { _, _ ->
                    purchaseQuickReactionPack(10, 90)
                }
                .setNegativeButton(getString(R.string.more_options)) { _, _ ->
                    showMoreQuickReactionOptions()
                }
                .show()
                
            Log.d("CrystalStore", "✅ Quick Reaction dialog shown")
        } catch (e: Exception) {
            Log.e("CrystalStore", "❌ Error showing Quick Reaction dialog", e)
            Toast.makeText(this, "❌ Dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showMoreQuickReactionOptions() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.more_quick_reactions_title))
            .setMessage(getString(R.string.big_packs))
            .setPositiveButton(getString(R.string.quick_reaction_20_pack)) { _, _ ->
                purchaseQuickReactionPack(20, 160)
            }
            .setNeutralButton(getString(R.string.quick_reaction_50_pack)) { _, _ ->
                purchaseQuickReactionPack(50, 350)
            }
            .setNegativeButton(getString(R.string.back_button), null)
            .show()
    }
    
    private fun purchaseQuickReactionPack(count: Int, cost: Int) {
        if (crystalManager.getCrystalBalance() >= cost) {
            if (spontaneousFeatures.purchaseQuickReactionPack(count)) {
                showSuccessMessage(getString(R.string.quick_reactions_purchased, count))
            } else {
                showErrorMessage(getString(R.string.purchase_failed))
            }
        } else {
            val needed = cost - crystalManager.getCrystalBalance()
            showErrorMessage(getString(R.string.insufficient_aura, needed))
        }
    }
    
    private fun showVoiceIntroDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_intro_activated_title))
            .setMessage(getString(R.string.voice_intro_activated_message))
            .setPositiveButton(getString(R.string.ok_button)) { _, _ ->
                showSuccessMessage(getString(R.string.voice_intro_activated_success))
                
                // Voice Intro'yu aktif et (demo için basit aktivasyon)
                val demoAudio = "demo_audio_base64" // Gerçek uygulamada ses kaydı olacak
                spontaneousFeatures.setVoiceIntro(demoAudio, 30)
            }
            .show()
    }
    
    private fun showThemeSelectionDialog() {
        Log.d("CrystalStore", "🎨 showThemeSelectionDialog called")
        
        try {
            // Tema listesi ve fiyatları
            val themes = arrayOf(
                getString(R.string.galaxy_theme_option),
                getString(R.string.sunset_theme_option), 
                getString(R.string.ocean_theme_option),
                getString(R.string.fire_theme_option),
                getString(R.string.aurora_theme_option)
            )
            
            val themeIds = arrayOf("GALAXY", "SUNSET", "OCEAN", "FIRE", "AURORA")
            val themeCosts = arrayOf(60, 60, 60, 60, 80)
            
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.premium_theme_selection_title))
                .setItems(themes) { _, which ->
                    val selectedTheme = themeIds[which]
                    val cost = themeCosts[which]
                    purchasePremiumTheme(selectedTheme, cost)
                }
                .setNegativeButton("İptal", null)
                .show()
                
            Log.d("CrystalStore", "✅ Theme selection dialog shown")
        } catch (e: Exception) {
            Log.e("CrystalStore", "❌ Error showing theme dialog", e)
            Toast.makeText(this, "❌ Tema dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showAllThemeOptions() {
        // Bu metod artık gerekli değil, showThemeSelectionDialog tüm temaları gösteriyor
        showThemeSelectionDialog()
    }
    
    private fun purchasePremiumTheme(themeId: String, cost: Int) {
        val currentBalance = crystalManager.getCrystalBalance()
        if (currentBalance >= cost) {
            try {
                val theme = SpontaneousFeatures.PremiumTheme.valueOf(themeId)
                if (spontaneousFeatures.activatePremiumTheme(theme)) {
                    showSuccessMessage(getString(R.string.theme_purchased_activated, theme.themeName))
                } else {
                    showErrorMessage(getString(R.string.theme_activation_failed))
                }
            } catch (e: Exception) {
                showErrorMessage("Tema bulunamadı: $themeId")
            }
        } else {
            val needed = cost - currentBalance
            showErrorMessage("Yetersiz aura! $needed aura daha gerekli.")
        }
    }
    
    private fun showRadarEffectSelectionDialog() {
        Log.d("CrystalStore", "🌀 showRadarEffectSelectionDialog called")
        
        try {
            // Radar efekt listesi ve fiyatları
            val effects = arrayOf(
                getString(R.string.pulse_effect_option),
                getString(R.string.wave_effect_option),
                getString(R.string.spiral_effect_option), 
                getString(R.string.lightning_effect_option),
                getString(R.string.rainbow_effect_option)
            )
            
            val effectIds = arrayOf("PULSE", "RIPPLE", "SPIRAL", "LIGHTNING", "RAINBOW")
            val effectCosts = arrayOf(80, 80, 100, 120, 100)
            
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.radar_effect_selection_title))
                .setItems(effects) { _, which ->
                    val selectedEffect = effectIds[which]
                    val cost = effectCosts[which]
                    purchaseRadarEffect(selectedEffect, cost)
                }
                .setNegativeButton("İptal", null)
                .show()
                
            Log.d("CrystalStore", "✅ Radar effect dialog shown")
        } catch (e: Exception) {
            Log.e("CrystalStore", "❌ Error showing radar effect dialog", e)
            Toast.makeText(this, "❌ Radar efekt dialog hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun purchaseRadarEffect(effectId: String, cost: Int) {
        val currentBalance = crystalManager.getCrystalBalance()
        if (currentBalance >= cost) {
            try {
                val effect = SpontaneousFeatures.RadarEffect.valueOf(effectId)
                if (spontaneousFeatures.activateRadarEffect(effect)) {
                    showSuccessMessage(getString(R.string.radar_effect_purchased_activated, effect.effectName))
                } else {
                    showErrorMessage(getString(R.string.effect_activation_failed))
                }
            } catch (e: Exception) {
                showErrorMessage("Efekt bulunamadı: $effectId")
            }
        } else {
            val needed = cost - currentBalance
            showErrorMessage("Yetersiz aura! $needed aura daha gerekli.")
        }
    }
    
    // Veri sınıfları
    data class CrystalFeature(
        val id: String,
        val title: String,
        val description: String,
        val cost: Int,
        val duration: String,
        val category: String,
        val isPopular: Boolean
    )
    
    data class CrystalPack(
        val id: String,
        val title: String,
        val crystals: Int,
        val bonus: Int,
        val price: String,
        val isPopular: Boolean
    )
}