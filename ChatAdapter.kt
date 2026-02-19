package com.aura.link

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {
    
    private var messages: List<ChatStore.ChatMessage> = emptyList()
    
    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }
    
    fun updateMessages(newMessages: List<ChatStore.ChatMessage>) {
        // CRITICAL FIX: Only update if messages actually changed to prevent flickering
        if (messages.size != newMessages.size || messages != newMessages) {
            messages = newMessages
            notifyDataSetChanged()
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isFromMe) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_SENT) {
            R.layout.item_message_sent
        } else {
            R.layout.item_message_received
        }
        
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return MessageViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }
    
    override fun getItemCount(): Int = messages.size
    
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvStatus: TextView? = itemView.findViewById(R.id.tvStatus) // Only in sent messages
        
        fun bind(message: ChatStore.ChatMessage) {
            tvMessage.text = message.content
            
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            tvTime.text = timeFormat.format(Date(message.timestamp))
            
            // Show status for sent messages
            tvStatus?.let { statusView ->
                val statusText = when (message.status) {
                    ChatStore.MessageStatus.PENDING -> "⏳"
                    ChatStore.MessageStatus.DELIVERED -> "✓"
                    ChatStore.MessageStatus.READ -> "✓✓"
                    ChatStore.MessageStatus.FAILED -> "❌"
                }
                statusView.text = statusText
            }
        }
    }
}