package com.aura.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Background service to keep BLE advertising active for messaging
 * Runs as foreground service to prevent system killing
 */
class AuraBackgroundService : Service() {
    
    companion object {
        private const val TAG = "AuraBackgroundService"
        private const val NOTIFICATION_ID = 1000
        private const val CHANNEL_ID = "aura_background_service"
        
        fun start(context: Context) {
            try {
                val intent = Intent(context, AuraBackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "✅ Background service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start background service", e)
            }
        }
        
        fun stop(context: Context) {
            try {
                val intent = Intent(context, AuraBackgroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "✅ Background service stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to stop background service", e)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔄 Background service created")
        
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Ensure BLE services are running
            val userPrefs = UserPreferences(this)
            if (userPrefs.getVisibilityEnabled()) {
                BleEngineManager.startAdvertising()
                BleEngineManager.ensureBackgroundScanning()
                Log.d(TAG, "📡 BLE services started in background")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in background service onCreate", e)
            stopSelf()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 Background service start command")
        
        try {
            // Ensure BLE services continue running
            val userPrefs = UserPreferences(this)
            if (userPrefs.getVisibilityEnabled()) {
                BleEngineManager.ensureBackgroundScanning()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in background service onStartCommand", e)
        }
        
        // Return START_STICKY to restart if killed
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔄 Background service destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aura Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aura running in background for messaging"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura aktif")
            .setContentText("Arka planda mesaj alımı için çalışıyor")
            .setSmallIcon(R.drawable.ic_heart)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}