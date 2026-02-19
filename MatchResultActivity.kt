package com.aura.link

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MatchResultActivity : BaseThemedActivity() {
    
    companion object {
        const val EXTRA_MATCH_SUCCESS = "match_success"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_SYNC_PLAY_AT = "sync_play_at"
        const val EXTRA_PARTNER_GENDER = "partner_gender"
        private const val TAG = "MatchResultActivity"
    }
    
    private lateinit var tvResult: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnContinue: Button
    private lateinit var userPrefs: UserPreferences
    private lateinit var auraSoundEngine: AuraSoundEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_match_result)
        
        try {
            userPrefs = UserPreferences(this)
            auraSoundEngine = AuraSoundEngine(this)
            
            initViews()
            handleMatchResult()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            finish()
        }
    }
    
    private fun initViews() {
        tvResult = findViewById(R.id.tvResult)
        tvMessage = findViewById(R.id.tvMessage)
        btnContinue = findViewById(R.id.btnContinue)
        
        btnContinue.setOnClickListener {
            finish()
        }
    }
    
    private fun handleMatchResult() {
        val isSuccess = intent.getBooleanExtra(EXTRA_MATCH_SUCCESS, false)
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        val syncPlayAt = intent.getLongExtra(EXTRA_SYNC_PLAY_AT, 0L)
        val partnerGender = intent.getStringExtra(EXTRA_PARTNER_GENDER)
        
        if (isSuccess) {
            showMatchSuccess()
            playMatchToneAndVibrate(syncPlayAt, partnerGender)
        } else {
            showMatchFailure()
        }
    }
    
    private fun showMatchSuccess() {
        tvResult.text = getString(R.string.match_success_title)
        tvResult.setTextColor(getColor(R.color.neon_blue))
        tvMessage.text = getString(R.string.match_success_message)
        btnContinue.text = getString(R.string.continue_text)
    }
    
    private fun showMatchFailure() {
        tvResult.text = getString(R.string.match_failed_title)
        tvResult.setTextColor(getColor(R.color.white))
        tvMessage.text = getString(R.string.match_failed_message)
        btnContinue.text = getString(R.string.try_again)
    }
    
    private fun playMatchToneAndVibrate(syncPlayAt: Long, partnerGender: String?) {
        try {
            // Determine gender for tone profile (use our own gender, not partner's)
            val gender = userPrefs.getGender().toGender()
            val outputMode = auraSoundEngine.detectOutputMode(this)
            
            // Play tone - either synced or immediate
            if (syncPlayAt > 0) {
                auraSoundEngine.scheduleMatchTone(syncPlayAt, gender, outputMode)
            } else {
                auraSoundEngine.playMatchTone(gender, outputMode)
            }
            
            // Vibrate based on output mode
            vibrate(outputMode)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error playing match tone, falling back to vibration only", e)
            // Fallback to vibration only if sound fails
            vibrate(OutputMode.SPEAKER) // Use normal vibration as fallback
        }
    }
    
    private fun vibrate(outputMode: OutputMode) {
        try {
            val vibrator = getVibrator()
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "No vibrator available")
                return
            }
            
            // Adjust vibration based on output mode
            val vibrationDuration = when (outputMode) {
                OutputMode.HEADSET -> 300L // Shorter for headset users
                OutputMode.SPEAKER -> 500L // Normal for speaker users
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(vibrationDuration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationDuration)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating", e)
            // Ignore vibration errors - not critical
        }
    }
    
    private fun getVibrator(): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting vibrator service", e)
            null
        }
    }
}