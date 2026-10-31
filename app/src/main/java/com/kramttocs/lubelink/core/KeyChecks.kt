package com.kramttocs.lubelink.core

/**
 * The network checks behind Setup's Connect and the key dialogs in Settings (OD-42): a screen calls this
 * interface, never a client, so a test can give a dialog a check that fails, never returns or returns late.
 */
interface KeyChecks {
    /** Setup's Connect: the account name when [server] answers as LubeLogger to the Editor [key]. */
    suspend fun connect(server: String, key: String): String
    /** Returns only when [server] answers as LubeLogger with the Editor key's account. */
    suspend fun verifyEditorKey(server: String, key: String)
    /** Returns only when the Manager [key] belongs to the same LubeLogger account as [editorKey]. */
    suspend fun verifyManagerKey(server: String, key: String, editorKey: String)
    /** Returns only when Geoapify accepts [key] for the Places API. */
    suspend fun testGeoapifyKey(key: String)
}

/** The checks against the real services. */
object ServiceKeyChecks : KeyChecks {
    override suspend fun connect(server: String, key: String): String = LubeLoggerApi(server, key).checkConnection()
    override suspend fun verifyEditorKey(server: String, key: String) {
        LubeLoggerEditorApi(server, key).verifyConnection()
    }
    override suspend fun verifyManagerKey(server: String, key: String, editorKey: String) {
        LubeLoggerManagerApi(server, key).verifySameAccount(editorKey)
    }
    override suspend fun testGeoapifyKey(key: String) { FuelStationLookup.testGeoapifyKey(key) }
}
