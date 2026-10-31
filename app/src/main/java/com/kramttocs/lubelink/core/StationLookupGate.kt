package com.kramttocs.lubelink.core

/**
 * Picker-local generations. The request itself may be blocked in Android's location or HTTP
 * stack after cancellation; late successes AND failures must not modify a different page.
 * Each action has an independent sequence, so Nearby never locks or overwrites Manual.
 * All methods are called on the Android main thread.
 */
class StationLookupGate {
    private var nearbyGeneration = 0
    private var addressGeneration = 0

    fun beginNearby(): Int = ++nearbyGeneration
    fun cancelNearby() { ++nearbyGeneration }
    fun acceptsNearby(generation: Int, nearbyVisible: Boolean): Boolean =
        nearbyVisible && generation == nearbyGeneration

    fun beginAddress(): Int = ++addressGeneration
    fun cancelAddress() { ++addressGeneration }
    fun acceptsAddress(generation: Int, manualVisible: Boolean): Boolean =
        manualVisible && generation == addressGeneration
}
