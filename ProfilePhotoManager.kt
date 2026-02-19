package com.aura.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility class for managing profile photos
 * Handles saving, loading, and caching of user profile photos
 */
object ProfilePhotoManager {
    
    private const val TAG = "ProfilePhotoManager"
    private const val PROFILE_PHOTO_FILENAME = "profile_photo.jpg"
    private const val PHOTO_CACHE_SIZE = 50 // Cache up to 50 photos
    
    // In-memory cache for profile photos
    private val photoCache = mutableMapOf<String, Bitmap>()
    private val cacheAccessOrder = mutableListOf<String>()
    
    /**
     * Save current user's profile photo
     */
    fun saveCurrentUserPhoto(context: Context, bitmap: Bitmap): Boolean {
        return try {
            val file = File(context.filesDir, PROFILE_PHOTO_FILENAME)
            Log.d(TAG, "💾 Saving photo to: ${file.absolutePath}")
            
            val outputStream = FileOutputStream(file)
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.close()
            
            if (compressed) {
                Log.d(TAG, "✅ Photo saved successfully, file size: ${file.length()} bytes")
                
                // Clear cache for current user
                val userId = UserPreferences(context).getUserId()
                photoCache.remove(userId)
                cacheAccessOrder.remove(userId)
                
                true
            } else {
                Log.e(TAG, "❌ Failed to compress bitmap")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error saving profile photo", e)
            false
        }
    }
    
    /**
     * Load current user's profile photo
     */
    /**
     * Load current user's profile photo
     * THREAD SAFE: Can be called from any thread
     */
    fun loadCurrentUserPhoto(context: Context): Bitmap? {
        return try {
            val userId = UserPreferences(context).getUserId()
            Log.d(TAG, "🖼️ Loading photo for user: $userId")
            
            // Check cache first
            if (photoCache.containsKey(userId)) {
                Log.d(TAG, "✅ Photo found in cache")
                updateCacheAccess(userId)
                photoCache[userId]
            } else {
                // Load from file
                val file = File(context.filesDir, PROFILE_PHOTO_FILENAME)
                Log.d(TAG, "📁 Checking file: ${file.absolutePath}, exists: ${file.exists()}")
                
                if (file.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            Log.d(TAG, "✅ Photo loaded from file: ${bitmap.width}x${bitmap.height}")
                            cachePhoto(userId, bitmap)
                            bitmap
                        } else {
                            Log.w(TAG, "❌ Failed to decode bitmap from file")
                            null
                        }
                    } catch (e: OutOfMemoryError) {
                        Log.e(TAG, "❌ Out of memory loading photo", e)
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error loading profile photo", e)
                        null
                    }
                } else {
                    Log.d(TAG, "📁 Profile photo file does not exist")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Critical error in loadCurrentUserPhoto", e)
            null
        }
    }
    
    /**
     * Get profile photo for any user (for nearby users list)
     * Returns cached photo or loads current user photo if it's the current user
     */
    fun getUserPhoto(userHash: String): Bitmap? {
        // Check cache first
        if (photoCache.containsKey(userHash)) {
            updateCacheAccess(userHash)
            return photoCache[userHash]
        }
        
        // If this is a request for current user's photo, try to load it
        // We need context for this, so we'll return null here and handle it in the caller
        return null
    }
    
    /**
     * Get profile photo for any user with context (better version)
     */
    fun getUserPhotoWithContext(context: Context, userHash: String): Bitmap? {
        Log.d(TAG, "🔍 getUserPhotoWithContext called for hash: $userHash")
        
        // Check cache first
        if (photoCache.containsKey(userHash)) {
            Log.d(TAG, "✅ Photo found in cache for: $userHash")
            updateCacheAccess(userHash)
            return photoCache[userHash]
        }
        
        // Check if this is the current user by comparing with current user's hash
        try {
            val currentUserId = UserPreferences(context).getUserId()
            val currentUserHashBytes = BLEPacket.hashUserIdTo4Bytes(currentUserId)
            val currentUserHashHex = currentUserHashBytes.joinToString("") { "%02x".format(it) }
            
            Log.d(TAG, "🔍 Current user ID: $currentUserId")
            Log.d(TAG, "🔍 Current user hash: $currentUserHashHex")
            Log.d(TAG, "🔍 Requested hash: $userHash")
            
            // IMPROVED: Try multiple hash comparison methods
            val normalizedRequestedHash = userHash.lowercase().replace(" ", "").take(8)
            val normalizedCurrentHash = currentUserHashHex.lowercase().replace(" ", "").take(8)
            
            Log.d(TAG, "🔍 Normalized requested: $normalizedRequestedHash")
            Log.d(TAG, "🔍 Normalized current: $normalizedCurrentHash")
            
            if (normalizedRequestedHash == normalizedCurrentHash) {
                Log.d(TAG, "🎯 This is current user's photo request")
                val bitmap = loadCurrentUserPhoto(context)
                if (bitmap != null) {
                    Log.d(TAG, "✅ Current user photo loaded successfully")
                    cachePhoto(userHash, bitmap)
                    return bitmap
                } else {
                    Log.d(TAG, "❌ Current user photo not found")
                }
            } else {
                Log.d(TAG, "❌ Hash mismatch - not current user")
                Log.d(TAG, "❌ Requested: '$normalizedRequestedHash' vs Current: '$normalizedCurrentHash'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in getUserPhotoWithContext", e)
        }
        
        Log.d(TAG, "❌ No photo found for hash: $userHash")
        return null
    }
    
    /**
     * Cache a profile photo for a user
     * This would be used when receiving photos via BLE (future feature)
     */
    fun cacheUserPhoto(userHash: String, bitmap: Bitmap) {
        cachePhoto(userHash, bitmap)
    }
    
    /**
     * Check if current user has a profile photo
     */
    fun hasCurrentUserPhoto(context: Context): Boolean {
        val file = File(context.filesDir, PROFILE_PHOTO_FILENAME)
        return file.exists()
    }
    
    /**
     * Delete current user's profile photo
     */
    fun deleteCurrentUserPhoto(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, PROFILE_PHOTO_FILENAME)
            val deleted = file.delete()
            
            if (deleted) {
                val userId = UserPreferences(context).getUserId()
                photoCache.remove(userId)
                cacheAccessOrder.remove(userId)
            }
            
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting profile photo", e)
            false
        }
    }
    
