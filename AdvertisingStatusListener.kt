package com.aura.link

/**
 * Interface for listening to advertising status changes
 * This can be implemented by activities that need to update UI based on advertising state
 */
interface AdvertisingStatusListener {
    
    /**
     * Called when advertising starts successfully
     */
    fun onAdvertisingStarted()
    
    /**
     * Called when advertising stops
     */
    fun onAdvertisingStopped()
    
    /**
     * Called when advertising fails to start
     * @param errorCode The error code from AdvertiseCallback.onStartFailure
     * @param errorMessage Human-readable error message
     */
    fun onAdvertisingFailed(errorCode: Int, errorMessage: String)
}