package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

class UserPreferences(context: Context) {
    
    companion object {
        private const val TAG = "UserPreferences"
        private const val PREFS_NAME = "aura_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_GENDER = "gender"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_VISIBILITY_ENABLED = "visibility_enabled"
        
        const val GENDER_MALE = "M"
        const val GENDER_FEMALE = "F"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getUserId(): String {
        return try {
            var userId = prefs.getString(KEY_USER_ID, null)
            if (userId.isNullOrEmpty()) {
                userId = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_USER_ID, userId).apply()
                Log.d(TAG, "Generated new userId: $userId")
            }
            userId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/generating userId", e)
            // Fallback: generate a new UUID without saving
            UUID.randomUUID().toString()
        }
    }
    
    fun getUserName(): String {
        return try {
            val userName = prefs.getString(KEY_USER_NAME, null)
            if (userName.isNullOrEmpty()) {
                // Generate default name based on userId
                val userId = getUserId()
                val shortId = userId.takeLast(4).uppercase()
                return "User$shortId"
            }
            userName
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user name", e)
            "User${System.currentTimeMillis().toString().takeLast(4)}"
        }
    }
    
    fun setUserName(name: String) {
        try {
            val success = prefs.edit().putString(KEY_USER_NAME, name).commit()
            Log.d(TAG, "Set user name to: $name, success: $success")
            if (!success) {
                Log.e(TAG, "Failed to save user name to SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting user name", e)
        }
    }
    
    fun getGender(): String? {
        return try {
            val gender = prefs.getString(KEY_GENDER, null)
            Log.d(TAG, "Retrieved gender: $gender")
            gender
        } catch (e: Exception) {
            Log.e(TAG, "Error getting gender", e)
            null
        }
    }
    
    fun setGender(gender: String) {
        try {
            val success = prefs.edit().putString(KEY_GENDER, gender).commit() // Use commit() for immediate write
            Log.d(TAG, "Set gender to: $gender, success: $success")
            if (!success) {
                Log.e(TAG, "Failed to save gender to SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting gender", e)
        }
    }
    
    fun isFirstLaunch(): Boolean {
        return try {
            val isFirst = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
            Log.d(TAG, "Is first launch: $isFirst")
            isFirst
        } catch (e: Exception) {
            Log.e(TAG, "Error checking first launch", e)
            true // Default to first launch if error
        }
    }
    
    fun setFirstLaunchComplete() {
        try {
            val success = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).commit() // Use commit() for immediate write
            Log.d(TAG, "Set first launch complete, success: $success")
            if (!success) {
                Log.e(TAG, "Failed to save first launch status to SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting first launch complete", e)
        }
    }
    
    fun getVisibilityEnabled(): Boolean {
        return try {
            val enabled = prefs.getBoolean(KEY_VISIBILITY_ENABLED, true) // Default to TRUE
            Log.d(TAG, "Retrieved visibility enabled: $enabled")
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error getting visibility enabled", e)
            true // Default to enabled if error
        }
    }
    
    fun setVisibilityEnabled(enabled: Boolean) {
        try {
            val success = prefs.edit().putBoolean(KEY_VISIBILITY_ENABLED, enabled).commit() // Use commit() for immediate write
            Log.d(TAG, "Set visibility enabled to: $enabled, success: $success")
            if (!success) {
                Log.e(TAG, "Failed to save visibility enabled to SharedPreferences")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting visibility enabled", e)
        }
    }
}