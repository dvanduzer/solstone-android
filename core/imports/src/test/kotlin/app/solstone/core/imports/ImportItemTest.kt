// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportItemTest {
    private val saved = ImportState.Saved("/journal/imports/20261019_101500/scan.pdf", "20261019_101500")
    private val item = ImportItem(
        clientItemId = "123e4567-e89b-42d3-a456-426614174000",
        payload = ImportPayload.File("scan.pdf", "application/pdf", 8),
    )

    @Test
    fun aSavedItemStartsWithItsSavedPathAndTimestamp() {
        val afterSave = item.afterSave(SaveOutcome.Staged(saved, replay = false, source = SavedSource.DOCUMENT))
        assertEquals(saved, afterSave.state)
        assertFalse(afterSave.settled)

        val afterStart = afterSave.afterStart(StartOutcome.Started("1792404900000", "20261019_101500"))
        assertEquals(ImportState.Started("1792404900000", "20261019_101500"), afterStart.state)
        assertTrue(afterStart.settled)
    }

    @Test
    fun retriesKeepTheStepAndCountAttemptsUntilItAdvances() {
        val retried = item.afterSave(Retry(503, "import_queue_unreachable")).afterNoResponse()
        assertEquals(ImportState.Staged, retried.state)
        assertEquals(2, retried.attempts)
        assertEquals(0, retried.afterSave(SaveOutcome.Staged(saved, replay = true, source = null)).attempts)

        val savedItem = item.copy(state = saved)
        val resumed = savedItem.afterStart(Retry(null, null))
        assertEquals(saved, resumed.state)
        assertEquals(1, resumed.attempts)
    }

    @Test
    fun anAuthStopLeavesTheItemUntouched() {
        assertSame(item, item.afterSave(AuthStop(401)))
    }

    @Test
    fun acceptedAndRefusedItemsSettle() {
        assertEquals(ImportState.AlreadyAccepted, item.afterSave(AlreadyAccepted).state)
        assertEquals(ImportState.AlreadyAccepted, item.copy(state = saved).afterStart(AlreadyAccepted).state)
        val refused = item.afterSave(Attention(422, "strava_download", "use the Strava card"))
        assertEquals(ImportState.NeedsAttention(422, "strava_download", "use the Strava card"), refused.state)
        assertTrue(refused.settled)
        assertFailsWith<IllegalStateException> { refused.afterNoResponse() }
    }

    @Test
    fun anAnswerForTheWrongStepIsAnError() {
        assertFailsWith<IllegalStateException> { item.afterStart(StartOutcome.Started("1", "2")) }
        assertFailsWith<IllegalStateException> {
            item.copy(state = saved).afterSave(SaveOutcome.Staged(saved, replay = false, source = null))
        }
    }

    @Test
    fun payloadsRefuseWhatTheJournalWouldRefuse() {
        assertFailsWith<IllegalArgumentException> { ImportPayload.File("a/b.pdf", "", 1) }
        assertFailsWith<IllegalArgumentException> { ImportPayload.File("b.pdf", "", -1) }
        assertFailsWith<IllegalArgumentException> { ImportPayload.Text(" ") }
        assertFailsWith<IllegalArgumentException> { item.copy(clientItemId = "") }
    }
}
