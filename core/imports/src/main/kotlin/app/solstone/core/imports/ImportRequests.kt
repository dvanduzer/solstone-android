// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import app.solstone.core.pl.toJson
import java.io.ByteArrayOutputStream

/**
 * A file item's save request around its bytes: send [prefix], the file, then [suffix]. The file
 * is never held here, so it can be streamed with an exact `Content-Length`.
 */
class ImportSaveFrame internal constructor(
    val contentType: String,
    val prefix: ByteArray,
    val suffix: ByteArray,
) {
    fun contentLength(fileBytes: Long): Long {
        require(fileBytes in 0..MobileImportContract.MAX_FILE_BYTES) { "file size out of range: $fileBytes" }
        return prefix.size + fileBytes + suffix.size
    }
}

/** A request whose whole body is in memory: a text save or a start. */
class ImportRequestBody internal constructor(val contentType: String, val bytes: ByteArray)

object ImportRequests {
    /** The save request for a file, framed around its bytes. [filename] must already be [importFilename]. */
    fun fileSave(boundary: String, clientItemId: String, filename: String, mediaType: String): ImportSaveFrame {
        requireBoundary(boundary)
        require(filename == importFilename(filename)) { "filename is not normalized" }
        require(mediaType.none { it == '\r' || it == '\n' }) { "media type contains a line break" }
        val prefix = ByteArrayOutputStream()
        prefix.writeFields(boundary, clientItemId)
        prefix.writeAscii("--$boundary\r\n")
        prefix.writeAscii("Content-Disposition: form-data; name=\"file\"; filename=\"")
        prefix.writeUtf8(filename)
        prefix.writeAscii("\"\r\n")
        if (mediaType.isNotEmpty()) prefix.writeAscii("Content-Type: $mediaType\r\n")
        prefix.writeAscii("\r\n")
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII)
        return ImportSaveFrame(multipartType(boundary), prefix.toByteArray(), suffix)
    }

    /** The save request for shared text, which the journal stages as `paste.txt`. */
    fun textSave(boundary: String, clientItemId: String, text: String): ImportRequestBody {
        requireBoundary(boundary)
        require(text.isNotBlank()) { "text is empty" }
        val body = ByteArrayOutputStream()
        body.writeFields(boundary, clientItemId)
        body.writeField(boundary, "text", text)
        body.writeAscii("--$boundary--\r\n")
        return ImportRequestBody(multipartType(boundary), body.toByteArray())
    }

    /** The start request, carrying the saved item's `path` and `timestamp` back unchanged. */
    fun start(saved: ImportState.Saved): ImportRequestBody =
        ImportRequestBody(
            "application/json",
            toJson(mapOf("path" to saved.path, "timestamp" to saved.timestamp)).toByteArray(Charsets.UTF_8),
        )

    private fun ByteArrayOutputStream.writeFields(boundary: String, clientItemId: String) {
        require(clientItemId.isNotBlank()) { "client item id is empty" }
        writeField(boundary, "client_item_id", clientItemId)
        writeField(boundary, "imported_via", MobileImportContract.IMPORTED_VIA)
    }

    private fun ByteArrayOutputStream.writeField(boundary: String, name: String, value: String) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n")
        writeAscii("\r\n")
        writeUtf8(value)
        writeAscii("\r\n")
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun multipartType(boundary: String) = "multipart/form-data; boundary=$boundary"

    // RFC 2046 boundary characters, without the space it allows only before the end.
    private val BOUNDARY = Regex("[0-9A-Za-z'()+_,./:=?-]{1,70}")

    private fun requireBoundary(boundary: String) {
        require(BOUNDARY.matches(boundary)) { "invalid multipart boundary" }
    }
}

/**
 * The name the journal will keep for [raw]: its last path component, without control characters
 * or the quote and backslash a multipart filename would have to escape, and at most
 * [MobileImportContract.MAX_FILENAME_BYTES] UTF-8 bytes. The extension survives shortening,
 * because the journal decides what an item is from it.
 */
fun importFilename(raw: String): String {
    val last = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString(last.length) {
        last.forEach { char ->
            when {
                Character.isISOControl(char) -> Unit
                char == '"' -> append('_')
                else -> append(char)
            }
        }
    }.trim()
    val name = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "item" else cleaned
    if (name.utf8Size() <= MobileImportContract.MAX_FILENAME_BYTES) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
    val budget = MobileImportContract.MAX_FILENAME_BYTES - extension.utf8Size()
    val base = StringBuilder()
    var used = 0
    var index = 0
    val stem = name.substring(0, name.length - extension.length)
    while (index < stem.length) {
        val codePoint = stem.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val size = text.utf8Size()
        if (used + size > budget) break
        base.append(text)
        used += size
        index += Character.charCount(codePoint)
    }
    return base.toString().trimEnd() + extension
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
