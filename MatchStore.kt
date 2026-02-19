package com.aura.link

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Persistent storage for match requests and matches using SharedPreferences + JSON
 * Now includes reactive Flow for UI updates
 */
class MatchStore(context: Context) {
    
    companion object {
        private const val TAG = "MatchStore"
        private const val PREFS_NAME = "aura_matches"
        private const val KEY_PENDING_REQUESTS = "pending_requests"
        private const val KEY_MATCHES = "matches"
    }
    
    private val context = context.applicationContext
    
    data class PendingRequest(
        val id: String = UUID.randomUUID().toString(),
        val fromUserHash: String,
        val fromGender: String,
        val timestamp: Long = System.currentTimeMillis(),
        val deviceAddress: String? = null
    )
    
    data class Match(
        val id: String = UUID.randomUUID().toString(),
        val userHash: String,
        val userName: String = "User${userHash.take(4).uppercase()}", // User name
        val gender: String,
        val matchedAt: Long = System.currentTimeMillis(),
        val deviceAddress: String? = null
    )
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Type tokens for Gson
    private val pendingRequestsType = object : TypeToken<List<PendingRequest>>() {}.type
    private val matchesType = object : TypeToken<List<Match>>() {}.type
    
    // REACTIVE FLOWS for UI updates
    private val _matchesFlow = MutableStateFlow<List<Match>>(emptyList())
    val matchesFlow: StateFlow<List<Match>> = _matchesFlow.asStateFlow()
    
    private val _pendingRequestsFlow = MutableStateFlow<List<PendingRequest>>(emptyList())
    val pendingRequestsFlow: StateFlow<List<PendingRequest>> = _pendingRequestsFlow.asStateFlow()
    
    init {
        // Initialize flows with current data
        _matchesFlow.value = getMatches()
        _pendingRequestsFlow.value = getPendingRequests()
    }
    
    /**
     * Store a new incoming match request
     */
    fun storePendingRequest(fromUserHash: String, fromGender: String, deviceAddress: String? = null): PendingRequest {
        val request = PendingRequest(
            fromUserHash = fromUserHash,
            fromGender = fromGender,
            deviceAddress = deviceAddress
        )
        
        val currentRequests = getPendingRequests().toMutableList()
        
        // Check if we already have a request from this user (deduplicate)
        val existingIndex = currentRequests.indexOfFirst { it.fromUserHash == fromUserHash }
        if (existingIndex >= 0) {
            Log.d(TAG, "Updating existing request from: $fromUserHash")
            currentRequests[existingIndex] = request
        } else {
            Log.d(TAG, "Storing new request from: $fromUserHash")
            currentRequests.add(request)
        }
        
        savePendingRequests(currentRequests)
        
        // Update reactive flow
        _pendingRequestsFlow.value = currentRequests
        
        return request
    }
    
    /**
     * Accept a pending request - move to matches and remove from pending
     */
    fun acceptRequest(requestId: String): Match? {
        val pendingRequests = getPendingRequests().toMutableList()
        val requestIndex = pendingRequests.indexOfFirst { it.id == requestId }
        
        if (requestIndex >= 0) {
            val request = pendingRequests[requestIndex]
            
            // Create match
            val match = Match(
                userHash = request.fromUserHash,
                gender = request.fromGender,
                deviceAddress = request.deviceAddress
            )
            
            // Store match
            storeMatch(match)
            
            // Remove from pending
            pendingRequests.removeAt(requestIndex)
            savePendingRequests(pendingRequests)
            
            // Update reactive flows
            _pendingRequestsFlow.value = pendingRequests
            _matchesFlow.value = getMatches()
            
            Log.d(TAG, "✅ Accepted request $requestId, created match: ${match.id}")
            return match
        } else {
            Log.w(TAG, "❌ Request not found for acceptance: $requestId")
            return null
        }
    }
    
    /**
     * Reject a pending request - remove from pending
     */
    fun rejectRequest(requestId: String): Boolean {
        val pendingRequests = getPendingRequests().toMutableList()
        val requestIndex = pendingRequests.indexOfFirst { it.id == requestId }
        
        if (requestIndex >= 0) {
            val request = pendingRequests[requestIndex]
            pendingRequests.removeAt(requestIndex)
            savePendingRequests(pendingRequests)
            
            // Update reactive flow
            _pendingRequestsFlow.value = pendingRequests
            
            Log.d(TAG, "❌ Rejected request $requestId from: ${request.fromUserHash}")
            return true
        } else {
            Log.w(TAG, "❌ Request not found for rejection: $requestId")
            return false
        }
    }
    
