package com.kramttocs.lubelink.quality

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.Attachment
import com.kramttocs.lubelink.core.AttachmentReader
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.RecordDetailsDialog
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-42: the record-details attachment download goes through [AttachmentReader], which these
 * tests answer by hand. A failed download shows its reason and can be tried again; showing another record or closing
 * the dialog cancels the download; a download that completes for a record no longer shown never opens; and a current
 * download opens its picture. Synthetic records and a generated picture; nothing leaves the test device. Runs on the
 * JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDownloadUiTest {
    @get:Rule val compose = createComposeRule()

    private fun record(id: Int, attachment: String) = ServiceRecord(RecordId(id), VehicleId(1), "2026-10-01",
        "Synthetic service $id", 1200, "10.00", "", "", listOf(Attachment(attachment, "/documents/$attachment")),
        RecordKind.SERVICE)
    private val first = record(1, "synthetic-receipt-1.png")
    private val second = record(2, "synthetic-receipt-2.png")

    private var shownRecord by mutableStateOf<ServiceRecord?>(first)

    private class Reader(val pastCancel: Boolean) : AttachmentReader {
        val downloads = HeldCalls<ByteArray>()
        override suspend fun downloadAttachment(path: String) =
            if (pastCancel) downloads.holdPastCancel(path) else downloads.hold(path)
    }

    private fun show(reader: AttachmentReader) = compose.setContent {
        LubeTheme { shownRecord?.let { RecordDetailsDialog(it, useHours = false, api = reader, onDismiss = {}) } }
    }

    private fun picture(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun previewShown(name: String) =
        compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    private fun open(name: String, reader: Reader, count: Int) {
        compose.onNodeWithText(name).performClick()
        compose.awaitCondition { reader.downloads.calls.size == count }
    }

    @Test fun aFailedDownloadShowsWhyAndCanBeTriedAgain() {
        val reader = Reader(pastCancel = false)
        show(reader)
        open("synthetic-receipt-1.png", reader, 1)
        assertTrue(shows("Opening attachment…"))
        reader.downloads.last.fail(LubeLoggerApi.ApiException("Attachment unavailable (HTTP 404)"))
        compose.awaitCondition { shows("Attachment unavailable (HTTP 404)") }
        assertFalse(shows("Opening attachment…"))
        compose.onNodeWithText("synthetic-receipt-1.png").assertIsEnabled()
        open("synthetic-receipt-1.png", reader, 2)
        assertEquals("/documents/synthetic-receipt-1.png", reader.downloads.last.argument)
    }

    @Test fun aCurrentDownloadOpensItsPicture() {
        val reader = Reader(pastCancel = false)
        show(reader)
        open("synthetic-receipt-1.png", reader, 1)
        reader.downloads.last.succeed(picture())
        compose.awaitCondition { previewShown("synthetic-receipt-1.png") }
    }

    @Test fun showingAnotherRecordCancelsTheDownload() {
        val reader = Reader(pastCancel = false)
        show(reader)
        open("synthetic-receipt-1.png", reader, 1)
        shownRecord = second
        compose.awaitCondition { reader.downloads[0].cancelled }
        assertFalse("The other record is not busy", shows("Opening attachment…"))
        open("synthetic-receipt-2.png", reader, 2)
        assertEquals("/documents/synthetic-receipt-2.png", reader.downloads.last.argument)
    }

    @Test fun aDownloadThatCompletesForARecordNoLongerShownNeverOpens() {
        val reader = Reader(pastCancel = true)
        show(reader)
        open("synthetic-receipt-1.png", reader, 1)
        shownRecord = second
        compose.waitForIdle()
        reader.downloads[0].succeed(picture())
        compose.waitForIdle()
        passTime(200)
        compose.waitForIdle()
        assertFalse("The first record's picture never opens", previewShown("synthetic-receipt-1.png"))
        assertFalse(shows("Opening attachment…"))
        compose.onNodeWithText("synthetic-receipt-2.png").assertIsEnabled()
    }

    @Test fun closingTheDialogCancelsTheDownload() {
        val reader = Reader(pastCancel = false)
        show(reader)
        open("synthetic-receipt-1.png", reader, 1)
        shownRecord = null
        compose.awaitCondition { reader.downloads.last.cancelled }
    }
}
