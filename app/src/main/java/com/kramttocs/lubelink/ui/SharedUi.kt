package com.kramttocs.lubelink.ui

import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.kramttocs.lubelink.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.VehicleImages
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.UnreadableResponseException
import com.kramttocs.lubelink.core.Vehicle
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Free text the user types (notes, descriptions, station names and addresses, nicknames): the keyboard starts each
 * sentence with a capital letter (OD-67). Server addresses, keys and searches keep the keyboard as it is. */
internal val TextKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

internal fun safeMessage(error: Exception): String = when (error) {
    is LubeLoggerApi.ApiException -> error.message.orEmpty()
    is LubeLoggerEditorApi.EditorApiException -> error.message.orEmpty()
    is IllegalArgumentException -> error.message.orEmpty()
    is IllegalStateException -> error.message.orEmpty()
    is UnreadableResponseException -> error.message.orEmpty()
    is com.kramttocs.lubelink.core.IncompleteRecordsException -> error.message.orEmpty()
    is org.json.JSONException -> "LubeLogger sent a response LubeLink could not read. Try again; if it keeps happening, " +
        "check the server address."
    is java.net.UnknownHostException -> "Cannot find the server. Check DNS or VPN."
    is java.net.ConnectException, is java.net.NoRouteToHostException ->
        "Cannot reach the server. Check the address, your network or VPN."
    is java.net.SocketTimeoutException -> "Server timed out. Try again."
    is javax.net.ssl.SSLException -> "Secure connection failed. Check the HTTPS certificate."
    else -> "Could not load LubeLogger data. Check your connection and try again."
}

/** Shown above History, Calendar and a vehicle's overview when some record types did not load (OD-31): the records
 * shown are those that loaded, never presented as complete. [retry] reads again. */
@Composable
internal fun RecordGapNotice(load: com.kramttocs.lubelink.core.RecordsLoad, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${load.missingText} couldn't be loaded from LubeLogger, so they are missing here.",
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        TextButton(onClick = retry) { Text("Retry") }
    }
}

/** Said where reminders would be listed when they did not load (OD-24), so they are never shown as none. */
internal const val REMINDERS_NOT_LOADED = "Reminders couldn't be loaded from LubeLogger."

/** Shown above every screen when part of the Garage did not load (OD-24). Without it, missing reminders or counts
 * would look like "none". */
@Composable
internal fun GarageGapNotice(missing: Set<GarageSection>, retry: () -> Unit) {
    val text = when {
        missing.containsAll(GarageSection.entries) ->
            "Reminders, mileage and record counts couldn't be loaded from LubeLogger."
        GarageSection.REMINDERS in missing ->
            "Reminders couldn't be loaded from LubeLogger, so they are missing from Overview, Reminders and Calendar."
        else -> "Mileage and record counts couldn't be loaded from LubeLogger."
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        TextButton(onClick = retry) { Text("Retry") }
    }
}

/**
 * A switch and its label as one control: TalkBack reads the label with the state, and a tap anywhere on the row,
 * the label included, changes it. A switch beside a separate text is read as an unnamed switch.
 */
@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
                           style: TextStyle = LocalTextStyle.current) {
    Row(modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = style)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A checkbox and its label as one control, as [LabeledSwitch]. */
@Composable
internal fun LabeledCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
internal fun CenterMessage(text: String, spinning: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinning) CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text)
        }
    }
}

/**
 * Shown when the Garage cannot be loaded at all. Besides Retry (a passing outage), it always offers the
 * way out of a key or server that no longer works: Disconnect, then connect again. Settings is not
 * reachable without a loaded Garage, so this is the only route there.
 */
@Composable
internal fun ErrorPane(message: String, retry: () -> Unit, onDisconnect: () -> Unit) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Connection problem", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message)
        Spacer(Modifier.height(16.dp))
        Button(onClick = retry) { Text("Retry") }
        Spacer(Modifier.height(24.dp))
        Text("If your API key was changed or removed in LubeLogger, or the server moved, disconnect and " +
            "connect again.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Disconnect and change server/key") }
    }
    if (confirmDisconnect) DisconnectConfirmDialog(
        onConfirm = { confirmDisconnect = false; onDisconnect() },
        onDismiss = { confirmDisconnect = false })
}

/** The one Disconnect confirmation, used by Settings and by the connection-problem screen. */
@Composable
internal fun DisconnectConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LubeDialogTitle("Disconnect from LubeLogger?") },
        text = { DialogBody { Text("This removes saved Editor and Manager keys from this device, stops maintenance alerts, " +
            "and clears downloaded attachments and images. Your records on LubeLogger are unchanged. " +
            "Keep a copy of your API keys if you need them again.") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } }
    )
}

