package com.kramttocs.lubelink.core

import androidx.core.content.edit
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local per-vehicle display preferences the Garage needs; production is [SecureConfig]. */
interface VehiclePreferences {
    fun nicknameFor(vehicleId: VehicleId): String
    fun isVehicleHidden(vehicleId: VehicleId): Boolean
}

/** The key is kept in Android Keystore; prefs contain only the AES-GCM encrypted API key. */
class SecureConfig(context: Context) : VehiclePreferences {
    private val prefs = StoredFormats.prefs(context, StoredFormat.SETTINGS)
    private val alias = "lubelink_api_key_encryption_v1"

    // Every read tolerates a value of the wrong type under its key (a damaged file): it reads as the default, never
    // stopping the app. Only the app writes this file, each key with its own type.
    private fun text(key: String, default: String?): String? = runCatching { prefs.getString(key, default) }.getOrDefault(default)
    private fun flag(key: String, default: Boolean): Boolean = runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)
    private fun number(key: String, default: Int): Int = runCatching { prefs.getInt(key, default) }.getOrDefault(default)

    /**
     * One-time, local-only migration from legacy credentials. Prefer the explicitly
     * configured Editor key and drop the superseded Viewer ciphertext. Both
     * ciphertexts were encrypted with the same Android Keystore key.
     * Existing Viewer-only installs remain read-only until
     * an Editor key is entered in Settings.
     */
    init {
        if (prefs.contains("encrypted_editor_key")) {
            val oldEditor = readEncryptedKey("encrypted_editor_key")
            if (!oldEditor.isNullOrBlank()) {
                val ciphertext = text("encrypted_editor_key", null)
                if (!ciphertext.isNullOrBlank()) {
                    prefs.edit {
                        putString("encrypted_key", ciphertext)
                        putBoolean("primary_key_is_editor", true)
                        remove("encrypted_editor_key")
                    }
                    bumpCredentialEpoch()
                }
            }
        }
    }
    enum class Startup { LAST, GARAGE, FAVORITE }
    /** Controls only the local all-vehicle Garage presentation. */
    enum class GarageLayout { LIST, GRID }
    var garageLayout: GarageLayout
        get() = runCatching { GarageLayout.valueOf(text("garage_layout", GarageLayout.GRID.name).orEmpty()) }
            .getOrDefault(GarageLayout.GRID)
        set(value) { prefs.edit { putString("garage_layout", value.name) } }
    /** Reminder suggestions on Reminders (OD-59); on unless turned off, a missing or unreadable value reads as on. */
    var suggestReminders: Boolean
        get() = flag("suggest_reminders", true)
        set(value) { prefs.edit { putBoolean("suggest_reminders", value) } }
    /** Device-local look (OD-53); a missing or unknown value reads as Rounded. */
    var cornerStyle: CornerStyle
        get() = CornerStyle.of(text("corner_style", null))
        set(value) { prefs.edit { putString("corner_style", value.code) } }
    var baseUrl: String
        get() = text("server", "").orEmpty()
        set(value) {
            if (value == baseUrl) return
            prefs.edit { putString("server", value) }
            bumpCredentialEpoch()
        }

    /**
     * Changes whenever the stored server or primary key changes. A reminder check compares it, instead
     * of decrypting the key with the Keystore, inside its locked step.
     */
    val credentialEpoch: Long get() = runCatching { prefs.getLong("credential_epoch", 0) }.getOrDefault(0)
    private fun bumpCredentialEpoch() = prefs.edit { putLong("credential_epoch", credentialEpoch + 1) }
    /** Stored as the bare number under the existing keys; -1 (never set) reads as null. */
    var lastVehicleId: VehicleId?
        get() = VehicleId(number("last_vehicle", -1)).takeIf { it.isValid }
        set(value) { prefs.edit { putInt("last_vehicle", value?.value ?: -1) } }
    var favoriteVehicleId: VehicleId?
        get() = VehicleId(number("favorite_vehicle", -1)).takeIf { it.isValid }
        set(value) { prefs.edit { putInt("favorite_vehicle", value?.value ?: -1) } }
    var startup: Startup
        get() = runCatching { Startup.valueOf(text("startup", Startup.LAST.name).orEmpty()) }
            .getOrDefault(Startup.LAST)
        set(value) { prefs.edit { putString("startup", value.name) } }
    var includeFuel: Boolean
        get() = flag("include_fuel", true)
        set(value) { prefs.edit { putBoolean("include_fuel", value) } }

    /**
     * Per-server, per-vehicle local display aliases. No vehicle or credential is sent
     * to a third party, and changing Viewer -> Editor key keeps these preferences.
     * Names deliberately are NOT treated as remotely synchronized vehicle metadata.
     */
    private fun nicknamePreferenceKey(vehicleId: VehicleId): String =
        MaintenanceRules.nicknamePreferenceKey(baseUrl, vehicleId)

    override fun nicknameFor(vehicleId: VehicleId): String =
        text(nicknamePreferenceKey(vehicleId), "").orEmpty()

    fun saveNickname(vehicleId: VehicleId, nickname: String) {
        val cleaned = MaintenanceRules.normalizeNickname(nickname)
        prefs.edit {
            if (cleaned.isBlank()) remove(nicknamePreferenceKey(vehicleId))
            else putString(nicknamePreferenceKey(vehicleId), cleaned)
        }
    }

    /** A local visibility preference, never a write to LubeLogger vehicle metadata. */
    override fun isVehicleHidden(vehicleId: VehicleId): Boolean =
        flag(MaintenanceRules.visibilityPreferenceKey(baseUrl, vehicleId), false)

    /** Applied at once for the current server (memory now, disk shortly after, like every setting); a
     * reminder check posting meanwhile withdraws. */
    fun setVehicleHidden(vehicleId: VehicleId, hidden: Boolean) = synchronized(ReminderAlertLock) {
        val key = MaintenanceRules.visibilityPreferenceKey(baseUrl, vehicleId)
        if (hidden) prefs.edit { putBoolean(key, true) }
        else prefs.edit { remove(key) }
        ReminderAlertChanges.changed()
    }

    var historyNewestFirst: Boolean
        get() = flag("history_newest_first", true)
        set(value) { prefs.edit { putBoolean("history_newest_first", value) } }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    /**
     * The primary connection credential is an Editor-scoped API key. The flag
     * records the user's explicit choice of an Editor key; it is NOT proof of
     * remote permissions (LubeLogger checks those for every write).
     * Changing the credential never changes server-side record permissions.
     */
    fun saveApiKey(key: String) {
        require(key.isNotBlank())
        saveEncryptedKey("encrypted_key", key)
        // A new account/key must never silently inherit delete privileges.
        removeManagerKey()
        prefs.edit {
            putBoolean("primary_key_is_editor", true)
            remove("encrypted_editor_key")
            remove("editor_sandbox_vehicle_id")
        }
        bumpCredentialEpoch()
    }

    /** Legacy Viewer-only users can still browse until they replace their key. */
    fun isEditorKeyConfigured(): Boolean = flag("primary_key_is_editor", false) &&
        !readApiKey().isNullOrBlank()

    private fun saveEncryptedKey(slot: String, key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit { putString(slot, "$iv:$body") }
    }

    fun readApiKey(): String? = readEncryptedKey("encrypted_key")

    /** Optional deletion-only credential, always bound to the current Editor/server setup. */
    fun readManagerKey(): String? = if (isEditorKeyConfigured())
        readEncryptedKey("encrypted_manager_key")?.takeIf { it.isNotBlank() }
    else null

    fun isManagerKeyConfigured(): Boolean = !readManagerKey().isNullOrBlank()

    fun saveManagerKey(key: String) {
        val value = key.trim()
        require(isEditorKeyConfigured() && value.isNotBlank() && value.length <= 512 && value.none { it.isWhitespace() })
        saveEncryptedKey("encrypted_manager_key", value)
    }

    fun removeManagerKey() { prefs.edit { remove("encrypted_manager_key") } }


    /** Optional user-owned Places API credential, distinct from the LubeLogger Editor key. */
    fun readGeoapifyKey(): String? = readEncryptedKey("encrypted_geoapify_key")?.takeIf { it.isNotBlank() }

    fun saveGeoapifyKey(key: String) {
        val cleaned = key.trim()
        require(cleaned.isNotBlank() && cleaned.length <= 512 && cleaned.none { it.isWhitespace() })
        saveEncryptedKey("encrypted_geoapify_key", cleaned)
    }

    fun removeGeoapifyKey() {
        prefs.edit { remove("encrypted_geoapify_key") }
    }

    private fun readEncryptedKey(slot: String): String? {
        val data = text(slot, null) ?: return null
        return runCatching {
            val parts = data.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun disconnect() {
        prefs.edit {
            remove("encrypted_key")
            remove("encrypted_editor_key")
            remove("encrypted_manager_key")
            remove("primary_key_is_editor")
            remove("editor_sandbox_vehicle_id")
        }
        bumpCredentialEpoch()
    }
}
