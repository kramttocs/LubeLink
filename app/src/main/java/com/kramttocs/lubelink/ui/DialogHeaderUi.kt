package com.kramttocs.lubelink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A common vehicle line within a modal header, above the gold divider. */
@Composable
internal fun LubeDialogVehicleLine(vehicleLabel: String?, onOpenVehicle: (() -> Unit)? = null) {
    if (vehicleLabel.isNullOrBlank()) return
    if (onOpenVehicle != null) {
        TextButton(onClick = onOpenVehicle, modifier = Modifier.fillMaxWidth()) {
            Text(vehicleLabel, color = Gold, modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start, maxLines = 2,
                style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Open vehicle", tint = Gold)
        }
    } else {
        Text(vehicleLabel, color = Gold, maxLines = 2,
            style = MaterialTheme.typography.labelMedium)
    }
}

/** Consistent gold divider after title and any vehicle context. */
@Composable
internal fun LubeDialogTitle(title: String, maxLines: Int = Int.MAX_VALUE,
                             vehicleLabel: String? = null,
                             onOpenVehicle: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        LubeDialogVehicleLine(vehicleLabel, onOpenVehicle)
        HorizontalDivider(color = Gold, thickness = 1.dp)
    }
}
