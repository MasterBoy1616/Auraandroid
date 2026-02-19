package com.aura.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Aura'ya özel spontane premium özellikler - SINGLETON
 */
class SpontaneousFeatures private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SpontaneousFeatures"
        private const val PREFS_NAME = "spontaneous_features_prefs"
        
        @Volatile
        private var INSTANCE: SpontaneousFeatures? = null
        
        fun getInstance(context: Context): SpontaneousFeatures {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpontaneousFeatures(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val premiumManager = PremiumManager(context)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Aktif özellik durumları - SharedPreferences ile kalıcı
    private val _auraBoostActive = MutableStateFlow(loadAuraBoostState())
    val auraBoostActive: StateFlow<Boolean> = _auraBoostActive
    
    private val _instantPulseActive = MutableStateFlow(loadInstantPulseState())
    val instantPulseActive: StateFlow<Boolean> = _instantPulseActive
    
    private val _currentMood = MutableStateFlow<MoodSignal?>(loadMoodState())
    val currentMood: StateFlow<MoodSignal?> = _currentMood
    
    private val _goldenHourActive = MutableStateFlow(loadGoldenHourState())
    val goldenHourActive: StateFlow<Boolean> = _goldenHourActive
    
    private val _activeFlashEvents = MutableStateFlow<List<FlashEvent>>(loadFlashEvents())
    val activeFlashEvents: StateFlow<List<FlashEvent>> = _activeFlashEvents
    
    // YENİ: Kalan premium özellikler
    private val _quickReactionsRemaining = MutableStateFlow(loadQuickReactionsRemaining())
    val quickReactionsRemaining: StateFlow<Int> = _quickReactionsRemaining
    
    private val _proximityAlertsActive = MutableStateFlow(loadProximityAlertsState())
    val proximityAlertsActive: StateFlow<Boolean> = _proximityAlertsActive
    
    private val _voiceIntroActive = MutableStateFlow(loadVoiceIntroState())
    val voiceIntroActive: StateFlow<Boolean> = _voiceIntroActive
    
    private val _premiumThemeActive = MutableStateFlow(loadPremiumThemeState())
    val premiumThemeActive: StateFlow<Boolean> = _premiumThemeActive
    
    private val _radarEffectActive = MutableStateFlow(loadRadarEffectState())
    val radarEffectActive: StateFlow<Boolean> = _radarEffectActive
    
    init {
        Log.d(TAG, "🌟 SpontaneousFeatures initialized with persistent state")
        
        // Uygulama açılışında aktif özellikleri kontrol et ve süreleri yönet
        checkAndRestoreActiveFeatures()
    }
    
    // Veri sınıfları
    data class MoodSignal(
        val type: MoodType,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val duration: Long = 30 * 60 * 1000L // 30 dakika
    )
    
    enum class MoodType(val emoji: String, val description: String, val color: String) {
        COFFEE("☕", "Kahve içelim", "#8B4513"),
        MUSIC("🎵", "Müzik dinleyelim", "#9932CC"),
        WALK("🚶", "Yürüyüş yapalım", "#228B22"),
        FOOD("🍕", "Yemek yiyelim", "#FF6347"),
        GAME("🎮", "Oyun oynayalım", "#4169E1"),
        STUDY("📚", "Çalışalım", "#2F4F4F"),
        PARTY("🎉", "Eğlenelim", "#FF1493"),
        CHILL("😌", "Takılalım", "#87CEEB")
    }
    
    data class FlashEvent(
        val id: String = UUID.randomUUID().toString(),
        val creatorHash: String,
        val title: String,
        val description: String,
        val location: String,
        val timestamp: Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + (30 * 60 * 1000L), // 30 dakika
        val maxParticipants: Int = 10,
        val participants: MutableList<String> = mutableListOf()
    )
    
    enum class EnergyLevel(val displayName: String, val color: String) {
        HIGH("Yüksek Enerji", "#FF4500"),
        MEDIUM("Orta Enerji", "#FFA500"),
        LOW("Düşük Enerji", "#32CD32"),
        CHILL("Sakin", "#87CEEB")
    }
    
    // YENİ: Quick Reaction türleri
    enum class QuickReaction(val emoji: String, val message: String, val cost: Int) {
        WAVE("👋", "Selam!", 10),
        LOVE("😍", "Beğendim!", 15),
        CURIOUS("🤔", "Merak ettim", 12),
        EXCITED("⚡", "Heyecanlı!", 18),
        COFFEE("☕", "Kahve içelim", 20),
        WINK("😉", "Göz kırptım", 15)
    }
    
    // YENİ: Premium tema türleri
    enum class PremiumTheme(val themeName: String, val description: String, val cost: Int) {
        GALAXY("Galaksi", "Yıldızlı gece teması", 60),
        SUNSET("Gün Batımı", "Turuncu-pembe gradyan", 60),
        OCEAN("Okyanus", "Mavi-yeşil dalga efekti", 60),
        FIRE("Ateş", "Kırmızı-turuncu alev efekti", 60),
        AURORA("Aurora", "Kutup ışığı efekti", 80)
    }
    
    // YENİ: Radar efekt türleri
    enum class RadarEffect(val effectName: String, val description: String, val cost: Int) {
        PULSE("Nabız", "Kalp atışı efekti", 80),
        RIPPLE("Dalga", "Su dalgası efekti", 80),
        SPIRAL("Spiral", "Dönen spiral efekti", 100),
        LIGHTNING("Şimşek", "Elektrik efekti", 120),
        RAINBOW("Gökkuşağı", "Renk geçişli efekt", 100)
    }
    
    // YENİ: Voice Intro veri sınıfı
    data class VoiceIntro(
        val audioBase64: String,
        val duration: Int, // saniye
        val timestamp: Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L) // 7 gün
    )
    
    // Aura Boost özelliği - KALİCİ
    fun activateAuraBoost(): Boolean {
        if (!premiumManager.canUseAuraBoost()) {
            return false
        }
        
        val expiresAt = System.currentTimeMillis() + (30 * 60 * 1000L) // 30 dakika
        saveAuraBoostState(true, expiresAt)
        _auraBoostActive.value = true
        
        // BLE advertising power'ı artır
        increaseBLEPower()
        
        // 30 dakika sonra deaktive et
        handler.postDelayed({
            _auraBoostActive.value = false
            saveAuraBoostState(false, 0)
            resetBLEPower()
            Log.d(TAG, "Aura Boost expired and saved")
        }, 30 * 60 * 1000L)
        
        Log.d(TAG, "Aura Boost activated for 30 minutes and saved")
        return true
    }
    
    // Instant Pulse özelliği - KALİCİ
    fun activateInstantPulse(): Boolean {
        if (!premiumManager.canUseInstantPulse()) {
            return false
        }
        
        val expiresAt = System.currentTimeMillis() + (5 * 60 * 1000L) // 5 dakika
        saveInstantPulseState(true, expiresAt)
        _instantPulseActive.value = true
        
        // Scan interval'ı hızlandır
        increaseScanFrequency()
        
        // 5 dakika sonra deaktive et
        handler.postDelayed({
            _instantPulseActive.value = false
            saveInstantPulseState(false, 0)
            resetScanFrequency()
            Log.d(TAG, "Instant Pulse expired and saved")
        }, 5 * 60 * 1000L)
        
        Log.d(TAG, "Instant Pulse activated for 5 minutes and saved")
        return true
    }
    
    // Mood Signal ayarlama
    fun setMoodSignal(moodType: MoodType, customMessage: String? = null): Boolean {
        if (!premiumManager.hasMoodSignals()) {
            return false
        }
        
        val message = customMessage ?: moodType.description
        val mood = MoodSignal(moodType, message)
        _currentMood.value = mood
        
        // BLE advertising data'sına mood ekle
        addMoodToBLEData(mood)
        
        // Mood süresinin sonunda temizle
        handler.postDelayed({
            if (_currentMood.value?.timestamp == mood.timestamp) {
                _currentMood.value = null
                removeMoodFromBLEData()
            }
        }, mood.duration)
        
        Log.d(TAG, "Mood signal set: ${moodType.description}")
        return true
    }
    
    // Golden Hour aktivasyonu
    fun activateGoldenHour(): Boolean {
        if (!premiumManager.canUseGoldenHour()) {
            return false
        }
        
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // Sadece 18:00-22:00 arası aktif
        if (hour in 18..21) {
            _goldenHourActive.value = true
            
            // Advertising priority artır
            increaseAdvertisingPriority()
            
            // Gece yarısında deaktive et
            val midnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            
            val timeUntilMidnight = midnight.timeInMillis - System.currentTimeMillis()
            handler.postDelayed({
                _goldenHourActive.value = false
                resetAdvertisingPriority()
            }, timeUntilMidnight)
            
            Log.d(TAG, "Golden Hour activated")
            return true
        }
        
        return false
    }
    
    // Flash Event oluşturma
    fun createFlashEvent(title: String, description: String, location: String): FlashEvent? {
        if (!premiumManager.canCreateFlashEvent()) {
            return null
        }
        
        val userPrefs = UserPreferences(context)
        val event = FlashEvent(
            creatorHash = userPrefs.getUserId(), // getUserHash yerine getUserId kullan
            title = title,
            description = description,
            location = location
        )
        
        // Event'i aktif listeye ekle
        val currentEvents = _activeFlashEvents.value.toMutableList()
        currentEvents.add(event)
        _activeFlashEvents.value = currentEvents
        
        // BLE ile event'i yayınla
        broadcastFlashEvent(event)
        
        // 30 dakika sonra event'i kaldır
        handler.postDelayed({
            removeFlashEvent(event.id)
        }, event.expiresAt - event.timestamp)
        
        Log.d(TAG, "Flash Event created: ${event.title}")
        return event
    }
    
    // Flash Event'e katılma
    fun joinFlashEvent(eventId: String): Boolean {
        val event = _activeFlashEvents.value.find { it.id == eventId }
        if (event != null && event.participants.size < event.maxParticipants) {
            val userPrefs = UserPreferences(context)
            val userHash = userPrefs.getUserId() // getUserHash yerine getUserId kullan
            
            if (!event.participants.contains(userHash)) {
                event.participants.add(userHash)
                
                // Event güncellemesini yayınla
                broadcastEventUpdate(event)
                
                Log.d(TAG, "Joined Flash Event: ${event.title}")
                return true
            }
        }
        return false
    }
    
    // Proximity Alert ayarlama
    fun enableProximityAlerts(targetUserHash: String): Boolean {
        if (!premiumManager.hasProximityAlerts()) {
            return false
        }
        
        // BLE RSSI monitoring başlat
        startProximityMonitoring(targetUserHash)
        
        Log.d(TAG, "Proximity alerts enabled for user: $targetUserHash")
        return true
    }
    
    // Yardımcı fonksiyonlar (BLE entegrasyonu için)
    private fun increaseBLEPower() {
        // BleEngine'e güçlü advertising modu ayarla
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setHighPowerMode(true)
    }
    
    private fun resetBLEPower() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setHighPowerMode(false)
    }
    
    private fun increaseScanFrequency() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setFastScanMode(true)
    }
    
    private fun resetScanFrequency() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setFastScanMode(false)
    }
    
    private fun addMoodToBLEData(mood: MoodSignal) {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setMoodData(mood.type.name, mood.message)
    }
    
    private fun removeMoodFromBLEData() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.clearMoodData()
    }
    
    private fun increaseAdvertisingPriority() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setPriorityMode(true)
    }
    
    private fun resetAdvertisingPriority() {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.setPriorityMode(false)
    }
    
    private fun broadcastFlashEvent(event: FlashEvent) {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.broadcastFlashEvent(event)
    }
    
    private fun broadcastEventUpdate(event: FlashEvent) {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.broadcastEventUpdate(event)
    }
    
    private fun sendReactionViaBLE(targetUserHash: String, reaction: QuickReaction) {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.sendQuickReaction(targetUserHash, reaction.emoji, reaction.message)
    }
    
    private fun startProximityMonitoring(targetUserHash: String) {
        val bleEngine = BleEngineManager.getInstance()
        bleEngine?.startProximityMonitoring(targetUserHash) { distance ->
            if (distance <= 100) { // 100 metre içinde
                showProximityNotification(targetUserHash, distance)
            }
        }
    }
    
    private fun showProximityNotification(userHash: String, distance: Int) {
        // Bildirim göster
        Log.d(TAG, "User $userHash is ${distance}m away!")
    }
    
    private fun removeFlashEvent(eventId: String) {
        val currentEvents = _activeFlashEvents.value.toMutableList()
        currentEvents.removeAll { it.id == eventId }
        _activeFlashEvents.value = currentEvents
    }
    
    // ==================== YENİ PREMIUM ÖZELLİKLER ====================
    
    // Quick Reaction gönderme
    fun sendQuickReaction(targetUserHash: String, reaction: QuickReaction): Boolean {
        val remaining = _quickReactionsRemaining.value
        if (remaining <= 0) {
            return false
        }
        
        // BLE ile reaction gönder
        sendReactionViaBLE(targetUserHash, reaction)
        
        // Kalan sayıyı azalt ve kaydet
        val newRemaining = remaining - 1
        _quickReactionsRemaining.value = newRemaining
        saveQuickReactionsRemaining(newRemaining)
        
        Log.d(TAG, "Quick Reaction sent: ${reaction.emoji}, remaining: $newRemaining")
        return true
    }
    
    // Quick Reaction paketi satın alma
    fun purchaseQuickReactionPack(count: Int): Boolean {
        val cost = count * 10 // Her reaction 10 aura
        val crystalManager = CrystalManager(context)
        
        if (crystalManager.spendCrystals(cost, "quick_reactions")) {
            val current = _quickReactionsRemaining.value
            val newTotal = current + count
            _quickReactionsRemaining.value = newTotal
            saveQuickReactionsRemaining(newTotal)
            
            Log.d(TAG, "Quick Reaction pack purchased: +$count reactions, total: $newTotal")
            return true
        }
        return false
    }
    
    // Voice Intro kaydetme
    fun setVoiceIntro(audioBase64: String, duration: Int): Boolean {
        val crystalManager = CrystalManager(context)
        if (!crystalManager.spendCrystals(CrystalManager.VOICE_INTRO_COST, "voice_intro")) {
            return false
        }
        
        val voiceIntro = VoiceIntro(audioBase64, duration)
        saveVoiceIntroState(true, voiceIntro)
        _voiceIntroActive.value = true
        
        // 7 gün sonra süresi dolsun
        handler.postDelayed({
            _voiceIntroActive.value = false
            saveVoiceIntroState(false, null)
            Log.d(TAG, "Voice Intro expired")
        }, 7 * 24 * 60 * 60 * 1000L)
        
        Log.d(TAG, "Voice Intro set for 7 days")
        return true
    }
    
    // Premium Tema aktivasyonu
    fun activatePremiumTheme(theme: PremiumTheme): Boolean {
        val crystalManager = CrystalManager(context)
        if (!crystalManager.spendCrystals(theme.cost, "premium_theme_${theme.name}")) {
            return false
        }
        
        // Temayı satın alındı olarak kaydet
        val purchasedPrefs = context.getSharedPreferences("purchased_themes", Context.MODE_PRIVATE)
        purchasedPrefs.edit()
            .putBoolean("theme_${theme.themeName}", true)
            .apply()
        
        savePremiumThemeState(true, theme.themeName) // theme.name yerine theme.themeName
        _premiumThemeActive.value = true
        
        // Tema kalıcı (süre sınırı yok)
        Log.d(TAG, "Premium Theme activated and purchased: ${theme.themeName}")
        return true
    }
    
    // Radar Efekt aktivasyonu
    fun activateRadarEffect(effect: RadarEffect): Boolean {
        val crystalManager = CrystalManager(context)
        if (!crystalManager.spendCrystals(effect.cost, "radar_effect_${effect.name}")) {
            return false
        }
        
        // Radar efektini satın alındı olarak kaydet
        val purchasedPrefs = context.getSharedPreferences("purchased_radar_effects", Context.MODE_PRIVATE)
        purchasedPrefs.edit()
            .putBoolean("effect_${effect.effectName}", true)
            .apply()
        
        saveRadarEffectState(true, effect.effectName) // effect.name yerine effect.effectName
        _radarEffectActive.value = true
        
        // Efekt kalıcı (süre sınırı yok)
        Log.d(TAG, "Radar Effect activated and purchased: ${effect.effectName}")
        return true
    }
    
    // Proximity Alert aktivasyonu
    fun activateProximityAlerts(): Boolean {
        val crystalManager = CrystalManager(context)
        if (!crystalManager.spendCrystals(CrystalManager.PROXIMITY_ALERT_COST, "proximity_alerts")) {
            return false
        }
        
        val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L) // 24 saat
        saveProximityAlertsState(true, expiresAt)
        _proximityAlertsActive.value = true
        
        // 24 saat sonra deaktive et
        handler.postDelayed({
            _proximityAlertsActive.value = false
            saveProximityAlertsState(false, 0)
            Log.d(TAG, "Proximity Alerts expired")
        }, 24 * 60 * 60 * 1000L)
        
        Log.d(TAG, "Proximity Alerts activated for 24 hours")
        return true
    }
    
    // ==================== KALİCİLİK METODLARİ ====================
    
    private fun checkAndRestoreActiveFeatures() {
        Log.d(TAG, "🔄 Checking and restoring active features on app start")
        
        // Aura Boost kontrolü
        val auraBoostExpiry = prefs.getLong("aura_boost_expires_at", 0)
        if (auraBoostExpiry > System.currentTimeMillis()) {
            val remainingTime = auraBoostExpiry - System.currentTimeMillis()
            Log.d(TAG, "🔥 Restoring Aura Boost, remaining: ${remainingTime / 1000}s")
            _auraBoostActive.value = true
            increaseBLEPower()
            
            handler.postDelayed({
                _auraBoostActive.value = false
                saveAuraBoostState(false, 0)
                resetBLEPower()
            }, remainingTime)
        }
        
        // Instant Pulse kontrolü
        val instantPulseExpiry = prefs.getLong("instant_pulse_expires_at", 0)
        if (instantPulseExpiry > System.currentTimeMillis()) {
            val remainingTime = instantPulseExpiry - System.currentTimeMillis()
            Log.d(TAG, "⚡ Restoring Instant Pulse, remaining: ${remainingTime / 1000}s")
            _instantPulseActive.value = true
            increaseScanFrequency()
            
            handler.postDelayed({
                _instantPulseActive.value = false
                saveInstantPulseState(false, 0)
                resetScanFrequency()
            }, remainingTime)
        }
        
        // Golden Hour kontrolü
        val goldenHourExpiry = prefs.getLong("golden_hour_expires_at", 0)
        if (goldenHourExpiry > System.currentTimeMillis()) {
            val remainingTime = goldenHourExpiry - System.currentTimeMillis()
            Log.d(TAG, "🌅 Restoring Golden Hour, remaining: ${remainingTime / 1000}s")
            _goldenHourActive.value = true
            increaseAdvertisingPriority()
            
            handler.postDelayed({
                _goldenHourActive.value = false
                saveGoldenHourState(false, 0)
                resetAdvertisingPriority()
            }, remainingTime)
        }
        
        // Proximity Alerts kontrolü
        val proximityExpiry = prefs.getLong("proximity_alerts_expires_at", 0)
        if (proximityExpiry > System.currentTimeMillis()) {
            val remainingTime = proximityExpiry - System.currentTimeMillis()
            Log.d(TAG, "📍 Restoring Proximity Alerts, remaining: ${remainingTime / 1000}s")
            _proximityAlertsActive.value = true
            
            handler.postDelayed({
                _proximityAlertsActive.value = false
                saveProximityAlertsState(false, 0)
            }, remainingTime)
        }
        
        // Voice Intro kontrolü
        val voiceIntroExpiry = prefs.getLong("voice_intro_expires_at", 0)
        if (voiceIntroExpiry > System.currentTimeMillis()) {
            val remainingTime = voiceIntroExpiry - System.currentTimeMillis()
            Log.d(TAG, "🎤 Restoring Voice Intro, remaining: ${remainingTime / (24 * 60 * 60 * 1000)}d")
            _voiceIntroActive.value = true
            
            handler.postDelayed({
                _voiceIntroActive.value = false
                saveVoiceIntroState(false, null)
            }, remainingTime)
        }
        
        Log.d(TAG, "✅ Feature restoration complete")
    }
    
    // Load metodları
    private fun loadAuraBoostState(): Boolean {
        val isActive = prefs.getBoolean("aura_boost_active", false)
        val expiresAt = prefs.getLong("aura_boost_expires_at", 0)
        return isActive && expiresAt > System.currentTimeMillis()
    }
    
    private fun loadInstantPulseState(): Boolean {
        val isActive = prefs.getBoolean("instant_pulse_active", false)
        val expiresAt = prefs.getLong("instant_pulse_expires_at", 0)
        return isActive && expiresAt > System.currentTimeMillis()
    }
    
    private fun loadMoodState(): MoodSignal? {
        val moodTypeStr = prefs.getString("mood_type", null) ?: return null
        val moodMessage = prefs.getString("mood_message", "") ?: ""
        val timestamp = prefs.getLong("mood_timestamp", 0)
        val duration = prefs.getLong("mood_duration", 30 * 60 * 1000L)
        
        return try {
            val moodType = MoodType.valueOf(moodTypeStr)
            if (timestamp + duration > System.currentTimeMillis()) {
                MoodSignal(moodType, moodMessage, timestamp, duration)
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun loadGoldenHourState(): Boolean {
        val isActive = prefs.getBoolean("golden_hour_active", false)
        val expiresAt = prefs.getLong("golden_hour_expires_at", 0)
        return isActive && expiresAt > System.currentTimeMillis()
    }
    
    private fun loadFlashEvents(): List<FlashEvent> {
        // Flash events için basit implementation
        return emptyList()
    }
    
    private fun loadQuickReactionsRemaining(): Int {
        return prefs.getInt("quick_reactions_remaining", 0)
    }
    
    private fun loadProximityAlertsState(): Boolean {
        val isActive = prefs.getBoolean("proximity_alerts_active", false)
        val expiresAt = prefs.getLong("proximity_alerts_expires_at", 0)
        return isActive && expiresAt > System.currentTimeMillis()
    }
    
    private fun loadVoiceIntroState(): Boolean {
        val isActive = prefs.getBoolean("voice_intro_active", false)
        val expiresAt = prefs.getLong("voice_intro_expires_at", 0)
        return isActive && expiresAt > System.currentTimeMillis()
    }
    
    private fun loadPremiumThemeState(): Boolean {
        return prefs.getBoolean("premium_theme_active", false)
    }
    
    private fun loadRadarEffectState(): Boolean {
        return prefs.getBoolean("radar_effect_active", false)
    }
    
    // Save metodları
    private fun saveAuraBoostState(active: Boolean, expiresAt: Long) {
        prefs.edit()
            .putBoolean("aura_boost_active", active)
            .putLong("aura_boost_expires_at", expiresAt)
            .apply()
    }
    
    private fun saveInstantPulseState(active: Boolean, expiresAt: Long) {
        prefs.edit()
            .putBoolean("instant_pulse_active", active)
            .putLong("instant_pulse_expires_at", expiresAt)
            .apply()
    }
    
    private fun saveGoldenHourState(active: Boolean, expiresAt: Long) {
        prefs.edit()
            .putBoolean("golden_hour_active", active)
            .putLong("golden_hour_expires_at", expiresAt)
            .apply()
    }
    
    private fun saveProximityAlertsState(active: Boolean, expiresAt: Long) {
        prefs.edit()
            .putBoolean("proximity_alerts_active", active)
            .putLong("proximity_alerts_expires_at", expiresAt)
            .apply()
    }
    
    private fun saveVoiceIntroState(active: Boolean, voiceIntro: VoiceIntro?) {
        val editor = prefs.edit()
            .putBoolean("voice_intro_active", active)
        
        if (voiceIntro != null) {
            editor.putString("voice_intro_audio", voiceIntro.audioBase64)
                .putInt("voice_intro_duration", voiceIntro.duration)
                .putLong("voice_intro_expires_at", voiceIntro.expiresAt)
        } else {
            editor.putLong("voice_intro_expires_at", 0)
        }
        
        editor.apply()
    }
    
    private fun savePremiumThemeState(active: Boolean, themeName: String) {
        prefs.edit()
            .putBoolean("premium_theme_active", active)
            .putString("premium_theme_name", themeName)
            .apply()
    }
    
    private fun saveRadarEffectState(active: Boolean, effectName: String) {
        prefs.edit()
            .putBoolean("radar_effect_active", active)
            .putString("radar_effect_name", effectName)
            .apply()
    }
    
    private fun saveQuickReactionsRemaining(count: Int) {
        prefs.edit()
            .putInt("quick_reactions_remaining", count)
            .apply()
    }
    
    // Getter metodları
    fun getCurrentTheme(): String? {
        return if (_premiumThemeActive.value) {
            prefs.getString("premium_theme_name", null)
        } else null
    }
    
    fun getCurrentRadarEffect(): String? {
        return if (_radarEffectActive.value) {
            prefs.getString("radar_effect_name", null)
        } else null
    }
    
    fun getCurrentVoiceIntro(): VoiceIntro? {
        return if (_voiceIntroActive.value) {
            val audio = prefs.getString("voice_intro_audio", null)
            val duration = prefs.getInt("voice_intro_duration", 0)
            val expiresAt = prefs.getLong("voice_intro_expires_at", 0)
            
            if (audio != null && expiresAt > System.currentTimeMillis()) {
                VoiceIntro(audio, duration, System.currentTimeMillis(), expiresAt)
            } else null
        } else null
    }
}