/** A glyph that only decorates what its row already says (a radio dot beside a selectable row, a record kind's shape
 * beside its name): hidden from TalkBack so it is not read on top of the row. */
@Composable
internal fun DecorativeGlyph(glyph: String, color: Color = Gold) {
    Text(glyph, color = color, modifier = Modifier.clearAndSetSemantics { })
}

/** The body of every dialog. It scrolls when large text makes it taller than the dialog, so no sentence, error or
 * control is cut off while the dialog's buttons stay in place. */
@Composable
internal fun DialogBody(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing),
        content = content)
}

/** Space after the last item of every scrolling screen, so it ends clear of the bottom navigation bar instead of
 * meeting it. */
internal val ScreenEndPadding = PaddingValues(bottom = 24.dp)

@Composable
internal fun SetupScreen(settings: SecureConfig, connected: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var server by remember { mutableStateOf(settings.baseUrl) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val checks = LocalKeyChecks.current
    val check = rememberLatestCall()
    val pending = check.busy
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Image(painter = painterResource(R.drawable.lubelink_header_logo),
            contentDescription = "LubeLink",
            modifier = Modifier.width(202.dp).height(60.dp), contentScale = ContentScale.Fit)
        Text("Connect to your LubeLogger server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        // Editing the server or key makes a check of the previous text stale: it never connects.
        OutlinedTextField(server, { server = it; check.cancel() }, label = { Text("HTTPS server") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(key, { key = it; check.cancel() }, label = { Text("Editor API key") },
            visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (error.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !pending && key.isNotBlank(),
            onClick = {
                val normalized = MaintenanceRules.allowedServerUrl(server)
                if (normalized == null) { error = "Enter a valid HTTPS server URL."; return@Button }
                error = ""
                val checked = key.trim()
                check.run({ checks.connect(normalized, checked) }, onSuccess = {
                    settings.baseUrl = normalized
                    settings.saveApiKey(checked)
                    ReminderNotificationScheduler.synchronize(context, settings)
                    key = ""
                    connected()
                }, onFailure = { error = safeMessage(it) })
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (pending) "Testing connection…" else "Connect") }
    }
}

@Composable
internal fun VehiclePicture(vehicle: Vehicle, api: VehicleImages, refreshGeneration: Int,
                           modifier: Modifier = Modifier, fixedFrame: Boolean = false) {
    // Keep the existing bitmap visible while refreshing its server copy.
    var bitmap by remember(vehicle.imageLocation, api) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(vehicle.imageLocation, api, refreshGeneration) {
        // The picture is decorative: one that cannot be read keeps what is shown (the last picture, or the
        // "Vehicle" placeholder). Another vehicle or leaving the screen cancels the load, which must stop it, so
        // cancellation is never caught here.
        suspend fun attempt(load: suspend () -> android.graphics.Bitmap?) =
            try { load() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        // Display an already-downloaded image immediately, even during manual refresh.
        bitmap = attempt { api.getCachedImage(vehicle.imageLocation) } ?: bitmap
        bitmap = attempt { api.getImage(vehicle.imageLocation, refreshGeneration) } ?: bitmap
    }
    // Full image fitting (not cropping): calculate the actual drawn bitmap rectangle
    // inside the fixed grid/list frame. A narrow Explorer image must never be zoomed
    // to match a wider truck image or clipped merely to fill the photo frame.
    // Overview uses the source image ratio, but Garage uses shared frame dimensions.
    val frameAspect = MaintenanceRules.artworkAspectRatio(bitmap?.width ?: 0, bitmap?.height ?: 0)
    BoxWithConstraints((if (fixedFrame) modifier else modifier.aspectRatio(frameAspect))
        .clip(panelShape).background(Charcoal),
        contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) {
            // A 1 dp inset avoids cutting off corners of the original bitmap.
            val fitted = MaintenanceRules.artworkContainSize(
                loaded.width, loaded.height,
                (maxWidth.value - 2f).coerceAtLeast(1f),
                (maxHeight.value - 2f).coerceAtLeast(1f)
            )
            Image(loaded.asImageBitmap(), contentDescription = "${vehicle.displayName} picture",
                modifier = Modifier.size(fitted.first.dp, fitted.second.dp),
                contentScale = ContentScale.Fit)
        } else Text("Vehicle", style = MaterialTheme.typography.labelSmall)
    }
}
