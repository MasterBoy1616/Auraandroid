package com.aura.link

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : BaseThemedActivity() {
    
    companion object {
        private const val TAG = "SplashActivity"
    }
    
    private lateinit var tvAuraLogo: TextView
    private lateinit var tvTagline: TextView
    private lateinit var userPrefs: UserPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        try {
            userPrefs = UserPreferences(this)
            tvAuraLogo = findViewById(R.id.tvAuraLogo)
            tvTagline = findViewById(R.id.tvTagline)
            
            // Start animations
            startSplashAnimations()
            
            // Navigate after 3 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                navigateToNextScreen()
            }, 3000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            // Fallback: go directly to gender selection
            navigateToGenderSelection()
        }
    }
    
    private fun startSplashAnimations() {
        try {
            // Logo fade in animation
            tvAuraLogo.alpha = 0f
            tvAuraLogo.animate()
                .alpha(1f)
                .setDuration(1000)
                .setStartDelay(300)
                .start()
            
            // Tagline fade in animation
            tvTagline.alpha = 0f
            tvTagline.animate()
                .alpha(0.8f)
                .setDuration(800)
                .setStartDelay(800)
                .start()
            
            Log.d(TAG, "✨ Splash animations started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting animations", e)
        }
    }
    
    private fun navigateToNextScreen() {
        try {
            val hasGender = !userPrefs.getGender().isNullOrEmpty()
            val isFirstLaunch = userPrefs.isFirstLaunch()
            
            Log.d(TAG, "Navigation check - hasGender: $hasGender, isFirstLaunch: $isFirstLaunch")
            
            val intent = if (!hasGender || isFirstLaunch) {
                Intent(this, GenderSelectActivity::class.java)
            } else {
                Intent(this, MainActivity::class.java)
            }
            
            startActivity(intent)
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to next screen", e)
            // Fallback: go to gender selection
            navigateToGenderSelection()
        }
    }
    
    private fun navigateToGenderSelection() {
        try {
            val intent = Intent(this, GenderSelectActivity::class.java)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Critical error: cannot navigate to gender selection", e)
            // Last resort: finish the app
            finish()
        }
    }
}