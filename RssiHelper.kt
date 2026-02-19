package com.aura.link

import android.content.Context

/**
 * RSSI değerlerini kullanıcı dostu mesafe göstergelerine çeviren yardımcı sınıf
 */
object RssiHelper {
    
    /**
     * RSSI değerini mesafe göstergesine çevirir
     * @param rssi RSSI değeri (negatif sayı)
     * @param context Context for string resources
     * @return Mesafe göstergesi string'i
     */
    fun rssiToDistanceText(rssi: Int, context: Context): String {
        return when {
            rssi >= -40 -> context.getString(R.string.very_close_distance)
            rssi >= -55 -> context.getString(R.string.close_distance)
            rssi >= -70 -> context.getString(R.string.medium_distance)
            rssi >= -85 -> context.getString(R.string.far_distance)
            else -> context.getString(R.string.far_distance) // Very far - use far for now
        }
    }
    
    /**
     * RSSI değerini mesafe göstergesine çevirir (backward compatibility)
     * @param rssi RSSI değeri (negatif sayı)
     * @return Mesafe göstergesi string'i (English fallback)
     */
    fun rssiToDistanceText(rssi: Int): String {
        return when {
            rssi >= -40 -> "Very Close"
            rssi >= -55 -> "Close"
            rssi >= -70 -> "Medium"
            rssi >= -85 -> "Far"
            else -> "Very Far"
        }
    }
    
    /**
     * RSSI değerini renk koduna çevirir (UI için)
     * @param rssi RSSI değeri
     * @return Android renk resource ID'si
     */
    fun rssiToColorResource(rssi: Int): Int {
        return when {
            rssi >= -40 -> android.R.color.holo_green_dark
            rssi >= -55 -> android.R.color.holo_green_light
            rssi >= -70 -> android.R.color.holo_orange_light
            rssi >= -85 -> android.R.color.holo_red_light
            else -> android.R.color.holo_red_dark
        }
    }
    
    /**
     * RSSI değerini sinyal gücü yüzdesine çevirir
     * @param rssi RSSI değeri
     * @return 0-100 arası sinyal gücü yüzdesi
     */
    fun rssiToSignalStrength(rssi: Int): Int {
        return when {
            rssi >= -40 -> 100
            rssi >= -55 -> 80
            rssi >= -70 -> 60
            rssi >= -85 -> 40
            else -> 20
        }.coerceIn(0, 100)
    }
}