package com.aura.link

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchesActivity : BaseThemedActivity(), BleEngine.BleEngineListener {
    
    companion object {
        private const val TAG = "MatchesActivity"
    }
    
    private lateinit var tabLayout: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var bottomNavigation: BottomNavigationView
    
    private lateinit var matchStore: MatchStore
    private var requestsAdapter: RequestsAdapter? = null
    private var matchesAdapter: MatchesAdapter? = null
    
    // Match request dialog management
    private var currentMatchRequestDialog: AlertDialog? = null
    private val processedMatchRequests = mutableSetOf<String>()
    
    private fun showTopToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, -300) // Ekranın ortasında, yukarıda
        toast.show()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_matches)
        
        matchStore = MatchStore(this)
        
        initViews()
        setupBottomNavigation()
        setupTabs()
        observeDataChanges() // NEW: Observe reactive flows instead of manual loading
        
        // Add BLE listener for match requests
        BleEngineManager.addListener(this)
        
        // CRITICAL: Ensure background scanning is active for match requests
        BleEngineManager.ensureBackgroundScanning()
        
        // Check if we should focus on requests tab
        val focusTab = intent.getStringExtra("focus_tab")
        if (focusTab == "requests") {
            tabLayout.selectTab(tabLayout.getTabAt(0)) // Requests tab
        }
    }
    
    override fun onResume() {
        super.onResume()
        // NO MANUAL RELOAD - reactive flows handle updates automatically
        Log.d(TAG, "▶️ onResume() - relying on reactive flows for data updates")
    }
    
    private fun initViews() {
        tabLayout = findViewById(R.id.tabLayout)
        recyclerView = findViewById(R.id.recyclerView)
        emptyStateContainer = findViewById(R.id.emptyStateContainer)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        
        // CRASH PREVENTION: Add null safety checks
        if (recyclerView == null || tabLayout == null || bottomNavigation == null) {
            Log.e(TAG, "❌ Critical views not found in layout")
            finish()
            return
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
    }
    
    private fun setupBottomNavigation() {
        bottomNavigation.selectedItemId = R.id.nav_matches
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_discover -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_matches -> {
                    // Already on matches screen
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.requests_tab)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.matches_tab)))
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showRequests()
                    1 -> showMatches()
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        
        // Default to requests tab
        showRequests()
    }
    
    /**
     * NEW: Observe reactive flows for automatic UI updates
     * This ensures sender-side UI updates when MATCH_ACCEPT is received
     */
    private fun observeDataChanges() {
        // Observe matches flow
        lifecycleScope.launch {
            matchStore.matchesFlow.collect { matches ->
                Log.d(TAG, "🔄 Matches flow updated: ${matches.size} matches")
                
                runOnUiThread {
                    if (matchesAdapter == null) {
                        matchesAdapter = MatchesAdapter(matches.toMutableList())
                    } else {
                        matchesAdapter?.updateData(matches)
                    }
                    
                    // Update current view if showing matches tab
                    if (tabLayout.selectedTabPosition == 1) {
                        showMatches()
                    }
                }
            }
        }
        
        // Observe pending requests flow
        lifecycleScope.launch {
            matchStore.pendingRequestsFlow.collect { requests ->
                Log.d(TAG, "🔄 Requests flow updated: ${requests.size} requests")
                
                runOnUiThread {
                    if (requestsAdapter == null) {
                        requestsAdapter = RequestsAdapter(requests.toMutableList()) { request, accepted ->
                            handleRequestResponse(request, accepted)
                        }
                    } else {
                        requestsAdapter?.updateData(requests)
                    }
                    
                    // Update current view if showing requests tab
                    if (tabLayout.selectedTabPosition == 0) {
                        showRequests()
                    }
                }
            }
        }
    }
    
    private fun showRequests() {
        try {
            val adapter = requestsAdapter
            val requests = adapter?.requests ?: emptyList()
            
            if (requests.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                tvEmptyTitle.text = getString(R.string.no_match_requests_yet)
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                recyclerView.adapter = adapter
            }
            
            Log.d(TAG, "📋 Showing ${requests.size} pending requests")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing requests", e)
            recyclerView.visibility = View.GONE
            emptyStateContainer.visibility = View.VISIBLE
            tvEmptyTitle.text = getString(R.string.requests_loading_error)
        }
    }
    
    private fun showMatches() {
        try {
            val adapter = matchesAdapter
            val matches = adapter?.matches ?: emptyList()
            
            if (matches.isEmpty()) {
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                tvEmptyTitle.text = getString(R.string.no_matches_yet)
            } else {
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                recyclerView.adapter = adapter
            }
            
            Log.d(TAG, "📋 Showing ${matches.size} matches")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing matches", e)
            recyclerView.visibility = View.GONE
            emptyStateContainer.visibility = View.VISIBLE
            tvEmptyTitle.text = getString(R.string.matches_loading_error)
        }
    }
    
    private fun handleRequestResponse(request: MatchStore.PendingRequest, accepted: Boolean) {
        Log.d(TAG, "🎯 HANDLE_REQUEST_RESPONSE: request=${request.id}, accepted=$accepted, fromUserHash=${request.fromUserHash}")
        
        lifecycleScope.launch {
            try {
                if (accepted) {
                    Log.d(TAG, "✅ Accepting request from: ${request.fromUserHash}")
                    val match = MatchRequestManager.acceptRequest(request.id)
                    if (match != null) {
                        Log.d(TAG, "✅ Request accepted successfully, match created: ${match.id}")
                    } else {
                        Log.e(TAG, "❌ Failed to create match after accepting request")
                    }
                } else {
                    Log.d(TAG, "❌ Rejecting request from: ${request.fromUserHash}")
                    val success = MatchRequestManager.rejectRequest(request.id)
                    if (success) {
                        Log.d(TAG, "✅ Request rejected successfully")
                    } else {
                        Log.e(TAG, "❌ Failed to reject request")
                    }
                }
                
                // NO MANUAL RELOAD - reactive flows handle updates automatically
                Log.d(TAG, "✅ Request response handled, reactive flows will update UI")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling request response", e)
                runOnUiThread {
                    showTopToast(getString(R.string.request_processing_error, e.message))
                }
            }
        }
    }
    
    // Adapter for pending requests
    private class RequestsAdapter(
        private var _requests: MutableList<MatchStore.PendingRequest>,
        private val onResponse: (MatchStore.PendingRequest, Boolean) -> Unit
    ) : RecyclerView.Adapter<RequestsAdapter.RequestViewHolder>() {
        
        // Expose data for reactive UI updates
        val requests: List<MatchStore.PendingRequest> get() = this._requests
        
        fun updateData(newRequests: List<MatchStore.PendingRequest>) {
            _requests.clear()
            _requests.addAll(newRequests)
            notifyDataSetChanged()
            Log.d("RequestsAdapter", "🔄 Updated with ${newRequests.size} requests")
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_match_request, parent, false)
            return RequestViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
            holder.bind(_requests[position], onResponse)
        }
        
        override fun getItemCount(): Int = _requests.size
        
        class RequestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvGender: TextView = itemView.findViewById(R.id.tvGender)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val btnAccept: Button = itemView.findViewById(R.id.btnAccept)
            private val btnReject: Button = itemView.findViewById(R.id.btnReject)
            
            fun bind(request: MatchStore.PendingRequest, onResponse: (MatchStore.PendingRequest, Boolean) -> Unit) {
                val genderText = when (request.fromGender.uppercase()) {
                    "M" -> itemView.context.getString(R.string.male_user)
                    "F" -> itemView.context.getString(R.string.female_user)
                    else -> itemView.context.getString(R.string.user_generic)
                }
                tvGender.text = genderText
                
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                tvTime.text = timeFormat.format(Date(request.timestamp))
                
                btnAccept.setOnClickListener {
                    onResponse(request, true)
                }
                
                btnReject.setOnClickListener {
                    onResponse(request, false)
                }
            }
        }
    }
    
    // Adapter for matches
    private class MatchesAdapter(
        private var _matches: MutableList<MatchStore.Match>
    ) : RecyclerView.Adapter<MatchesAdapter.MatchViewHolder>() {
        
        // Expose data for reactive UI updates
        val matches: List<MatchStore.Match> get() = this._matches
        
        fun updateData(newMatches: List<MatchStore.Match>) {
            _matches.clear()
            _matches.addAll(newMatches)
            notifyDataSetChanged()
            Log.d("MatchesAdapter", "🔄 Updated with ${newMatches.size} matches")
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_match, parent, false)
            return MatchViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
            holder.bind(_matches[position])
        }
        
        override fun getItemCount(): Int = _matches.size
        
        class MatchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvGender: TextView = itemView.findViewById(R.id.tvGender)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val btnChat: Button = itemView.findViewById(R.id.btnChat)
            private val btnViewPhoto: Button = itemView.findViewById(R.id.btnViewPhoto)
            private val btnMore: Button = itemView.findViewById(R.id.btnMore)
            
            fun bind(match: MatchStore.Match) {
                // Show user name and gender
                val genderIcon = when (match.gender.uppercase()) {
                    "M" -> "👨"
                    "F" -> "👩"
                    else -> "❓"
                }
                tvGender.text = "$genderIcon ${match.userName}"
                
                val timeFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                tvTime.text = timeFormat.format(Date(match.matchedAt))
                
                // Chat button
                btnChat.setOnClickListener {
                    val context = itemView.context
                    val intent = Intent(context, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_MATCH_ID, match.id)
                        putExtra(ChatActivity.EXTRA_PARTNER_HASH, match.userHash)
                        putExtra(ChatActivity.EXTRA_PARTNER_GENDER, match.gender)
                    }
                    context.startActivity(intent)
                }
                
                // View Photo button
                btnViewPhoto.setOnClickListener {
                    val context = itemView.context
                    handleViewPhoto(context, match)
                }
                
                // More actions button (Unmatch & Block)
                btnMore.setOnClickListener {
                    val context = itemView.context
                    showMoreActionsDialog(context, match)
                }
            }
            
            private fun handleViewPhoto(context: Context, match: MatchStore.Match) {
                Log.d("MatchViewHolder", "📷 Requesting photo from: ${match.userHash}")
                
                // First check if we already have the photo cached
                val cachedPhoto = ProfilePhotoManager.getUserPhotoWithContext(context, match.userHash)
                if (cachedPhoto != null) {
                    Log.d("MatchViewHolder", "✅ Photo found in cache, showing immediately")
                    showPhotoDialog(context, match, cachedPhoto)
                    return
                }
                
                // Show loading dialog
                val loadingDialog = AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.profile_photo_title))
                    .setMessage(context.getString(R.string.photo_requesting))
                    .setCancelable(true)
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .create()
                
                loadingDialog.show()
                
                // Send photo request via BLE with safety checks
                try {
                    val bleEngine = BleEngineManager.getInstance()
                    if (bleEngine != null && bleEngine.isDeviceCompatible()) {
                        Log.d("MatchViewHolder", "📷 BLE Engine ready, sending photo request to: ${match.userHash}")
                        bleEngine.enqueuePhotoRequest(match.userHash)
                        Log.d("MatchViewHolder", "📷 Photo request enqueued successfully for: ${match.userHash}")
                        
                        // Set timeout for photo response (30 seconds - increased)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (loadingDialog.isShowing) {
                                loadingDialog.dismiss()
                                
                                // Check again if photo arrived
                                val receivedPhoto = ProfilePhotoManager.getUserPhotoWithContext(context, match.userHash)
                                if (receivedPhoto != null) {
                                    Log.d("MatchViewHolder", "✅ Photo received after request")
                                    showPhotoDialog(context, match, receivedPhoto)
                                } else {
                                    Log.d("MatchViewHolder", "❌ Photo not received after 30 seconds, showing timeout message")
                                    AlertDialog.Builder(context)
                                        .setTitle(context.getString(R.string.profile_photo_title))
                                        .setMessage(context.getString(R.string.photo_not_received_message))
                                        .setPositiveButton(context.getString(R.string.ok_button), null)
                                        .show()
                                }
                            }
                        }, 30000) // 30 second timeout (increased from 15)
                        
                    } else {
                        loadingDialog.dismiss()
                        Log.e("MatchViewHolder", "❌ BLE Engine not available or not compatible")
                        Toast.makeText(context, context.getString(R.string.bluetooth_connection_unavailable), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("MatchViewHolder", "❌ Error sending photo request", e)
                    loadingDialog.dismiss()
                    Toast.makeText(context, context.getString(R.string.photo_request_send_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
            
            private fun showPhotoDialog(context: Context, match: MatchStore.Match, photo: android.graphics.Bitmap) {
                // Create custom dialog with photo
                val dialogView = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_match_request, null)
                val ivProfilePhoto = dialogView.findViewById<android.widget.ImageView>(R.id.ivProfilePhoto)
                val tvEmojiAvatar = dialogView.findViewById<android.widget.TextView>(R.id.tvEmojiAvatar)
                val tvUserName = dialogView.findViewById<android.widget.TextView>(R.id.tvUserName)
                val tvMessage = dialogView.findViewById<android.widget.TextView>(R.id.tvMessage)
                val btnAccept = dialogView.findViewById<android.widget.Button>(R.id.btnAccept)
                val btnReject = dialogView.findViewById<android.widget.Button>(R.id.btnReject)
                
                // Set photo and info
                val circularPhoto = ProfilePhotoManager.createCircularBitmap(photo)
                ivProfilePhoto.setImageBitmap(circularPhoto)
                ivProfilePhoto.visibility = android.view.View.VISIBLE
                tvEmojiAvatar.visibility = android.view.View.GONE
                
                tvUserName.text = match.userName
                tvMessage.text = context.getString(R.string.profile_photo_title)
                
                // Hide action buttons, only show close
                btnAccept.text = context.getString(R.string.close_button)
                btnReject.visibility = android.view.View.GONE
                
                val dialog = AlertDialog.Builder(context)
                    .setView(dialogView)
                    .create()
                
                btnAccept.setOnClickListener {
                    dialog.dismiss()
                }
                
                dialog.show()
            }
            
            private fun showMoreActionsDialog(context: Context, match: MatchStore.Match) {
                val options = arrayOf(
                    context.getString(R.string.unmatch_option),
                    context.getString(R.string.block_user_option)
                )
                
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.actions_title))
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> { // Unmatch
                                AlertDialog.Builder(context)
                                    .setTitle(context.getString(R.string.unmatch_title))
                                    .setMessage(context.getString(R.string.unmatch_confirmation))
                                    .setPositiveButton(context.getString(R.string.yes_button)) { _, _ ->
                                        handleUnmatch(context, match)
                                    }
                                    .setNegativeButton(context.getString(R.string.no_button), null)
                                    .show()
                            }
                            1 -> { // Block
                                AlertDialog.Builder(context)
                                    .setTitle(context.getString(R.string.block_user_title))
                                    .setMessage(context.getString(R.string.block_user_confirmation))
                                    .setPositiveButton(context.getString(R.string.yes_button)) { _, _ ->
                                        handleBlock(context, match)
                                    }
                                    .setNegativeButton(context.getString(R.string.no_button), null)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton(context.getString(R.string.cancel), null)
                    .show()
            }
            
            private fun handleUnmatch(context: Context, match: MatchStore.Match) {
                Log.d("MatchViewHolder", "💔 Unmatching with: ${match.userHash}")
                
                // Remove match locally FIRST
                val matchStore = MatchStore(context)
                matchStore.removeMatch(match.userHash)
                
                // Clear chat history
                val chatStore = ChatStore(context)
                chatStore.clearChatHistory(match.userHash)
                
                // Send unmatch message via BLE Engine Manager MULTIPLE TIMES for reliability
                try {
                    // Send unmatch message 3 times with longer delays to avoid duplicate notifications
                    BleEngineManager.enqueueUnmatch(match.userHash)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        BleEngineManager.enqueueUnmatch(match.userHash)
                    }, 2000) // Increased delay
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        BleEngineManager.enqueueUnmatch(match.userHash)
                    }, 4000) // Increased delay
                    
                    Log.d("MatchViewHolder", "💔 Unmatch message sent 3x via BLE Engine Manager (with delays)")
                } catch (e: Exception) {
                    Log.w("MatchViewHolder", "Failed to send unmatch message", e)
                }
                
                Toast.makeText(context, context.getString(R.string.match_removed), Toast.LENGTH_SHORT).show()
            }
            
            private fun handleBlock(context: Context, match: MatchStore.Match) {
                Log.d("MatchViewHolder", "🚫 Blocking: ${match.userHash}")
                
                // Add to block list
                val blockStore = BlockStore(context)
                blockStore.blockUser(match.userHash)
                
                // Remove match locally
                val matchStore = MatchStore(context)
                matchStore.removeMatch(match.userHash)
                
                // Clear chat history
                val chatStore = ChatStore(context)
                chatStore.clearChatHistory(match.userHash)
                
                // Send block message via BLE Engine Manager MULTIPLE TIMES for reliability
                try {
                    // Send block message 3 times with longer delays to avoid duplicate notifications
                    BleEngineManager.enqueueBlock(match.userHash)
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        BleEngineManager.enqueueBlock(match.userHash)
                    }, 2000) // Increased delay
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        BleEngineManager.enqueueBlock(match.userHash)
                    }, 4000) // Increased delay
                    
                    Log.d("MatchViewHolder", "🚫 Block message sent 3x via BLE Engine Manager (with delays)")
                } catch (e: Exception) {
                    Log.w("MatchViewHolder", "Failed to send block message", e)
                }
                
                Toast.makeText(context, context.getString(R.string.user_blocked), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // BleEngine.BleEngineListener implementation
    override fun onIncomingMatchRequest(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "📥 Incoming match request from: $senderHash")
            showMatchRequestDialog(senderHash)
        }
    }
    
    override fun onMatchAccepted(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "✅ Match accepted from: $senderHash")
            showTopToast(getString(R.string.match_accepted_toast))
        }
    }
    
    override fun onMatchRejected(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "❌ Match rejected from: $senderHash")
            showTopToast(getString(R.string.match_rejected_toast))
        }
    }
    
    override fun onChatMessage(senderHash: String, message: String) {
        runOnUiThread {
            Log.d(TAG, "💬 Chat message from: $senderHash")
            showTopToast(getString(R.string.new_message_received))
        }
    }
    
    override fun onPhotoReceived(senderHash: String, photoBase64: String) {
        runOnUiThread {
            Log.d(TAG, "📷 Photo received from: $senderHash")
            // Photo is automatically cached by MatchRequestManager
        }
    }
    
    override fun onPhotoRequested(senderHash: String) {
        runOnUiThread {
            Log.d(TAG, "📷 Photo requested by: $senderHash")
            // Photo request is automatically handled by MatchRequestManager
        }
    }
    
    override fun onUnmatchReceived(senderHash: String) {
        try {
            // Safety check - ensure activity is not destroyed
            if (isDestroyed || isFinishing) {
                Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring unmatch")
                return
            }
            
            runOnUiThread {
                try {
                    Log.d(TAG, "💔 Unmatch received from: $senderHash")
                    showTopToast(getString(R.string.match_cancelled))
                    
                    // Refresh matches list to remove the unmatched user
                    refreshMatches()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling unmatch in UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling unmatch", e)
        }
    }
    
    override fun onBlockReceived(senderHash: String) {
        try {
            // Safety check - ensure activity is not destroyed
            if (isDestroyed || isFinishing) {
                Log.d(TAG, "🚫 Activity destroyed/finishing, ignoring block")
                return
            }
            
            runOnUiThread {
                try {
                    Log.d(TAG, "🚫 Block received from: $senderHash")
                    showTopToast(getString(R.string.user_blocked_you))
                    
                    // Refresh matches list
                    refreshMatches()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling block in UI thread", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling block", e)
        }
    }
    
    /**
     * Refresh the current tab's data
     */
    private fun refreshMatches() {
        try {
            // Safety check - ensure activity is not destroyed
            if (isDestroyed || isFinishing) {
                Log.d(TAG, "🚫 Activity is destroyed/finishing, skipping refresh")
                return
            }
            
            // Safety check - ensure tabLayout is initialized
            if (!::tabLayout.isInitialized) {
                Log.d(TAG, "🚫 TabLayout not initialized, skipping refresh")
                return
            }
            
            when (tabLayout.selectedTabPosition) {
                0 -> showRequests() // Refresh requests tab
                1 -> showMatches()  // Refresh matches tab
                else -> Log.d(TAG, "Unknown tab position: ${tabLayout.selectedTabPosition}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error refreshing matches", e)
            // Don't crash, just log the error
        }
    }
    
    private fun showMatchRequestDialog(senderHash: String) {
        // Prevent duplicate dialogs
        if (processedMatchRequests.contains(senderHash)) {
            Log.d(TAG, "🚫 Duplicate match request dialog prevented for: $senderHash")
            return
        }
        
        // Close any existing dialog
        currentMatchRequestDialog?.dismiss()
        
        processedMatchRequests.add(senderHash)
        
        currentMatchRequestDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.match_request_dialog_title))
            .setMessage(getString(R.string.match_request_dialog_message))
            .setPositiveButton(getString(R.string.accept_match_button)) { _, _ ->
                Log.d(TAG, "🎯 MATCHES_DIALOG: User clicked ACCEPT for: $senderHash")
                
                // CRITICAL: Find the request ID and use MatchRequestManager
                val pendingRequests = MatchRequestManager.getPendingRequests()
                val request = pendingRequests.find { it.fromUserHash == senderHash }
                
                if (request != null) {
                    Log.d(TAG, "🎯 MATCHES_DIALOG: Found request ID: ${request.id}")
                    val match = MatchRequestManager.acceptRequest(request.id)
                    if (match != null) {
                        Log.d(TAG, "✅ MATCHES_DIALOG: Match created successfully: ${match.id}")
                        showTopToast(getString(R.string.match_accepted_simple_toast))
                    } else {
                        Log.e(TAG, "❌ MATCHES_DIALOG: Failed to create match")
                        showTopToast(getString(R.string.match_accept_failed))
                    }
                } else {
                    Log.e(TAG, "❌ MATCHES_DIALOG: Request not found for hash: $senderHash")
                }
                
                processedMatchRequests.remove(senderHash)
            }
            .setNegativeButton(getString(R.string.reject_match_button)) { _, _ ->
                Log.d(TAG, "🎯 MATCHES_DIALOG: User clicked REJECT for: $senderHash")
                
                // CRITICAL: Find the request ID and use MatchRequestManager
                val pendingRequests = MatchRequestManager.getPendingRequests()
                val request = pendingRequests.find { it.fromUserHash == senderHash }
                
                if (request != null) {
                    Log.d(TAG, "🎯 MATCHES_DIALOG: Found request ID: ${request.id}")
                    val success = MatchRequestManager.rejectRequest(request.id)
                    if (success) {
                        Log.d(TAG, "✅ MATCHES_DIALOG: Request rejected successfully")
                        showTopToast(getString(R.string.match_rejected_simple_toast))
                    } else {
                        Log.e(TAG, "❌ MATCHES_DIALOG: Failed to reject request")
                    }
                } else {
                    Log.e(TAG, "❌ MATCHES_DIALOG: Request not found for hash: $senderHash")
                }
                
                processedMatchRequests.remove(senderHash)
            }
            .setOnDismissListener {
                processedMatchRequests.remove(senderHash)
                currentMatchRequestDialog = null
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Remove listener to prevent memory leaks
        BleEngineManager.removeListener(this)
    }
}