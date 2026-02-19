package com.aura.link

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BleDeviceAdapter : RecyclerView.Adapter<BleDeviceAdapter.BleDeviceViewHolder>() {
    
    private val devices = mutableListOf<BleDevice>()
    private var onDeviceClickListener: ((BleDevice) -> Unit)? = null
    
    fun setOnDeviceClickListener(listener: (BleDevice) -> Unit) {
        onDeviceClickListener = listener
    }
    
    fun updateDevices(newDevices: List<BleDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }
    
    fun addDevice(device: BleDevice) {
        val existingIndex = devices.indexOfFirst { it.address == device.address }
        if (existingIndex >= 0) {
            devices[existingIndex] = device
            notifyItemChanged(existingIndex)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }
    
    fun clearDevices() {
        devices.clear()
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BleDeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ble_device, parent, false)
        return BleDeviceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: BleDeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.btnConnect.setOnClickListener {
            onDeviceClickListener?.invoke(device)
        }
    }
    
    override fun getItemCount(): Int = devices.size
    
    class BleDeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGenderIcon: TextView = itemView.findViewById(R.id.tvGenderIcon)
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvGender: TextView = itemView.findViewById(R.id.tvGender)
        private val tvProximity: TextView = itemView.findViewById(R.id.tvProximity)
        val btnConnect: Button = itemView.findViewById(R.id.btnConnect)
        
        fun bind(device: BleDevice) {
            tvDeviceName.text = device.getDisplayName(itemView.context)
            
            // Set gender icon and text
            when (device.gender) {
                "M" -> {
                    tvGenderIcon.text = "👨"
                    tvGender.text = itemView.context.getString(R.string.male_gender)
                }
                "F" -> {
                    tvGenderIcon.text = "👩"
                    tvGender.text = itemView.context.getString(R.string.female_gender)
                }
                else -> {
                    tvGenderIcon.text = "👤"
                    tvGender.text = itemView.context.getString(R.string.unknown_gender)
                }
            }
            
            // Set proximity based on RSSI
            val proximity = when {
                device.rssi > -50 -> itemView.context.getString(R.string.very_close_distance)
                device.rssi > -70 -> itemView.context.getString(R.string.close_distance)
                device.rssi > -90 -> itemView.context.getString(R.string.medium_distance)
                else -> itemView.context.getString(R.string.far_distance)
            }
            tvProximity.text = proximity
        }
    }
}