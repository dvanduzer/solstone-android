// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportRequestsTest {
    private val boundary = "solstone-import-7f3a"
    private val itemId = "123e4567-e89b-42d3-a456-426614174000"

    @Test
    fun fileSaveFramesTheFileBetweenItsFieldsAndTheClosingBoundary() {
        val frame = ImportRequests.fileSave(boundary, itemId, "scan.pdf", "application/pdf")
        val file = "%PDF-1.7".toByteArray()
        val body = frame.prefix + file + frame.suffix

        assertEquals("multipart/form-data; boundary=$boundary", frame.contentType)
        assertEquals(body.size.toLong(), frame.contentLength(file.size.toLong()))
        assertEquals(
            listOf(
                Part("client_item_id", null, null, itemId),
                Part("imported_via", null, null, "mobile_share"),
                Part("file", "scan.pdf", "application/pdf", "%PDF-1.7"),
            ),
            parts(body),
        )
    }

    @Test
    fun textSaveSendsTheTextField() {
        val request = ImportRequests.textSave(boundary, itemId, "a note\nwith two lines")
        assertEquals(
            listOf(
                Part("client_item_id", null, null, itemId),
                Part("imported_via", null, null, "mobile_share"),
                Part("text", null, null, "a note\nwith two lines"),
            ),
            parts(request.bytes),
        )
    }

    @Test
    fun requestsRefuseInputsTheJournalWouldReject() {
        assertFailsWith<IllegalArgumentException> { ImportRequests.fileSave("bad boundary", itemId, "a.pdf", "") }
        assertFailsWith<IllegalArgumentException> { ImportRequests.fileSave(boundary, " ", "a.pdf", "") }
        assertFailsWith<IllegalArgumentException> { ImportRequests.fileSave(boundary, itemId, "dir/a.pdf", "") }
        assertFailsWith<IllegalArgumentException> { ImportRequests.fileSave(boundary, itemId, "a.pdf", "text/plain\r\nX: y") }
        assertFailsWith<IllegalArgumentException> { ImportRequests.textSave(boundary, itemId, "   ") }
        val frame = ImportRequests.fileSave(boundary, itemId, "a.pdf", "")
        assertFailsWith<IllegalArgumentException> { frame.contentLength(MobileImportContract.MAX_FILE_BYTES + 1) }
    }

    @Test
    fun filenamesKeepTheirLastComponentWithoutCharactersTheJournalCannotTake() {
        assertEquals("scan.pdf", importFilename("/storage/emulated/0/Download/scan.pdf"))
        assertEquals("scan.pdf", importFilename("C:\\Users\\owner\\scan.pdf"))
        assertEquals("my_quote_.txt", importFilename("my\"quote\".txt"))
        assertEquals("tabs.txt", importFilename("ta\tbs.txt"))
        assertEquals("item", importFilename("   "))
        assertEquals("item", importFilename(".."))
        assertEquals("item", importFilename("photos/"))
    }

    @Test
    fun longFilenamesShortenToTheLimitAndKeepTheirExtension() {
        val ascii = importFilename("a".repeat(300) + ".m4a")
        assertEquals(MobileImportContract.MAX_FILENAME_BYTES, ascii.toByteArray().size)
        assertTrue(ascii.endsWith(".m4a"))

        val wide = importFilename("日本語のファイル名".repeat(10) + ".pdf")
        assertTrue(wide.toByteArray().size <= MobileImportContract.MAX_FILENAME_BYTES)
        assertTrue(wide.endsWith(".pdf"))
        assertEquals(wide, importFilename(wide))

        val emoji = importFilename("😀".repeat(40) + ".jpg")
        assertTrue(emoji.toByteArray().size <= MobileImportContract.MAX_FILENAME_BYTES)
        assertTrue(emoji.removeSuffix(".jpg").all { it.isSurrogate() })
        assertEquals(0, emoji.removeSuffix(".jpg").length % 2)
    }

    private data class Part(val name: String, val filename: String?, val contentType: String?, val value: String)

    private fun parts(body: ByteArray): List<Part> {
        val text = body.toString(Charsets.UTF_8)
        assertTrue(text.endsWith("--$boundary--\r\n"))
        return text.removeSuffix("--$boundary--\r\n")
            .split("--$boundary\r\n")
            .filter { it.isNotEmpty() }
            .map { raw ->
                val (head, value) = raw.removeSuffix("\r\n").split("\r\n\r\n", limit = 2)
                val headers = head.split("\r\n")
                val disposition = headers.first()
                Part(
                    name = Regex("name=\"([^\"]*)\"").find(disposition)!!.groupValues[1],
                    filename = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1),
                    contentType = headers.firstOrNull { it.startsWith("Content-Type: ") }?.removePrefix("Content-Type: "),
                    value = value,
                )
            }
    }
}
