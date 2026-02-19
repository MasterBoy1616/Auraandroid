package com.aura.link

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying nearby users from BLE Engine
 */
class NearbyUserAdapter : RecyclerView.Adapter<NearbyUserAdapter.NearbyUserViewHolder>() {
    
    private val users = mutableListOf<BleEngine.NearbyUser>()
    private var onUserClickListener: ((String) -> Unit)? = null
    
    fun setOnUserClickListener(listener: (String) -> Unit) {
        onUserClickListener = listener
    }
    
    fun updateUsers(newUsers: List<BleEngine.NearbyUser>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }
    
    fun clearUsers() {
        users.clear()
        notifyDataSetChanged()
    }
    
    fun removeUser(userHash: String) {
        val index = users.indexOfFirst { it.userHash == userHash }
        if (index >= 0) {
            users.removeAt(index)
            notifyItemRemoved(index)
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NearbyUserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ble_device, parent, false)
        return NearbyUserViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: NearbyUserViewHolder, position: Int) {
        val user = users[position]
        holder.bind(user)
    }
    
    override fun getItemCount(): Int = users.size
    
    inner class NearbyUserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivProfilePhoto: ImageView = itemView.findViewById(R.id.ivProfilePhoto)
        private val tvEmojiAvatar: TextView = itemView.findViewById(R.id.tvEmojiAvatar)
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvGender: TextView = itemView.findViewById(R.id.tvGender)
        private val tvProximity: TextView = itemView.findViewById(R.id.tvProximity)
        
        fun bind(user: BleEngine.NearbyUser) {
            // Show actual user name from BLE packet
            tvDeviceName.text = user.userName
            
            // Show gender with emoji and mood if available
            val genderText = when (user.gender) {
                "M" -> "👨 ${itemView.context.getString(R.string.male_gender)}"
                "F" -> "👩 ${itemView.context.getString(R.string.female_gender)}"
                else -> "👤 ${itemView.context.getString(R.string.unknown_gender)}"
            }
            
            // ENHANCED: Better mood display with emoji mapping
            val moodInfo = if (user.moodType != null && user.moodMessage != null) {
                val moodEmoji = when (user.moodType.uppercase()) {
                    "HAPPY" -> "😊"
                    "SAD" -> "😢"
                    "EXCITED" -> "🤩"
                    "CALM" -> "😌"
                    "ROMANTIC" -> "😍"
                    "PLAYFUL" -> "😜"
                    "MYSTERIOUS" -> "😏"
                    "CUSTOM" -> "💭"
                    else -> "😊"
                }
                " • $moodEmoji ${user.moodMessage}"
            } else {
                ""
            }
            
            tvGender.text = genderText + moodInfo
            
            // Show distance instead of raw RSSI
            tvProximity.text = RssiHelper.rssiToDistanceText(user.rssi, itemView.context)
            
            // Load profile photo or show emoji avatar
            val profilePhoto = ProfilePhotoManager.getUserPhotoWithContext(itemView.context, user.userHash)
            if (profilePhoto != null) {
                // Show profile photo
                val circularPhoto = ProfilePhotoManager.createCircularBitmap(profilePhoto)
                ivProfilePhoto.setImageBitmap(circularPhoto)
                ivProfilePhoto.visibility = View.VISIBLE
                tvEmojiAvatar.visibility = View.GONE
            } else {
                // Show emoji avatar with mood if available
                val emoji = if (user.moodType != null) {
                    // Show mood emoji if available
                    when (user.moodType) {
                        "HAPPY" -> "😊"
                        "SAD" -> "😢"
                        "EXCITED" -> "🤩"
                        "CALM" -> "😌"
                        "ROMANTIC" -> "😍"
                        else -> when (user.gender) {
                            "M" -> "👨"
                            "F" -> "👩"
                            else -> "👤"
                        }
                    }
                } else {
                    when (user.gender) {
                        "M" -> "👨"
                        "F" -> "👩"
                        else -> "👤"
                    }
                }
                
                tvEmojiAvatar.text = emoji
                tvEmojiAvatar.visibility = View.VISIBLE
                ivProfilePhoto.visibility = View.GONE
            }
            
            itemView.setOnClickListener {
                onUserClickListener?.invoke(user.userHash)
            }
        }
    }
}