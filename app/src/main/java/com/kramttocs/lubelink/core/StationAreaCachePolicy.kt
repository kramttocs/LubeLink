package com.kramttocs.lubelink.core

/** Avoid refetching the same place while allowing explicit searches at a NEW map center.
 * All distances are meters and times are milliseconds; no device data is stored. */
object StationAreaCachePolicy {
    fun reuse(ageMs: Long, distanceMeters: Float, forceRefresh: Boolean): Boolean {
        if (ageMs !in 0L..600_000L || !distanceMeters.isFinite() || distanceMeters < 0f) return false
        return if (forceRefresh) ageMs <= 30_000L && distanceMeters <= 75f
        else distanceMeters <= 650f
    }
}
