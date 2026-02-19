package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Aura puan sistemi yöneticisi
 */
class CrystalManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CrystalManager"
        private const val PREFS_NAME = "crystal_prefs"
        
        // Aura paket fiyatları (USD) - UPDATED PRICING
        const val STARTER_PACK_PRICE_USD = 1.99 // 100 Aura
        const val BOOST_PACK_PRICE_USD = 3.99 // 250 Aura  
        const val POPULAR_PACK_PRICE_USD = 6.99 // 500 Aura
        const val POWER_PACK_PRICE_USD = 11.99 // 1000 Aura
        const val PRO_PACK_PRICE_USD = 24.99 // 2500 Aura
        const val ELITE_PACK_PRICE_USD = 44.99 // 5000 Aura
        
        // Özellik maliyetleri (Aura)
        const val AURA_BOOST_COST = 80
        const val INSTANT_PULSE_COST = 50
        const val MOOD_SIGNAL_COST = 30
        const val QUICK_REACTION_COST = 10
        const val GOLDEN_HOUR_COST = 150
        const val FLASH_EVENT_COST = 100
        const val PROXIMITY_ALERT_COST = 200
        const val VOICE_INTRO_COST = 120
        const val THEME_COST = 60
        const val RADAR_EFFECT_COST = 80
        
        // Günlük kazanç limitleri
        const val DAILY_LOGIN_REWARD = 5
        const val FIRST_MATCH_REWARD = 10
        const val FIRST_MESSAGE_REWARD = 15
        const val ACTIVE_30MIN_REWARD = 20
        const val PROFILE_UPDATE_REWARD = 10
        
        // Haftalık görev ödülleri
        const val WEEKLY_LOGIN_STREAK_REWARD = 50
        const val WEEKLY_MATCHES_REWARD = 75
        const val WEEKLY_MESSAGES_REWARD = 40
        const val WEEKLY_CHATS_REWARD = 60
        
        // Yardımcı fonksiyonlar
        fun getCurrentDate(): String {
            val calendar = Calendar.getInstance()
            return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}-${calendar.get(Calendar.DAY_OF_MONTH)}"
        }
        
        fun getCurrentWeekStart(): String {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            return "${calendar.get(Calendar.YEAR)}-W${calendar.get(Calendar.WEEK_OF_YEAR)}"
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Aura durumu
    private val _crystalBalance = MutableStateFlow(getCrystalBalance())
    val crystalBalance: StateFlow<Int> = _crystalBalance
    
    // Günlük görevler durumu
    private val _dailyTasks = MutableStateFlow(loadDailyTasks())
    val dailyTasks: StateFlow<DailyTasks> = _dailyTasks
    
    // Haftalık görevler durumu
    private val _weeklyTasks = MutableStateFlow(loadWeeklyTasks())
    val weeklyTasks: StateFlow<WeeklyTasks> = _weeklyTasks
    
    // Başarımlar
    private val _achievements = MutableStateFlow(loadAchievements())
    val achievements: StateFlow<Set<Achievement>> = _achievements
    
    data class DailyTasks(
        val loginCompleted: Boolean = false,
        val firstMatchCompleted: Boolean = false,
        val firstMessageCompleted: Boolean = false,
        val active30MinCompleted: Boolean = false,
        val profileUpdateCompleted: Boolean = false,
        val lastResetDate: String = CrystalManager.getCurrentDate()
    )
    
    data class WeeklyTasks(
        val loginStreak: Int = 0,
        val matchesCount: Int = 0,
        val messagesCount: Int = 0,
        val chatsCount: Int = 0,
        val weekStartDate: String = CrystalManager.getCurrentWeekStart()
    )
    
    enum class Achievement(val id: String, val title: String, val description: String, val reward: Int, val icon: String) {
        FIRST_MATCH("first_match", "First Match", "You made your first match!", 25, "💝"),
        TENTH_MATCH("tenth_match", "Popular", "You completed 10 matches!", 100, "🌟"),
        FIRST_CHAT("first_chat", "Chatty", "You started your first chat!", 30, "💬"),
        POPULAR_PROFILE("popular_profile", "Attractive Profile", "10 people liked you!", 150, "😍"),
        SOCIAL_BUTTERFLY("social_butterfly", "Social Butterfly", "You made 50 matches!", 500, "🦋"),
        DAILY_STREAK_7("daily_streak_7", "Loyal User", "7 consecutive days login!", 200, "🔥"),
        CHAT_MASTER("chat_master", "Chat Master", "You sent 100 messages!", 300, "🎯"),
        NIGHT_OWL("night_owl", "Night Owl", "You matched after midnight!", 50, "🌙"),
        EARLY_BIRD("early_bird", "Early Bird", "You were active before 6 AM!", 50, "🌅")
    }
    
    init {
        checkDailyReset()
        checkWeeklyReset()
    }
    
    // Aura bakiyesi
    fun getCrystalBalance(): Int {
        return prefs.getInt("crystal_balance", 0)
    }
    
    // Aura ekleme (satın alma)
    fun addCrystals(amount: Int, source: String = "purchase"): Boolean {
        val currentBalance = _crystalBalance.value
        val newBalance = currentBalance + amount
        
        _crystalBalance.value = newBalance
        prefs.edit().putInt("crystal_balance", newBalance).apply()
        
        Log.d(TAG, "Added $amount aura from $source. New balance: $newBalance")
        
        // İstatistik kaydet
        recordCrystalTransaction(amount, source, "earned")
        
        return true
    }
    
    // Aura harcama
    fun spendCrystals(amount: Int, feature: String): Boolean {
        val currentBalance = _crystalBalance.value
        
        Log.d(TAG, "💰 SPEND_CRYSTALS: Attempting to spend $amount for $feature, current balance: $currentBalance")
        
        if (currentBalance >= amount) {
            val newBalance = currentBalance - amount
            _crystalBalance.value = newBalance
            prefs.edit().putInt("crystal_balance", newBalance).apply()
            
            Log.d(TAG, "✅ SPEND_CRYSTALS: Successfully spent $amount aura on $feature. New balance: $newBalance")
            
            // İstatistik kaydet
            recordCrystalTransaction(amount, feature, "spent")
            
            return true
        } else {
            Log.w(TAG, "❌ SPEND_CRYSTALS: Insufficient aura. Required: $amount, Available: $currentBalance")
            return false
        }
    }
    
    // Günlük görevler
    fun completeLoginTask(): Int {
        val tasks = _dailyTasks.value
        if (!tasks.loginCompleted) {
            val updatedTasks = tasks.copy(loginCompleted = true)
            _dailyTasks.value = updatedTasks
            saveDailyTasks(updatedTasks)
            
            addCrystals(DAILY_LOGIN_REWARD, "daily_login")
            return DAILY_LOGIN_REWARD
        }
        return 0
    }
    
    fun completeFirstMatchTask(): Int {
        val tasks = _dailyTasks.value
        if (!tasks.firstMatchCompleted) {
            val updatedTasks = tasks.copy(firstMatchCompleted = true)
            _dailyTasks.value = updatedTasks
            saveDailyTasks(updatedTasks)
            
            addCrystals(FIRST_MATCH_REWARD, "first_match")
            
            // Başarım kontrolü
            checkAchievement(Achievement.FIRST_MATCH)
            
            return FIRST_MATCH_REWARD
        }
        return 0
    }
    
    fun completeFirstMessageTask(): Int {
        val tasks = _dailyTasks.value
        if (!tasks.firstMessageCompleted) {
            val updatedTasks = tasks.copy(firstMessageCompleted = true)
            _dailyTasks.value = updatedTasks
            saveDailyTasks(updatedTasks)
            
            addCrystals(FIRST_MESSAGE_REWARD, "first_message")
            
            // Başarım kontrolü
            checkAchievement(Achievement.FIRST_CHAT)
            
            return FIRST_MESSAGE_REWARD
        }
        return 0
    }
    
    fun completeActive30MinTask(): Int {
        val tasks = _dailyTasks.value
        if (!tasks.active30MinCompleted) {
            val updatedTasks = tasks.copy(active30MinCompleted = true)
            _dailyTasks.value = updatedTasks
            saveDailyTasks(updatedTasks)
            
            addCrystals(ACTIVE_30MIN_REWARD, "active_30min")
            return ACTIVE_30MIN_REWARD
        }
        return 0
    }
    
    fun completeProfileUpdateTask(): Int {
        val tasks = _dailyTasks.value
        if (!tasks.profileUpdateCompleted) {
            val updatedTasks = tasks.copy(profileUpdateCompleted = true)
            _dailyTasks.value = updatedTasks
            saveDailyTasks(updatedTasks)
            
            addCrystals(PROFILE_UPDATE_REWARD, "profile_update")
            return PROFILE_UPDATE_REWARD
        }
        return 0
    }
    
    // Haftalık görevler
    fun updateWeeklyProgress(type: String) {
        val tasks = _weeklyTasks.value
        val updatedTasks = when (type) {
            "match" -> {
                val newCount = tasks.matchesCount + 1
                val reward = if (newCount == 5) WEEKLY_MATCHES_REWARD else 0
                if (reward > 0) addCrystals(reward, "weekly_matches")
                
                // Başarım kontrolü
                if (newCount == 10) checkAchievement(Achievement.TENTH_MATCH)
                if (newCount == 50) checkAchievement(Achievement.SOCIAL_BUTTERFLY)
                
                tasks.copy(matchesCount = newCount)
            }
            "message" -> {
                val newCount = tasks.messagesCount + 1
                val reward = if (newCount == 10) WEEKLY_MESSAGES_REWARD else 0
                if (reward > 0) addCrystals(reward, "weekly_messages")
                
                // Başarım kontrolü
                if (newCount == 100) checkAchievement(Achievement.CHAT_MASTER)
                
                tasks.copy(messagesCount = newCount)
            }
            "chat" -> {
                val newCount = tasks.chatsCount + 1
                val reward = if (newCount == 3) WEEKLY_CHATS_REWARD else 0
                if (reward > 0) addCrystals(reward, "weekly_chats")
                
                tasks.copy(chatsCount = newCount)
            }
            else -> tasks
        }
        
        _weeklyTasks.value = updatedTasks
        saveWeeklyTasks(updatedTasks)
    }
    
    // Başarım kontrolü
    private fun checkAchievement(achievement: Achievement) {
        val currentAchievements = _achievements.value
        if (!currentAchievements.contains(achievement)) {
            val updatedAchievements = currentAchievements + achievement
            _achievements.value = updatedAchievements
            saveAchievements(updatedAchievements)
            
            // Ödül ver
            addCrystals(achievement.reward, "achievement_${achievement.id}")
            
            Log.d(TAG, "Achievement unlocked: ${achievement.title} (+${achievement.reward} crystals)")
        }
    }
    
    // Özellik satın alma fonksiyonları
    fun purchaseAuraBoost(): Boolean {
        return spendCrystals(AURA_BOOST_COST, "aura_boost")
    }
    
    fun purchaseInstantPulse(): Boolean {
        return spendCrystals(INSTANT_PULSE_COST, "instant_pulse")
    }
    
    fun purchaseMoodSignal(): Boolean {
        return spendCrystals(MOOD_SIGNAL_COST, "mood_signal")
    }
    
    fun purchaseQuickReaction(): Boolean {
        return spendCrystals(QUICK_REACTION_COST, "quick_reaction")
    }
    
    fun purchaseGoldenHour(): Boolean {
        return spendCrystals(GOLDEN_HOUR_COST, "golden_hour")
    }
    
    fun purchaseFlashEvent(): Boolean {
        return spendCrystals(FLASH_EVENT_COST, "flash_event")
    }
    
    fun purchaseProximityAlert(): Boolean {
        return spendCrystals(PROXIMITY_ALERT_COST, "proximity_alert")
    }
    
    fun purchaseVoiceIntro(): Boolean {
        return spendCrystals(VOICE_INTRO_COST, "voice_intro")
    }
    
    fun purchaseTheme(): Boolean {
        return spendCrystals(THEME_COST, "theme")
    }
    
    fun purchaseRadarEffect(): Boolean {
        return spendCrystals(RADAR_EFFECT_COST, "radar_effect")
    }
    
    // Aura paket satın alma - UPDATED PACKAGES
    fun purchaseCrystalPack(packType: String): Int {
        return when (packType) {
            "starter" -> {
                addCrystals(100, "pack_starter")
                100
            }
            "boost" -> {
                addCrystals(250, "pack_boost")
                250
            }
            "popular" -> {
                addCrystals(500, "pack_popular")
                500
            }
            "power" -> {
                addCrystals(1000, "pack_power")
                1000
            }
            "pro" -> {
                addCrystals(2500, "pack_pro")
                2500
            }
            "elite" -> {
                addCrystals(5000, "pack_elite")
                5000
            }
            // Legacy support for old pack names
            "small" -> {
                addCrystals(100, "pack_small")
                100
            }
            "medium" -> {
                addCrystals(250, "pack_medium")
                250
            }
            "large" -> {
                addCrystals(500, "pack_large")
                500
            }
            "mega" -> {
                addCrystals(1000, "pack_mega")
                1000
            }
            "ultra" -> {
                addCrystals(2500, "pack_ultra")
                2500
            }
            else -> 0
        }
    }
    
    // Günlük/haftalık reset kontrolleri
    private fun checkDailyReset() {
        val today = CrystalManager.getCurrentDate()
        val tasks = _dailyTasks.value
        
        if (tasks.lastResetDate != today) {
            // Günlük görevleri sıfırla
            val resetTasks = DailyTasks(lastResetDate = today)
            _dailyTasks.value = resetTasks
            saveDailyTasks(resetTasks)
            
            // Login streak kontrolü
            updateLoginStreak()
            
            Log.d(TAG, "Daily tasks reset for $today")
        }
    }
    
    private fun checkWeeklyReset() {
        val currentWeek = CrystalManager.getCurrentWeekStart()
        val tasks = _weeklyTasks.value
        
        if (tasks.weekStartDate != currentWeek) {
            // Haftalık görevleri sıfırla
            val resetTasks = WeeklyTasks(weekStartDate = currentWeek)
            _weeklyTasks.value = resetTasks
            saveWeeklyTasks(resetTasks)
            
            Log.d(TAG, "Weekly tasks reset for week $currentWeek")
        }
    }
    
    private fun updateLoginStreak() {
        val tasks = _weeklyTasks.value
        val newStreak = tasks.loginStreak + 1
        
        val updatedTasks = tasks.copy(loginStreak = newStreak)
        _weeklyTasks.value = updatedTasks
        saveWeeklyTasks(updatedTasks)
        
        // 7 günlük streak ödülü
        if (newStreak == 7) {
            addCrystals(WEEKLY_LOGIN_STREAK_REWARD, "login_streak_7")
            checkAchievement(Achievement.DAILY_STREAK_7)
        }
    }
    
    // Veri kaydetme/yükleme
    private fun loadDailyTasks(): DailyTasks {
        return DailyTasks(
            loginCompleted = prefs.getBoolean("daily_login", false),
            firstMatchCompleted = prefs.getBoolean("daily_first_match", false),
            firstMessageCompleted = prefs.getBoolean("daily_first_message", false),
            active30MinCompleted = prefs.getBoolean("daily_active_30min", false),
            profileUpdateCompleted = prefs.getBoolean("daily_profile_update", false),
            lastResetDate = prefs.getString("daily_reset_date", CrystalManager.getCurrentDate()) ?: CrystalManager.getCurrentDate()
        )
    }
    
    private fun saveDailyTasks(tasks: DailyTasks) {
        prefs.edit().apply {
            putBoolean("daily_login", tasks.loginCompleted)
            putBoolean("daily_first_match", tasks.firstMatchCompleted)
            putBoolean("daily_first_message", tasks.firstMessageCompleted)
            putBoolean("daily_active_30min", tasks.active30MinCompleted)
            putBoolean("daily_profile_update", tasks.profileUpdateCompleted)
            putString("daily_reset_date", tasks.lastResetDate)
            apply()
        }
    }
    
    private fun loadWeeklyTasks(): WeeklyTasks {
        return WeeklyTasks(
            loginStreak = prefs.getInt("weekly_login_streak", 0),
            matchesCount = prefs.getInt("weekly_matches", 0),
            messagesCount = prefs.getInt("weekly_messages", 0),
            chatsCount = prefs.getInt("weekly_chats", 0),
            weekStartDate = prefs.getString("weekly_reset_date", CrystalManager.getCurrentWeekStart()) ?: CrystalManager.getCurrentWeekStart()
        )
    }
    
    private fun saveWeeklyTasks(tasks: WeeklyTasks) {
        prefs.edit().apply {
            putInt("weekly_login_streak", tasks.loginStreak)
            putInt("weekly_matches", tasks.matchesCount)
            putInt("weekly_messages", tasks.messagesCount)
            putInt("weekly_chats", tasks.chatsCount)
            putString("weekly_reset_date", tasks.weekStartDate)
            apply()
        }
    }
    
    private fun loadAchievements(): Set<Achievement> {
        val achievementIds = prefs.getStringSet("achievements", emptySet()) ?: emptySet()
        return Achievement.values().filter { it.id in achievementIds }.toSet()
    }
    
    private fun saveAchievements(achievements: Set<Achievement>) {
        val achievementIds = achievements.map { it.id }.toSet()
        prefs.edit().putStringSet("achievements", achievementIds).apply()
    }
    
    private fun recordCrystalTransaction(amount: Int, source: String, type: String) {
        // İstatistik kaydetme (analytics için)
        Log.d(TAG, "Aura transaction: $type $amount from $source")
    }
}