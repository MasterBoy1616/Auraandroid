package com.aura.link

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class UserDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UserDetailActivity"
        
        // Intent extras
        const val EXTRA_USER_HASH = "extra_user_hash"
    }
    
    private lateinit var tvUserName: TextView
    private lateinit var btnMatchRequest: Button
    private var userHash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_detail)

        userHash = intent.getStringExtra(EXTRA_USER_HASH)
        Log.d(TAG, "UserDetailActivity started for user: $userHash")
        
        initViews()
    }
    
    private fun initViews() {
        tvUserName = findViewById(R.id.tvUserName)
        btnMatchRequest = findViewById(R.id.btnMatchRequest)
        // btnQuickReaction = findViewById(R.id.btnQuickReaction) // Layout'ta yok, şimdilik kaldırıyoruz
        
        // Display user info from BLE Engine cache
        userHash?.let { hash ->
            // Try to get user info from BLE Engine's nearby users
            val nearbyUsers = BleEngineManager.getInstance()?.nearbyUsersFlow?.value ?: emptyList()
            val user = nearbyUsers.find { it.userHash == hash }
            
            val userName = user?.userName ?: "User${hash.take(4).uppercase()}"
            val userGender = when (user?.gender) {
                "M" -> "👨 $userName"
                "F" -> "👩 $userName"
                else -> "👤 $userName"
            }
            
            // ENHANCED: Show mood info if available
            val moodInfo = if (user?.moodType != null && user.moodMessage != null) {
                "\n😊 Mood: ${user.moodMessage}"
            } else {
                ""
            }
            
            tvUserName.text = userGender + moodInfo
            title = userName
            
            Log.d(TAG, "📝 User info loaded: $userName, gender: ${user?.gender ?: "U"}, mood: ${user?.moodType}")
        }
        
        // Set up match request button
        btnMatchRequest.setOnClickListener {
            sendMatchRequest()
        }
        
        // Set up quick reaction button - Long press for now
        btnMatchRequest.setOnLongClickListener {
            showQuickReactionDialog()
            true
        }
    }
    
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, -300) // Ekranın ortasında, yukarıda
        toast.show()
    }
    
    private fun sendMatchRequest() {
        val hash = userHash
        if (hash == null) {
            showTopToast("Kullanıcı bilgisi bulunamadı")
            Log.e(TAG, "❌ SEND_MATCH_REQUEST: userHash is null")
            return
        }
        
        Log.d(TAG, "🚀 SEND_MATCH_REQUEST: Starting request to $hash")
        
        // Check if BLE Engine is available
        val bleEngine = BleEngineManager.getInstance()
        if (bleEngine == null) {
            showTopToast("BLE Engine hazır değil")
            Log.e(TAG, "❌ SEND_MATCH_REQUEST: BLE Engine is null")
            return
        }
        
        // Send match request via BLE Engine
        BleEngineManager.enqueueMatchRequest(hash)
        
        showTopToast("Eşleşme isteği gönderildi!")
        Log.d(TAG, "📤 SEND_MATCH_REQUEST: Match request sent to: $hash")
        
        // Close activity after sending request
        finish()
    }
    
    private fun showQuickReactionDialog() {
        val hash = userHash ?: return
        
        val reactions = arrayOf(
            "👋 Selam!",
            "😊 Merhaba!",
            "🔥 Harika profil!",
            "💫 İlginç!",
            "🎵 Müzik zevkin süper!",
            "📚 Kitap önerir misin?",
            "☕ Kahve içelim mi?",
            "🌟 Tanışalım!"
        )
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Quick Reaction Gönder")
            .setItems(reactions) { _, which ->
                val selectedReaction = reactions[which]
                val parts = selectedReaction.split(" ", limit = 2)
                val emoji = parts[0]
                val message = if (parts.size > 1) parts[1] else ""
                
                sendQuickReaction(hash, emoji, message)
            }
            .setNegativeButton("İptal", null)
            .show()
    }
    
    private fun sendQuickReaction(targetHash: String, emoji: String, message: String) {
        Log.d(TAG, "😊 QUICK_REACTION: Sending $emoji to $targetHash")
        
        // ENHANCED: Send quick reaction via BLE Engine with proper formatting
        val bleEngine = BleEngineManager.getInstance()
        if (bleEngine != null) {
            // Send as special chat message with reaction prefix
            val reactionMessage = "🎯REACTION:$emoji:$message"
            bleEngine.enqueueChat(targetHash, reactionMessage)
            
            showTopToast("$emoji Quick Reaction gönderildi!")
            Log.d(TAG, "😊 QUICK_REACTION: Sent successfully via BLE")
        } else {
            showTopToast("BLE Engine hazır değil")
            Log.e(TAG, "❌ QUICK_REACTION: BLE Engine not available")
        }
        
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "UserDetailActivity destroyed")
    }
}