    /**
     * Resize bitmap to specified dimensions while maintaining aspect ratio
     */
    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val scaleWidth = maxWidth.toFloat() / width
        val scaleHeight = maxHeight.toFloat() / height
        val scale = minOf(scaleWidth, scaleHeight)
        
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Create circular bitmap for profile photos
     */
    fun createCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint()
        val rect = android.graphics.Rect(0, 0, size, size)
        val rectF = android.graphics.RectF(rect)
        
        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = 0xff424242.toInt()
        canvas.drawOval(rectF, paint)
        
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        
        return output
    }
    
    /**
     * Get profile photo as base64 string for BLE transmission
     * THREAD SAFE: Can be called from any thread
     */
    fun getCurrentUserPhotoAsBase64(context: Context): String? {
        return try {
            // SAFETY: Ensure we're not on UI thread for file operations
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Log.w(TAG, "⚠️ getCurrentUserPhotoAsBase64 called on UI thread - this may cause ANR")
            }
            
            val bitmap = loadCurrentUserPhoto(context)
            if (bitmap != null) {
                // Resize to very small size for BLE transmission (24x24 for smaller size)
                val smallBitmap = resizeBitmap(bitmap, 24, 24)
                
                val outputStream = java.io.ByteArrayOutputStream()
                // Use lower quality for smaller size
                smallBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 30, outputStream)
                val byteArray = outputStream.toByteArray()
                
                // Safety check: if still too large, don't send
                if (byteArray.size > 2000) { // 2KB limit for raw bytes
                    Log.w(TAG, "❌ Photo still too large after compression: ${byteArray.size} bytes")
                    return null
                }
                
                val base64String = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
                Log.d(TAG, "📤 Photo encoded to base64: ${base64String.length} chars, ${byteArray.size} bytes")
                return base64String
            } else {
                Log.d(TAG, "❌ No current user photo to encode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error encoding photo to base64", e)
            null
        }
    }
    
    /**
     * Decode base64 string to bitmap and cache it
     */
    fun cachePhotoFromBase64(userHash: String, base64String: String): Boolean {
        return try {
            val byteArray = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            
            if (bitmap != null) {
                cachePhoto(userHash, bitmap)
                Log.d(TAG, "✅ Photo cached from base64 for user: $userHash")
                true
            } else {
                Log.e(TAG, "❌ Failed to decode base64 to bitmap")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error decoding base64 photo", e)
            false
        }
    }
    
    private fun cachePhoto(userHash: String, bitmap: Bitmap) {
        // Remove if already exists to update access order
        if (photoCache.containsKey(userHash)) {
            cacheAccessOrder.remove(userHash)
        }
        
        // Add to cache
        photoCache[userHash] = bitmap
        cacheAccessOrder.add(userHash)
        
        // Maintain cache size limit
        while (photoCache.size > PHOTO_CACHE_SIZE) {
            val oldestKey = cacheAccessOrder.removeAt(0)
            photoCache.remove(oldestKey)
        }
    }
    
    private fun updateCacheAccess(userHash: String) {
        cacheAccessOrder.remove(userHash)
        cacheAccessOrder.add(userHash)
    }
}