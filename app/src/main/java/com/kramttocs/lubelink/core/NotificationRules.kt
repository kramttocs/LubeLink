package com.kramttocs.lubelink.core

import java.security.MessageDigest
import java.util.Locale

/**
 * The lowest urgency that notifies (Settings → Maintenance notifications). [code] is the value stored on the phone;
 * a missing or unknown one reads as [DUE_SOON], the default. [maxRank] is the least urgent rank it accepts.
 */
enum class AlertLevel(val code: String, val maxRank: Int) {
    DUE_SOON("DUE_SOON", 2), DUE("DUE", 1), PAST_DUE("PAST_DUE", 0);

    companion object {
        fun of(code: String?): AlertLevel = entries.firstOrNull { it.code == code } ?: DUE_SOON
    }
}

/** Pure rules: never touch the network, persistent credentials or Android APIs. */
object NotificationRules {
    // 0=Past due, 1=Very urgent, 2=Urgent, 3=Other/unknown.
    fun urgencyRank(value: String): Int = when (value.trim().lowercase(Locale.US)) {
        "pastdue" -> 0
        "veryurgent" -> 1
        "urgent" -> 2
        else -> 3
    }
    /** Return a notification rank when the reminder meets the local threshold. */
    fun eligible(urgency: String, minLevel: AlertLevel): Int? = urgencyRank(urgency).takeIf { it <= minLevel.maxRank }
    /** Notify on first discovery and on escalation; never repeat unchanged urgency. */
    fun isNewAlert(lastRank: Int?, currentRank: Int): Boolean =
        lastRank == null || currentRank < lastRank

    fun scope(server: String, secret: String): String = sha256("$server\n$secret").take(24)
    fun eventKey(vehicleId: VehicleId, reminderId: ReminderId): String = "v${vehicleId}_r$reminderId"
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
