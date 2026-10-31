package com.kramttocs.lubelink.core

/**
 * Common reminders suggested for a vehicle with none (OD-52): fixed wording, the same for
 * every user. An electric vehicle leaves out the oil change (OD-58). LubeLink creates no reminders; the suggestions
 * point to LubeLogger, where reminders are managed.
 */
object ReminderSuggestions {
    private val common = listOf("Oil change", "Tire rotation", "Cabin air filter", "Brake inspection", "Wiper blades")

    fun forVehicle(vehicle: Vehicle): List<String> = if (vehicle.isElectric) common - "Oil change" else common

    /** Shown only for one vehicle whose reminders loaded and are none, never while they could not be loaded. */
    fun shows(vehicle: Vehicle?, reminders: List<MaintenanceReminder>, missing: Set<GarageSection>): Boolean =
        vehicle != null && GarageSection.REMINDERS !in missing && reminders.none { it.vehicleId == vehicle.id }
}
