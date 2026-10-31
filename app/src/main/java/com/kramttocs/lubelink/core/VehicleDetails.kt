package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject

/** Reads and lists [VehicleDetails] (OD-49). */
object VehicleDetailsRules {
    /**
     * The details of one `/api/vehicles` entry, or null when any of them is missing or not in the contract's form.
     * They are only shown in the details dialog, so an unreadable one is reported there ("could not be read") and
     * never stops the Garage from loading, and never turns into a blank or 0.
     */
    internal fun read(item: JSONObject): VehicleDetails? = runCatching {
        val fields = ResponseFields("the vehicle details")
        // A date never set is JSON null from a newly set-up LubeLogger and empty text from one whose vehicles were
        // saved that way; both mean "not set". Any other text must be a yyyy-MM-dd date.
        fun date(key: String): LocalDate? = when {
            !item.has(key) -> fields.fail(key)
            item.isNull(key) || item.opt(key) == "" -> null
            else -> (item.opt(key) as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: fields.fail(key)
        }
        fun price(key: String): BigDecimal? =
            fields.decimalText(item, key).toBigDecimal().takeIf { it.signum() != 0 }?.also { if (it.signum() < 0) fields.fail(key) }
        val tags = fields.array(item, "tags").let { list ->
            (0 until list.length()).map { index -> list.opt(index) as? String ?: fields.fail("tags") }
        }
        VehicleDetails(fields.text(item, "licensePlate").trim(), fields.boolean(item, "isDiesel"),
            date("purchaseDate"), price("purchasePrice"), date("soldDate"), price("soldPrice"),
            tags.map { it.trim() }.filter { it.isNotEmpty() })
    }.getOrNull()

    /** The dialog's rows, label to value; a detail LubeLogger has no value for is left out. */
    fun rows(vehicle: Vehicle, details: VehicleDetails): List<Pair<String, String>> = buildList {
        if (details.licensePlate.isNotEmpty()) add("License plate" to MaintenanceRules.lineForDisplay(details.licensePlate))
        add("Fuel" to when {
            vehicle.isElectric -> "Electric"
            details.isDiesel -> "Diesel"
            else -> "Gasoline"
        })
        add("Odometer in" to if (vehicle.useHours) "Hours" else "Miles")
        dateAndPrice(details.purchaseDate, details.purchasePrice)?.let { add("Purchased" to it) }
        dateAndPrice(details.soldDate, details.soldPrice)?.let { add("Sold" to it) }
        if (details.tags.isNotEmpty()) add("Tags" to MaintenanceRules.lineForDisplay(details.tags.joinToString(", ")))
    }

    private fun dateAndPrice(date: LocalDate?, price: BigDecimal?): String? =
        listOfNotNull(date?.let { EditConfirmationRules.dateLabel(it.toString()) },
            price?.let { "$" + String.format(Locale.US, "%,.2f", it) })
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
