package com.kramttocs.lubelink.core

/** Read-only capability discovery for optional LubeLogger record fields. */
internal enum class FieldReadiness { UNKNOWN, READY, MISSING }

internal data class ConfiguredField(
    val recordType: String, val name: String, val fieldType: String, val isRequired: Boolean
)

internal data class GasStationFieldCheck(val readiness: FieldReadiness, val missing: List<String> = emptyList())

internal object RecordFieldRules {
    const val GAS_RECORD = "GasRecord"
    const val GAS_STATION = "LubeLink Gas Station"
    const val STATION_ADDRESS = "LubeLink Station Address"
    val requiredGasFields = listOf(GAS_STATION, STATION_ADDRESS)

    /** The feature requires two optional Text fields defined for GasRecord. */
    fun gasStationStatus(fields: List<ConfiguredField>): GasStationFieldCheck {
        val missing = requiredGasFields.filter { expected ->
            fields.none { it.recordType.equals(GAS_RECORD, ignoreCase = true) &&
                it.name == expected && it.fieldType.equals("Text", ignoreCase = true) && !it.isRequired }
        }
        return if (missing.isEmpty()) GasStationFieldCheck(FieldReadiness.READY)
               else GasStationFieldCheck(FieldReadiness.MISSING, missing)
    }
}
