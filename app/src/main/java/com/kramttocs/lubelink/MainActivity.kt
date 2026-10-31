package com.kramttocs.lubelink

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.clearAttachmentCache

data class ReminderDestination(val vehicleId: VehicleId, val reminderId: ReminderId)

class MainActivity : ComponentActivity() {
    private var reminderDestination by mutableStateOf<ReminderDestination?>(null)

    private fun parseReminderIntent(intent: Intent?): ReminderDestination? {
        if (intent?.hasExtra(ReminderNotificationScheduler.EXTRA_REMINDER_ID) != true) return null
        val destination = ReminderDestination(VehicleId(intent.getIntExtra(ReminderNotificationScheduler.EXTRA_VEHICLE_ID, -1)),
            ReminderId(intent.getIntExtra(ReminderNotificationScheduler.EXTRA_REMINDER_ID, -1)))
        intent.removeExtra(ReminderNotificationScheduler.EXTRA_VEHICLE_ID)
        intent.removeExtra(ReminderNotificationScheduler.EXTRA_REMINDER_ID)
        return destination
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderDestination = parseReminderIntent(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearAttachmentCache(applicationContext)
        if (savedInstanceState == null) reminderDestination = parseReminderIntent(intent)
        val settings = SecureConfig(applicationContext)
        ReminderNotificationScheduler.synchronize(applicationContext, settings)
        setContent {
            LubeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LubeLinkApp(settings, reminderDestination) { reminderDestination = null }
                }
            }
        }
    }
}
