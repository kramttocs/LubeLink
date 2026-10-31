package com.kramttocs.lubelink.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerApi
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** An attachment path outside LubeLogger's documents and images folders is refused before any request, with
 * the real client against the synthetic server. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class AttachmentPathTest {
    private val requested = CopyOnWriteArrayList<String>()
    private val server = ScreenshotServer(override = { path -> requested += path; null })
    private val api = LubeLoggerApi(server.baseUrl, ScreenshotGarage.EDITOR_KEY)

    @After fun close() = server.close()

    private fun refused(path: String) = runBlocking {
        try {
            api.downloadAttachment(path)
            fail("$path was fetched")
        } catch (e: LubeLoggerApi.ApiException) {
            assertEquals("LubeLink cannot open this attachment safely. Open it in LubeLogger's web page.", e.message)
        }
    }

    @Test fun pathsOutsideTheAttachmentFoldersAreNeverRequested() {
        listOf("/api/vehicles", "/documents/../api/vehicles", "/documents/%2e%2e/api/vehicles",
            "/documents/%252e%252e/x", "/images/a%2Fb.png", "/documents/a\\b.pdf", "/documents/receipt.pdf?x=1",
            "/documents/receipt.pdf#top", "https://elsewhere.invalid/documents/receipt.pdf", "documents/receipt.pdf",
            "/documents/").forEach(::refused)
        assertTrue("Nothing reached the server: $requested", requested.isEmpty())
    }

    @Test fun anAttachmentInsideTheFoldersIsRequested() {
        runBlocking { runCatching { api.downloadAttachment("/documents/Synthetic receipt (1).pdf") } }
        assertEquals(listOf("/documents/Synthetic%20receipt%20(1).pdf"), requested.toList())
    }
}
