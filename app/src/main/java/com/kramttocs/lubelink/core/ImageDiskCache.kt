package com.kramttocs.lubelink.core

import java.io.File
import java.security.MessageDigest

/**
 * Best-effort, bounded cache for previously authenticated vehicle image bytes.
 * Files are inside Android's private cacheDir (not exported or backed up).
 * A cache entry is scoped to the server, API credential, and exact allowlisted path.
 * API keys and URLs are never written as filenames or stored in cache metadata.
 */
class ImageDiskCache(private val directory: File, private val maxBytes: Long = 48L * 1024 * 1024) {
    private val maxFileBytes = 8L * 1024 * 1024

    private fun filename(cacheKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(cacheKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".img"

    @Synchronized
    fun get(cacheKey: String): ByteArray? {
        val file = File(directory, filename(cacheKey))
        if (!file.isFile) return null
        if (file.length() !in 1..maxFileBytes) {
            file.delete()
            return null
        }
        return runCatching {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrNull()
    }

    @Synchronized
    fun put(cacheKey: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxFileBytes || !directory.isDirectory && !directory.mkdirs()) return
        val target = File(directory, filename(cacheKey))
        val temp = runCatching { File.createTempFile("image-", ".tmp", directory) }.getOrNull() ?: return
        try {
            temp.writeBytes(bytes)
            if (target.exists() && !target.delete()) return
            if (!temp.renameTo(target)) return
            target.setLastModified(System.currentTimeMillis())
            trim()
        } catch (_: java.io.IOException) {
            // Cache is optional: storage failure must never prevent displaying online images.
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.filter { it.isFile && (it.name.endsWith(".img") || it.name.endsWith(".tmp")) }
            ?.forEach { it.delete() }
    }

    private fun trim() {
        val images = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedBy { it.lastModified() } ?: return
        var bytes = images.sumOf { it.length() }
        for (file in images) {
            if (bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }
}
