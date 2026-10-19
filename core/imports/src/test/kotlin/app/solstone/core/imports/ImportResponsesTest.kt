// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import app.solstone.core.pl.toJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportResponsesTest {
    @Test
    fun contentTheJournalAlreadyHasIsAccepted() {
        assertEquals(AlreadyAccepted, ImportResponses.save(400, refusal("invalid_operation_for_state", ImportResponses.SAVE_ALREADY_IMPORTED)))
        ImportResponses.START_ALREADY_ACCEPTED.forEach { detail ->
            assertEquals(AlreadyAccepted, ImportResponses.start(400, refusal("invalid_operation_for_state", detail)), detail)
        }
        val running = toJson(mapOf("error" to ImportResponses.START_ALREADY_RUNNING, "reason_code" to "import_preview_required"))
        assertEquals(AlreadyAccepted, ImportResponses.start(409, running.toByteArray()))
    }

    @Test
    fun otherStateRefusalsNeedTheOwner() {
        val reused = refusal("invalid_operation_for_state", "client_item_id already staged for different content")
        assertEquals(Attention(400, "invalid_operation_for_state", "sentence"), ImportResponses.save(400, reused))
        val changed = toJson(mapOf("error" to "the source or settings changed", "reason_code" to "import_preview_required"))
        assertEquals(
            Attention(409, "import_preview_required", "the source or settings changed"),
            ImportResponses.start(409, changed.toByteArray()),
        )
        assertEquals(
            Attention(422, "strava_download", "sentence"),
            ImportResponses.save(422, refusal("strava_download", "strava")),
        )
    }

    @Test
    fun credentialsAndTransientFailuresAreTheSameForBothSteps() {
        for (status in listOf(401, 403)) {
            assertEquals(AuthStop(status), ImportResponses.save(status, ByteArray(0)))
            assertEquals(AuthStop(status), ImportResponses.start(status, ByteArray(0)))
        }
        for (status in listOf(408, 425, 429, 500, 502, 503)) {
            assertEquals(Retry(status, null), ImportResponses.save(status, "<html>".toByteArray()))
            assertEquals(Retry(status, null), ImportResponses.start(status, "<html>".toByteArray()))
        }
    }

    @Test
    fun aSuccessThatCannotBeReadNeedsTheOwner() {
        assertEquals(Attention(200, null, null), ImportResponses.save(200, "not json".toByteArray()))
        assertEquals(Attention(200, null, null), ImportResponses.save(200, """{"status":"staged","path":"","timestamp":"t","recommended_action":"start"}""".toByteArray()))
        assertEquals(Attention(200, null, null), ImportResponses.save(200, """{"status":"staged","path":"p","timestamp":"t","recommended_action":"wait"}""".toByteArray()))
        assertEquals(Attention(200, null, null), ImportResponses.start(200, """{"status":"ok","task_id":"1"}""".toByteArray()))
        assertEquals(Attention(404, null, null), ImportResponses.start(404, "not found".toByteArray()))
    }

    private fun refusal(code: String, detail: String): ByteArray =
        toJson(mapOf("error" to "sentence", "reason_code" to code, "detail" to detail)).toByteArray()
}