    /**
     * Store a successful match (when we send request and get accepted)
     * BILATERAL STORAGE: Creates deterministic match ID for both devices
     */
    fun storeMatch(match: Match) {
        val currentMatches = getMatches().toMutableList()
        
        // Check for duplicates by userHash
        val existingIndex = currentMatches.indexOfFirst { it.userHash == match.userHash }
        if (existingIndex >= 0) {
            Log.d(TAG, "📝 MatchStore: Updating existing match with: ${match.userHash} (ID: ${match.id})")
            currentMatches[existingIndex] = match
        } else {
            Log.d(TAG, "📝 MatchStore: Storing new match with: ${match.userHash} (ID: ${match.id})")
            currentMatches.add(match)
        }
        
        saveMatches(currentMatches)
        
        // Update reactive flow - CRITICAL for sender-side UI updates
        _matchesFlow.value = currentMatches
        
        Log.d(TAG, "✅ MatchStore: Match saved to storage, total matches: ${currentMatches.size}")
        Log.d(TAG, "🔄 MatchStore: Updated matches flow with ${currentMatches.size} matches")
    }
    
    /**
     * Store a match by user hash and gender (convenience method)
     * BILATERAL STORAGE: Both sender and receiver call this with same deterministic ID
     */
    fun storeMatch(userHash: String, gender: String, deviceAddress: String? = null) {
        // Create deterministic match ID
        val currentUserPrefs = UserPreferences(context)
        val currentUserId = currentUserPrefs.getUserId()
        val currentUserHash = getUserHash(currentUserId)
        
        val matchId = createDeterministicMatchId(currentUserHash, userHash)
        
        val match = Match(
            id = matchId,
            userHash = userHash,
            gender = gender,
            deviceAddress = deviceAddress
        )
        storeMatch(match)
        
        Log.d(TAG, "📝 BILATERAL: Stored match with deterministic ID: $matchId")
        Log.d(TAG, "🔄 BILATERAL: Triggered matches flow update")
    }
    
    /**
     * Create deterministic match ID from two user hashes
     * DETERMINISTIC MATCH ID: Same ID generated on both devices for same pair
     */
    private fun createDeterministicMatchId(hashA: String, hashB: String): String {
        val sortedHashes = listOf(hashA, hashB).sorted()
        val combined = "${sortedHashes[0]}:${sortedHashes[1]}"
        
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(combined.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }.take(16) // 16 char hex
        } catch (e: Exception) {
            Log.e(TAG, "Error creating deterministic match ID", e)
            UUID.randomUUID().toString()
        }
    }
    
    /**
     * Get user hash from user ID
     */
    private fun getUserHash(userId: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(userId.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }.take(16) // 16 char hex
        } catch (e: Exception) {
            Log.e(TAG, "Error creating user hash", e)
            userId.hashCode().toString()
        }
    }
    
    /**
     * Get all pending requests
     */
    fun getPendingRequests(): List<PendingRequest> {
        return try {
            val json = prefs.getString(KEY_PENDING_REQUESTS, null)
            if (json != null) {
                gson.fromJson(json, pendingRequestsType) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading pending requests", e)
            emptyList()
        }
    }
    
    /**
     * Get all matches
     */
    fun getMatches(): List<Match> {
        return try {
            val json = prefs.getString(KEY_MATCHES, null)
            if (json != null) {
                gson.fromJson(json, matchesType) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading matches", e)
            emptyList()
        }
    }
    
    /**
     * Get pending request count (for badges)
     */
    fun getPendingRequestCount(): Int {
        return getPendingRequests().size
    }
    
    /**
     * Clear all data (for testing/reset)
     */
    fun clearAll() {
        prefs.edit()
            .remove(KEY_PENDING_REQUESTS)
            .remove(KEY_MATCHES)
            .apply()
        
        // Update reactive flows
        _pendingRequestsFlow.value = emptyList()
        _matchesFlow.value = emptyList()
        
        Log.d(TAG, "🧹 Cleared all match data")
    }
    
    /**
     * Remove a match by user hash
     */
    fun removeMatch(userHashHex: String): Boolean {
        val currentMatches = getMatches().toMutableList()
        val removed = currentMatches.removeAll { it.userHash == userHashHex }
        
        if (removed) {
            saveMatches(currentMatches)
            
            // Update reactive flow
            _matchesFlow.value = currentMatches
            
            Log.d(TAG, "💔 Removed match with: $userHashHex")
        }
        
        return removed
    }
    
    /**
     * Make saveMatches public for external use
     */
    fun saveMatches(matches: List<Match>) {
        val json = gson.toJson(matches)
        prefs.edit().putString(KEY_MATCHES, json).apply()
        
        // Update reactive flow
        _matchesFlow.value = matches
        
        Log.d(TAG, "💾 Saved ${matches.size} matches")
    }
    
    // Private helper methods
    
    private fun savePendingRequests(requests: List<PendingRequest>) {
        val json = gson.toJson(requests)
        prefs.edit().putString(KEY_PENDING_REQUESTS, json).apply()
        Log.d(TAG, "💾 Saved ${requests.size} pending requests")
    }
}