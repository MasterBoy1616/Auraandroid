package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Premium özellikler yöneticisi (Aura sistemi için basitleştirilmiş)
 */
class PremiumManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PremiumManager"
        private const val PREFS_NAME = "premium_prefs"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Premium durumları
    private val _premiumStatus = MutableStateFlow(PremiumStatus())
    val premiumStatus: StateFlow<PremiumStatus> = _premiumStatus
    
    // Consumable item sayıları
    private val _superLikes = MutableStateFlow(getDailySuperLikes())
    val superLikes: StateFlow<Int> = _superLikes
    
    private val _boosts = MutableStateFlow(getBoosts())
    val boosts: StateFlow<Int> = _boosts
    
    private val _rewinds = MutableStateFlow(getRewinds())
    val rewinds: StateFlow<Int> = _rewinds
    
    data class PremiumStatus(
        val hasAuraBoost: Boolean = false,
        val hasMoodSignals: Boolean = false,
        val hasVibeMatch: Boolean = false,
        val hasProximityAlerts: Boolean = false,
        val hasVoiceIntro: Boolean = false,
        val hasAmbientSounds: Boolean = false,
        val isGoldenHourActive: Boolean = false,
        val isWeekendWarriorActive: Boolean = false,
        val quickReactions: Int = 0,
        val instantPulses: Int = 0,
        val flashEventCredits: Int = 0,
        val energySyncDaysLeft: Int = 0,
        val ownedThemes: Set<String> = emptySet(),
        val ownedRadarEffects: Set<String> = emptySet()
    )
    
    init {
        loadPremiumStatus()
        checkDailyReset()
    }
    
    // Premium özellik kontrolleri
    fun canUseSuperLike(): Boolean {
        return _superLikes.value > 0
    }
    
    fun canUseBoost(): Boolean {
        return _boosts.value > 0
    }
    
    fun canUseRewind(): Boolean {
        return _rewinds.value > 0
    }
    
    fun canSeeWhoLiked(): Boolean {
        return _premiumStatus.value.hasAuraBoost
    }
    
    fun canUseMessageEffects(): Boolean {
        return _premiumStatus.value.hasMoodSignals
    }
    
    fun canUseAnalytics(): Boolean {
        return _premiumStatus.value.hasVibeMatch
    }
    
    fun canChangeLocation(): Boolean {
        return _premiumStatus.value.hasProximityAlerts
    }
    
    fun hasTheme(themeId: String): Boolean {
        return _premiumStatus.value.ownedThemes.contains(themeId)
    }
    
    // Consumable item kullanımı
    fun useSuperLike(): Boolean {
        val current = _superLikes.value
        if (current > 0) {
            _superLikes.value = current - 1
            saveSuperLikes(_superLikes.value)
            return true
        }
        return false
    }
    
    fun useBoost(): Boolean {
        val current = _boosts.value
        if (current > 0) {
            _boosts.value = current - 1
            saveBoosts(_boosts.value)
            return true
        }
        return false
    }
    
    fun useRewind(): Boolean {
        val current = _rewinds.value
        if (current > 0) {
            _rewinds.value = current - 1
            saveRewinds(_rewinds.value)
            return true
        }
        return false
    }
    
    // Item ekleme
    private fun addSuperLikes(count: Int) {
        val current = _superLikes.value
        _superLikes.value = current + count
        saveSuperLikes(_superLikes.value)
    }
    
    private fun addBoosts(count: Int) {
        val current = _boosts.value
        _boosts.value = current + count
        saveBoosts(_boosts.value)
    }
    
    private fun addRewinds(count: Int) {
        val current = _rewinds.value
        _rewinds.value = current + count
        saveRewinds(_rewinds.value)
    }
    
    // Günlük reset kontrolü
    private fun checkDailyReset() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastResetDay = prefs.getInt("last_reset_day", -1)
        
        if (lastResetDay != today) {
            // Günlük ücretsiz Super Like'ları sıfırla
            _superLikes.value = 3 // Günlük 3 ücretsiz
            saveSuperLikes(_superLikes.value)
            
            prefs.edit().putInt("last_reset_day", today).apply()
        }
    }
    
    private fun getDailySuperLikes(): Int {
        return prefs.getInt("super_likes", 3)
    }
    
    private fun getBoosts(): Int {
        return prefs.getInt("boosts", 0)
    }
    
    private fun getRewinds(): Int {
        return prefs.getInt("rewinds", 0)
    }
    
    private fun saveSuperLikes(count: Int) {
        prefs.edit().putInt("super_likes", count).apply()
    }
    
    private fun saveBoosts(count: Int) {
        prefs.edit().putInt("boosts", count).apply()
    }
    
    private fun saveRewinds(count: Int) {
        prefs.edit().putInt("rewinds", count).apply()
    }
    
    private fun loadPremiumStatus() {
        val status = PremiumStatus(
            hasAuraBoost = prefs.getBoolean("has_aura_boost", false),
            hasMoodSignals = prefs.getBoolean("has_mood_signals", false),
            hasVibeMatch = prefs.getBoolean("has_vibe_match", false),
            hasProximityAlerts = prefs.getBoolean("has_proximity_alerts", false),
            hasVoiceIntro = prefs.getBoolean("has_voice_intro", false),
            hasAmbientSounds = prefs.getBoolean("has_ambient_sounds", false),
            ownedThemes = prefs.getStringSet("owned_themes", emptySet()) ?: emptySet(),
            quickReactions = prefs.getInt("quick_reactions", 0),
            instantPulses = prefs.getInt("instant_pulses", 0),
            flashEventCredits = prefs.getInt("flash_event_credits", 0)
        )
        _premiumStatus.value = status
    }
    
    private fun updatePremiumStatus(update: (PremiumStatus) -> PremiumStatus) {
        val newStatus = update(_premiumStatus.value)
        _premiumStatus.value = newStatus
        
        // Save to preferences
        prefs.edit().apply {
            putBoolean("has_aura_boost", newStatus.hasAuraBoost)
            putBoolean("has_mood_signals", newStatus.hasMoodSignals)
            putBoolean("has_vibe_match", newStatus.hasVibeMatch)
            putBoolean("has_proximity_alerts", newStatus.hasProximityAlerts)
            putBoolean("has_voice_intro", newStatus.hasVoiceIntro)
            putBoolean("has_ambient_sounds", newStatus.hasAmbientSounds)
            putStringSet("owned_themes", newStatus.ownedThemes)
            putInt("quick_reactions", newStatus.quickReactions)
            putInt("instant_pulses", newStatus.instantPulses)
            putInt("flash_event_credits", newStatus.flashEventCredits)
            apply()
        }
    }
    
    fun cleanup() {
        // No billing client to cleanup in simplified version
    }
    
    // Premium özellik kontrol method'ları (Aura sistemi için)
    fun canUseAuraBoost(): Boolean = true
    fun canUseInstantPulse(): Boolean = true
    fun hasMoodSignals(): Boolean = true
    fun canUseGoldenHour(): Boolean = true
    fun canCreateFlashEvent(): Boolean = true
    fun canUseQuickReactions(): Boolean = true
    fun hasProximityAlerts(): Boolean = true
    fun useQuickReaction() { 
        val current = _premiumStatus.value.quickReactions
        if (current > 0) {
            updatePremiumStatus { it.copy(quickReactions = current - 1) }
        }
    }
}