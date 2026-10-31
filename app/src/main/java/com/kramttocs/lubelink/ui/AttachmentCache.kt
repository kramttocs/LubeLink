package com.kramttocs.lubelink.ui

import android.content.Context
import java.io.File

/** No receipts survive a new app launch or a server disconnect. */
fun clearAttachmentCache(context: Context) {
    val dir = File(context.cacheDir, "lubelink_attachments")
    dir.listFiles()?.forEach { if (it.isFile) it.delete() }
}
