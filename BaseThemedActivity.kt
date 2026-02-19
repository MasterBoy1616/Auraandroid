package com.aura.link

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

abstract class BaseThemedActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "BaseThemedActivity"
    }
    
    private lateinit var userPrefs: UserPreferences
    private lateinit var spontaneousFeatures: SpontaneousFeatures
    private val handler = Handler(Looper.getMainLooper())
    
    override fun attachBaseContext(newBase: Context?) {
        // Apply language preference before activity creation
        val context = newBase?.let { AuraApp.applyLanguage(it) } ?: newBase
        super.attachBaseContext(context)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super.onCreate() and setContentView()
        userPrefs = UserPreferences(this)
        applyGenderTheme()
        
        // Force apply language again in onCreate to ensure it's applied
        val languageCode = LanguageManager.getSavedLanguage(this)
        LanguageManager.applyLanguage(this, languageCode)
        
        super.onCreate(savedInstanceState)
    }
    
    override fun onResume() {
        super.onResume()
        
        // Premium tema kontrolü ve uygulama - CRASH-SAFE
        try {
            spontaneousFeatures = SpontaneousFeatures.getInstance(this)
            
            // Premium tema aktif mi kontrol et - SAFE
            lifecycleScope.launch {
                try {
                    spontaneousFeatures.premiumThemeActive.collect { isActive ->
                        try {
                            runOnUiThread {
                                if (isActive) {
                                    val themeName = spontaneousFeatures.getCurrentTheme()
                                    Log.d(TAG, "🎨 Applying premium theme to ${this@BaseThemedActivity.javaClass.simpleName}: $themeName")
                                    applyPremiumThemeToActivity(themeName)
                                } else {
                                    Log.d(TAG, "🎨 Reverting to default theme in ${this@BaseThemedActivity.javaClass.simpleName}")
                                    revertToDefaultTheme()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error applying theme in UI thread", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error collecting premium theme state", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up premium theme observer", e)
            // Tema sistemi çalışmasa da uygulama çalışmaya devam etsin
        }
    }
    
    private fun applyGenderTheme() {
        val gender = userPrefs.getGender()
        val themeRes = when (gender) {
            UserPreferences.GENDER_MALE -> R.style.Theme_Aura_Male
            UserPreferences.GENDER_FEMALE -> R.style.Theme_Aura_Female
            else -> R.style.Theme_Aura_Male // Default to male theme
        }
        setTheme(themeRes)
    }
    
    /**
     * Premium temayı aktiviteye uygula - CRASH-SAFE
     */
    private fun applyPremiumThemeToActivity(themeName: String?) {
        if (themeName == null) return
        
        try {
            // Ana container'ı bul (farklı aktivitelerde farklı ID'ler olabilir)
            val mainContainer = findMainContainer()
            
            if (mainContainer != null) {
                Log.d(TAG, "🎨 PREMIUM_THEME: Applying '$themeName' to container")
                
                when (themeName) {
                    "Galaksi" -> {
                        // GALAKSI TEMASI
                        window.statusBarColor = 0xFF000011.toInt()
                        
                        val galaxyGradient = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                0xFF000011.toInt(), // Çok koyu mavi
                                0xFF1A0033.toInt(), // Koyu mor
                                0xFF330066.toInt()  // Mor
                            )
                        )
                        mainContainer.background = galaxyGradient
                        
                        // Text renklerini güncelle
                        updateTextColors(0xFF9966FF.toInt(), 0xFF6699FF.toInt())
                    }
                    "Gün Batımı" -> {
                        // GÜN BATIMI TEMASI
                        window.statusBarColor = 0xFFFF4500.toInt()
                        
                        val sunsetGradient = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                0xFFFF4500.toInt(), // Parlak turuncu
                                0xFFFF6347.toInt(), // Domates kırmızısı
                                0xFFFF1493.toInt()  // Derin pembe
                            )
                        )
                        mainContainer.background = sunsetGradient
                        
                        updateTextColors(0xFFFFFFFF.toInt(), 0xFFFFD700.toInt())
                    }
                    "Okyanus" -> {
                        // OKYANUS TEMASI
                        window.statusBarColor = 0xFF006994.toInt()
                        
                        val oceanGradient = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(
                                0xFF006994.toInt(), // Koyu deniz mavisi
                                0xFF0099CC.toInt(), // Orta mavi
                                0xFF00CED1.toInt()  // Turkuaz
                            )
                        )
                        mainContainer.background = oceanGradient
                        
                        updateTextColors(0xFFFFFFFF.toInt(), 0xFF87CEEB.toInt())
                    }
                    "Ateş" -> {
                        // ATEŞ TEMASI
                        window.statusBarColor = 0xFF8B0000.toInt()
                        
                        val fireGradient = GradientDrawable(
                            GradientDrawable.Orientation.BOTTOM_TOP,
                            intArrayOf(
                                0xFF8B0000.toInt(), // Koyu kırmızı
                                0xFFDC143C.toInt(), // Crimson
                                0xFFFF4500.toInt()  // Parlak turuncu
                            )
                        )
                        mainContainer.background = fireGradient
                        
                        updateTextColors(0xFFFFFFFF.toInt(), 0xFFFFD700.toInt())
                    }
                    "Aurora" -> {
                        // AURORA TEMASI
                        window.statusBarColor = 0xFF000033.toInt()
                        mainContainer.setBackgroundColor(0xFF000033.toInt())
                        
                        // Aurora arka plan efekti başlat
                        startAuroraBackgroundEffect(mainContainer)
                        updateTextColors(0xFFFFFFFF.toInt(), 0xFF00E5FF.toInt())
                    }
                }
                
                // Container'ı yeniden çiz
                mainContainer.invalidate()
                mainContainer.requestLayout()
                
                Log.d(TAG, "✅ PREMIUM_THEME: '$themeName' applied successfully")
            } else {
                Log.w(TAG, "⚠️ PREMIUM_THEME: Main container not found in ${this.javaClass.simpleName}, skipping theme")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ PREMIUM_THEME: Error applying theme", e)
        }
    }
    
    /**
     * Varsayılan temaya dön - CRASH-SAFE
     */
    private fun revertToDefaultTheme() {
        try {
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
            
            val mainContainer = findMainContainer()
            if (mainContainer != null) {
                mainContainer.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_background))
                Log.d(TAG, "🎨 RESET: Default theme applied to ${this.javaClass.simpleName}")
            } else {
                Log.d(TAG, "🎨 RESET: No container found, skipping background reset")
            }
            
            // Varsayılan text renkleri
            updateTextColors(
                ContextCompat.getColor(this, R.color.neon_blue),
                ContextCompat.getColor(this, android.R.color.white)
            )
            
            // Aurora efektlerini durdur
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ RESET: Error reverting to default theme", e)
        }
    }
    
    /**
     * Ana container'ı bul (aktiviteye göre farklı ID'ler) - CRASH-SAFE
     */
    private fun findMainContainer(): ConstraintLayout? {
        return try {
            // Tüm olası container ID'lerini kontrol et
            val containerIds = listOf(
                R.id.mainContainer,        // MainActivity
                R.id.profileContainer,     // ProfileActivity  
                R.id.matchesContainer,     // MatchesActivity
                R.id.crystalStoreContainer // CrystalStoreActivity
            )
            
            for (containerId in containerIds) {
                try {
                    val container = findViewById<ConstraintLayout>(containerId)
                    if (container != null) {
                        Log.d(TAG, "✅ Found container: ${resources.getResourceEntryName(containerId)} in ${this.javaClass.simpleName}")
                        return container
                    }
                } catch (e: Exception) {
                    // Container ID doesn't exist in this activity, continue
                    continue
                }
            }
            
            Log.d(TAG, "⚠️ No container found for ${this.javaClass.simpleName}, skipping theme")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding main container", e)
            null
        }
    }
    
    /**
     * Text renklerini güncelle
     */
    private fun updateTextColors(primaryColor: Int, secondaryColor: Int) {
        // AURA logosu
        findViewById<TextView>(R.id.tvHeaderLogo)?.setTextColor(primaryColor)
        
        // Diğer text view'lar için genel güncelleme
        updateAllTextViews(findViewById(android.R.id.content), primaryColor, secondaryColor)
    }
    
    /**
     * Tüm TextView'ları güncelle (recursive)
     */
    private fun updateAllTextViews(view: View?, primaryColor: Int, secondaryColor: Int) {
        if (view == null) return
        
        when (view) {
            is TextView -> {
                // AURA logosu ve önemli text'ler için primary color
                if (view.id == R.id.tvHeaderLogo || 
                    view.id == R.id.tvScanStatus ||
                    view.id == R.id.tvUserName) {
                    view.setTextColor(primaryColor)
                } else {
                    // Diğer text'ler için secondary color
                    view.setTextColor(secondaryColor)
                }
            }
            is android.view.ViewGroup -> {
                // ViewGroup içindeki tüm child'ları kontrol et
                for (i in 0 until view.childCount) {
                    updateAllTextViews(view.getChildAt(i), primaryColor, secondaryColor)
                }
            }
        }
    }
    
    /**
     * Aurora arka plan efekti
     */
    private fun startAuroraBackgroundEffect(container: ConstraintLayout) {
        val backgroundColors = intArrayOf(
            0xFF000033.toInt(), // Koyu mavi
            0xFF330033.toInt(), // Koyu mor
            0xFF003300.toInt(), // Koyu yeşil
            0xFF330000.toInt(), // Koyu kırmızı
            0xFF003333.toInt()  // Koyu turkuaz
        )
        var colorIndex = 0
        
        val auroraRunnable = object : Runnable {
            override fun run() {
                try {
                    if (::spontaneousFeatures.isInitialized && 
                        spontaneousFeatures.premiumThemeActive.value && 
                        spontaneousFeatures.getCurrentTheme() == "Aurora") {
                        
                        val colorAnimator = android.animation.ValueAnimator.ofArgb(
                            backgroundColors[colorIndex],
                            backgroundColors[(colorIndex + 1) % backgroundColors.size]
                        )
                        colorAnimator.duration = 3000
                        colorAnimator.addUpdateListener { animator ->
                            val color = animator.animatedValue as Int
                            container.setBackgroundColor(color)
                        }
                        colorAnimator.start()
                        
                        colorIndex = (colorIndex + 1) % backgroundColors.size
                        handler.postDelayed(this, 4000)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Aurora effect error", e)
                }
            }
        }
        handler.post(auroraRunnable)
    }
    
    protected fun getUserPreferences(): UserPreferences {
        return userPrefs
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Handler callback'lerini temizle
        handler.removeCallbacksAndMessages(null)
    }
}