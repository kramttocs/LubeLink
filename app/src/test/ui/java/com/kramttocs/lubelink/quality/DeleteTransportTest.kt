package com.kramttocs.lubelink.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.LubeLoggerManagerApi
import com.kramttocs.lubelink.core.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Manager client's DELETE is sent once, even when the connection carrying it was reused
 * and its reply is lost; the outcome is uncertain. Runs on the JVM (the JDK's HTTP stack) and on the emulator
 * (Android's HTTP stack), against the synthetic server. Synthetic data only.
 */
@RunWith(AndroidJUnit4::class)
class DeleteTransportTest {
    private val server = ScreenshotServer(dropDeleteReply = true)

    @After fun close() = server.close()

    @Test fun aDeleteWhoseReplyIsLostIsSentOnceAndUncertain() = runBlocking {
        val reader = LubeLoggerApi(server.baseUrl, ScreenshotGarage.EDITOR_KEY)
        val vehicle = reader.loadGarage().vehicles.first { it.id.value == 1 }
        val record = reader.loadHistory(vehicle.id, includeFuel = false).records
            .first { it.kind == RecordKind.SERVICE && it.id.value == 1 }
        val manager = LubeLoggerManagerApi(server.baseUrl, ScreenshotGarage.MANAGER_KEY)
        // prepare() reads over the same keep-alive connection the DELETE then reuses.
        val snapshot = manager.prepare(vehicle, record)
        val failure = runCatching { manager.delete(snapshot) }.exceptionOrNull()
        assertTrue("A lost reply is uncertain, got $failure", failure is LubeLoggerEditorApi.WriteUncertainException)
        assertEquals("DELETEs that reached the server", 1, server.deletes.get())
    }
}
