package com.aura.link

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

class AuraSoundEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "AuraSoundEngine"
        
        // Audio configuration
        private const val SAMPLE_RATE = 44100
        private const val FADE_IN_MS = 10
        private const val FADE_OUT_MS = 30
        
        // Female tone profile - softer, warmer, lower
        private const val FEMALE_STAGE1_FREQ = 440.0 // A4 - calm
        private const val FEMALE_STAGE1_DURATION = 200
        private const val FEMALE_STAGE2_FREQ = 659.0 // E5 - soft sparkle
        private const val FEMALE_STAGE2_DURATION = 250
        
        // Male tone profile - clearer, brighter, more defined
        private const val MALE_STAGE1_FREQ = 523.0 // C5 - calm but higher
        private const val MALE_STAGE1_DURATION = 150
        private const val MALE_STAGE2_FREQ = 784.0 // G5 - brighter sparkle
        private const val MALE_STAGE2_DURATION = 200
        
        // Neutral profile
        private const val NEUTRAL_STAGE1_FREQ = 480.0
        private const val NEUTRAL_STAGE1_DURATION = 175
        private const val NEUTRAL_STAGE2_FREQ = 720.0
        private const val NEUTRAL_STAGE2_DURATION = 225
        
        // Volume adjustments
        private const val HEADSET_VOLUME_REDUCTION = 0.7f // 30% reduction
        private const val SPEAKER_VOLUME = 1.0f
        
        // NOTIFICATION SOUNDS - Aura özel bildirim tonları
        
        // Eşleşme bildirimi - Yükselen melodik ton
        private const val MATCH_NOTIFICATION_FREQ1 = 523.0 // C5
        private const val MATCH_NOTIFICATION_FREQ2 = 659.0 // E5
        private const val MATCH_NOTIFICATION_FREQ3 = 784.0 // G5
        private const val MATCH_NOTIFICATION_DURATION = 120 // Her ton 120ms
        
        // Mesaj bildirimi - Çift kısa ton
        private const val MESSAGE_NOTIFICATION_FREQ = 880.0 // A5
        private const val MESSAGE_NOTIFICATION_DURATION = 80 // Kısa ve net
        private const val MESSAGE_NOTIFICATION_PAUSE = 50 // Tonlar arası bekleme
        
        // Bildirim ses seviyesi
        private const val NOTIFICATION_VOLUME = 0.6f
    }
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    fun detectOutputMode(context: Context): OutputMode {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                
                for (device in devices) {
                    when (device.type) {
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                            return OutputMode.HEADSET
                        }
                    }
                    
                    // Check for BLE audio (API 31+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                            return OutputMode.HEADSET
                        }
                    }
                }
            } else {
                // Fallback for older versions
                @Suppress("DEPRECATION")
                when {
                    audioManager.isWiredHeadsetOn -> return OutputMode.HEADSET
                    audioManager.isBluetoothA2dpOn -> return OutputMode.HEADSET
                    audioManager.isBluetoothScoOn -> return OutputMode.HEADSET
                }
            }
            
            OutputMode.SPEAKER
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting output mode", e)
            OutputMode.SPEAKER // Default fallback
        }
    }
    
    fun playMatchTone(gender: Gender, outputMode: OutputMode) {
        coroutineScope.launch {
            try {
                val (stage1Freq, stage1Duration, stage2Freq, stage2Duration) = getToneProfile(gender)
                val volumeMultiplier = getVolumeMultiplier(outputMode)
                
                playToneSequence(stage1Freq, stage1Duration, stage2Freq, stage2Duration, volumeMultiplier)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error playing match tone", e)
                // Fallback to vibration only - handled by caller
            }
        }
    }
    
    fun scheduleMatchTone(playAtElapsedRealtimeMs: Long, gender: Gender, outputMode: OutputMode) {
        coroutineScope.launch {
            try {
                val currentTime = SystemClock.elapsedRealtime()
                val delayMs = playAtElapsedRealtimeMs - currentTime
                
                if (delayMs <= 0) {
                    // Time has passed, play immediately
                    playMatchTone(gender, outputMode)
                    return@launch
                }
                
                // Wait for the scheduled time
                delay(delayMs)
                
                // Play the tone
                val (stage1Freq, stage1Duration, stage2Freq, stage2Duration) = getToneProfile(gender)
                val volumeMultiplier = getVolumeMultiplier(outputMode)
                playToneSequence(stage1Freq, stage1Duration, stage2Freq, stage2Duration, volumeMultiplier)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling match tone", e)
                // Fallback to immediate playback
                playMatchTone(gender, outputMode)
            }
        }
    }
    
    private fun getToneProfile(gender: Gender): ToneProfile {
        return when (gender) {
            Gender.FEMALE -> ToneProfile(
                FEMALE_STAGE1_FREQ, FEMALE_STAGE1_DURATION,
                FEMALE_STAGE2_FREQ, FEMALE_STAGE2_DURATION
            )
            Gender.MALE -> ToneProfile(
                MALE_STAGE1_FREQ, MALE_STAGE1_DURATION,
                MALE_STAGE2_FREQ, MALE_STAGE2_DURATION
            )
            Gender.UNKNOWN -> ToneProfile(
                NEUTRAL_STAGE1_FREQ, NEUTRAL_STAGE1_DURATION,
                NEUTRAL_STAGE2_FREQ, NEUTRAL_STAGE2_DURATION
            )
        }
    }
    
    private fun getVolumeMultiplier(outputMode: OutputMode): Float {
        return when (outputMode) {
            OutputMode.HEADSET -> HEADSET_VOLUME_REDUCTION
            OutputMode.SPEAKER -> SPEAKER_VOLUME
        }
    }
    
    private suspend fun playToneSequence(
        stage1Freq: Double, stage1Duration: Int,
        stage2Freq: Double, stage2Duration: Int,
        volumeMultiplier: Float
    ) = withContext(Dispatchers.IO) {
        var audioTrack1: AudioTrack? = null
        var audioTrack2: AudioTrack? = null
        
        try {
            // Stage 1: Calm tone
            audioTrack1 = createAndPlayTone(stage1Freq, stage1Duration, volumeMultiplier)
            
            // Wait for stage 1 to complete
            delay(stage1Duration.toLong())
            
            // Release stage 1
            audioTrack1?.stop()
            audioTrack1?.release()
            audioTrack1 = null
            
            // Stage 2: Sparkle tone
            audioTrack2 = createAndPlayTone(stage2Freq, stage2Duration, volumeMultiplier)
            
            // Wait for stage 2 to complete
            delay(stage2Duration.toLong())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in playToneSequence", e)
        } finally {
            // Cleanup
            try {
                audioTrack1?.stop()
                audioTrack1?.release()
                audioTrack2?.stop()
                audioTrack2?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack resources", e)
            }
        }
    }
    
    private fun createAndPlayTone(frequency: Double, durationMs: Int, volumeMultiplier: Float): AudioTrack? {
        return try {
            val sampleCount = (SAMPLE_RATE * durationMs / 1000.0).toInt()
            val audioData = generateSineWave(frequency, sampleCount, volumeMultiplier)
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioDataBytes = audioData.size * 2
            val bufferSize = maxOf(minBufferSize, audioDataBytes)
            
            val audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            audioTrack.write(audioData, 0, audioData.size)
            audioTrack.play()
            
            audioTrack
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AudioTrack", e)
            null
        }
    }
    
    private fun generateSineWave(frequency: Double, sampleCount: Int, volumeMultiplier: Float): ShortArray {
        val audioData = ShortArray(sampleCount)
        val fadeInSamples = minOf(sampleCount, (SAMPLE_RATE * FADE_IN_MS / 1000.0).toInt())
        val fadeOutSamples = minOf(sampleCount, (SAMPLE_RATE * FADE_OUT_MS / 1000.0).toInt())
        val maxAmplitude = (Short.MAX_VALUE * 0.8 * volumeMultiplier).toInt() // 80% of max to avoid clipping
        
        for (i in 0 until sampleCount) {
            val angle = 2.0 * PI * frequency * i / SAMPLE_RATE
            var sample = (maxAmplitude * sin(angle)).toInt()
            
            // Apply fade-in
            if (i < fadeInSamples) {
                val fadeMultiplier = i.toFloat() / fadeInSamples
                sample = (sample * fadeMultiplier).toInt()
            }
            
            // Apply fade-out
            if (i >= sampleCount - fadeOutSamples) {
                val remaining = (sampleCount - i).coerceAtLeast(0)
                val fadeMultiplier = remaining.toFloat() / fadeOutSamples
                sample = (sample * fadeMultiplier).toInt()
            }
            
            audioData[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        
        return audioData
    }
    
    private data class ToneProfile(
        val stage1Freq: Double,
        val stage1Duration: Int,
        val stage2Freq: Double,
        val stage2Duration: Int
    )
    
    // ========== NOTIFICATION SOUNDS ==========
    
    /**
     * Eşleşme bildirimi - Yükselen 3'lü melodik ton (C-E-G)
     * Aura'ya özel, pozitif ve heyecan verici
     */
    fun playMatchNotification() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🎵 Playing match notification sound")
                
                val outputMode = detectOutputMode(context)
                val volumeMultiplier = getVolumeMultiplier(outputMode) * NOTIFICATION_VOLUME
                
                // 1. ton: C5 (523 Hz)
                val audioTrack1 = createAndPlayTone(
                    MATCH_NOTIFICATION_FREQ1, 
                    MATCH_NOTIFICATION_DURATION, 
                    volumeMultiplier
                )
                delay(MATCH_NOTIFICATION_DURATION.toLong())
                audioTrack1?.stop()
                audioTrack1?.release()
                
                delay(20) // Kısa bekleme
                
                // 2. ton: E5 (659 Hz)
                val audioTrack2 = createAndPlayTone(
                    MATCH_NOTIFICATION_FREQ2, 
                    MATCH_NOTIFICATION_DURATION, 
                    volumeMultiplier
                )
                delay(MATCH_NOTIFICATION_DURATION.toLong())
                audioTrack2?.stop()
                audioTrack2?.release()
                
                delay(20) // Kısa bekleme
                
                // 3. ton: G5 (784 Hz) - En yüksek, mutlu son
                val audioTrack3 = createAndPlayTone(
                    MATCH_NOTIFICATION_FREQ3, 
                    MATCH_NOTIFICATION_DURATION + 50, // Biraz daha uzun
                    volumeMultiplier
                )
                delay((MATCH_NOTIFICATION_DURATION + 50).toLong())
                audioTrack3?.stop()
                audioTrack3?.release()
                
                Log.d(TAG, "✅ Match notification sound completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing match notification", e)
            }
        }
    }
    
    /**
     * Mesaj bildirimi - Çift kısa ton (A5-A5)
     * Dikkat çekici ama rahatsız etmeyen
     */
    fun playMessageNotification() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🎵 Playing message notification sound")
                
                val outputMode = detectOutputMode(context)
                val volumeMultiplier = getVolumeMultiplier(outputMode) * NOTIFICATION_VOLUME
                
                // 1. ton: A5 (880 Hz)
                val audioTrack1 = createAndPlayTone(
                    MESSAGE_NOTIFICATION_FREQ, 
                    MESSAGE_NOTIFICATION_DURATION, 
                    volumeMultiplier
                )
                delay(MESSAGE_NOTIFICATION_DURATION.toLong())
                audioTrack1?.stop()
                audioTrack1?.release()
                
                delay(MESSAGE_NOTIFICATION_PAUSE.toLong()) // Tonlar arası bekleme
                
                // 2. ton: A5 (880 Hz) - Aynı ton tekrar
                val audioTrack2 = createAndPlayTone(
                    MESSAGE_NOTIFICATION_FREQ, 
                    MESSAGE_NOTIFICATION_DURATION, 
                    volumeMultiplier
                )
                delay(MESSAGE_NOTIFICATION_DURATION.toLong())
                audioTrack2?.stop()
                audioTrack2?.release()
                
                Log.d(TAG, "✅ Message notification sound completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing message notification", e)
            }
        }
    }
    
    /**
     * Test notification - Basit tek ton
     */
    fun playTestNotification() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "🎵 Playing test notification sound")
                
                val outputMode = detectOutputMode(context)
                val volumeMultiplier = getVolumeMultiplier(outputMode) * NOTIFICATION_VOLUME
                
                val audioTrack = createAndPlayTone(
                    660.0, // E5
                    300,   // 300ms
                    volumeMultiplier
                )
                delay(300)
                audioTrack?.stop()
                audioTrack?.release()
                
                Log.d(TAG, "✅ Test notification sound completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing test notification", e)
            }
        }
    }
}