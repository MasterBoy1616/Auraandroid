package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistent storage for blocked users using SharedPreferences
 * Blocked users are hidden from discovery and auto-rejected for match requests
 */
class BlockStore(context: Context) {
    
    companion object {
        private const val TAG = "BlockStore"
        private const val PREFS_NAME = "aura_blocked_users"
        private const val KEY_BLOCKED_HASHES = "blocked_hashes"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Block a user by their hash hex
     */
    fun blockUser(userHashHex: String) {
        val blockedHashes = getBlockedHashes().toMutableSet()
        blockedHashes.add(userHashHex)
        saveBlockedHashes(blockedHashes)
        Log.d(TAG, "🚫 Blocked user: $userHashHex")
    }
    
    /**
     * Unblock a user by their hash hex
     */
    fun unblockUser(userHashHex: String) {
        val blockedHashes = getBlockedHashes().toMutableSet()
        if (blockedHashes.remove(userHashHex)) {
            saveBlockedHashes(blockedHashes)
            Log.d(TAG, "✅ Unblocked user: $userHashHex")
        }
    }
    
    /**
     * Check if a user is blocked
     */
    fun isBlocked(userHashHex: String): Boolean {
        return getBlockedHashes().contains(userHashHex)
    }
    
    /**
     * Get all blocked user hashes
     */
    fun getBlockedHashes(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_HASHES, emptySet()) ?: emptySet()
    }
    
    /**
     * Clear all blocked users (for testing/admin purposes)
     */
    fun clearAllBlocked() {
        prefs.edit().remove(KEY_BLOCKED_HASHES).apply()
        Log.d(TAG, "🧹 Cleared all blocked users")
    }
    
    private fun saveBlockedHashes(blockedHashes: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_HASHES, blockedHashes).apply()
        Log.d(TAG, "💾 Saved ${blockedHashes.size} blocked users")
    }
}