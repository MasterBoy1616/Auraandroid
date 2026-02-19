package com.aura.link

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback

class GenderSelectActivity : BaseThemedActivity() {
    
    companion object {
        private const val TAG = "GenderSelectActivity"
    }
    
    private lateinit var userPrefs: UserPreferences
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gender_select)
        
        // Disable back press - user must select a gender to continue
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@GenderSelectActivity, "Please select your gender to continue", Toast.LENGTH_SHORT).show()
            }
        })
        
        try {
            userPrefs = getUserPreferences()
            
            val btnMale = findViewById<Button>(R.id.btnMale)
            val btnFemale = findViewById<Button>(R.id.btnFemale)
            
            btnMale.setOnClickListener {
                selectGender(UserPreferences.GENDER_MALE)
            }
            
            btnFemale.setOnClickListener {
                selectGender(UserPreferences.GENDER_FEMALE)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing gender selection", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun selectGender(gender: String) {
        try {
            Log.d(TAG, "Selecting gender: $gender")
            
            // Save gender and mark first launch as complete
            userPrefs.setGender(gender)
            userPrefs.setFirstLaunchComplete()
            
            // Verify the gender was saved
            val savedGender = userPrefs.getGender()
            Log.d(TAG, "Gender saved as: $savedGender")
            
            if (savedGender == gender) {
                // Successfully saved, restart app to apply theme
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } else {
                // Failed to save, show error
                Toast.makeText(this, "Failed to save gender selection. Please try again.", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting gender", e)
            Toast.makeText(this, "Error saving gender selection", Toast.LENGTH_SHORT).show()
        }
    }
}