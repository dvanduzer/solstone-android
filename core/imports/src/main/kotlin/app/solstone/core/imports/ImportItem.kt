// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

/** What an import item carries: a file held on the device, or shared text. */
sealed interface ImportPayload {
    data class File(val filename: String, val mediaType: String, val byteSize: Long) : ImportPayload {
        init {
            require(filename == importFilename(filename)) { "filename is not normalized" }
            require(byteSize in 0..MobileImportContract.MAX_FILE_BYTES) { "file size out of range: $byteSize" }
        }
    }

    data class Text(val text: String) : ImportPayload {
        init {
            require(text.isNotBlank()) { "text is empty" }
        }
    }
}

/** Where an item stands with the journal. */
sealed interface ImportState {
    /** Held on the device and not yet saved. */
    data object Staged : ImportState

    /** Saved; a start must carry this [path] and [timestamp] back unchanged. */
    data class Saved(val path: String, val timestamp: String) : ImportState

    /** The journal is importing it. */
    data class Started(val taskId: String, val importId: String) : ImportState

    /** The journal already had it. */
    data object AlreadyAccepted : ImportState

    /** The journal will not take it as it is; the owner decides what happens next. */
    data class NeedsAttention(val status: Int, val reasonCode: String?, val ownerMessage: String?) : ImportState
}

/**
 * One owner-chosen item on its way to the journal. [clientItemId] is the journal's idempotency
 * key: it stays the same for every attempt, so a save repeated after a lost response is safe.
 */
data class ImportItem(
    val clientItemId: String,
    val payload: ImportPayload,
    val state: ImportState = ImportState.Staged,
    val attempts: Int = 0,
) {
    init {
        require(clientItemId.isNotBlank()) { "client item id is empty" }
        require(attempts >= 0) { "attempts is negative" }
    }

    /** Nothing more will be sent for this item. */
    val settled: Boolean
        get() = state is ImportState.Started || state is ImportState.AlreadyAccepted ||
            state is ImportState.NeedsAttention

    fun afterSave(outcome: SaveOutcome): ImportItem {
        check(state == ImportState.Staged) { "save answered for an item in $state" }
        return when (outcome) {
            is SaveOutcome.Staged -> copy(state = outcome.saved, attempts = 0)
            else -> settle(outcome)
        }
    }

    fun afterStart(outcome: StartOutcome): ImportItem {
        check(state is ImportState.Saved) { "start answered for an item in $state" }
        return when (outcome) {
            is StartOutcome.Started -> copy(state = ImportState.Started(outcome.taskId, outcome.importId), attempts = 0)
            else -> settle(outcome)
        }
    }

    /** No answer arrived for the step in flight: the item stays where it was. */
    fun afterNoResponse(): ImportItem {
        check(!settled) { "no response for a settled item" }
        return copy(attempts = attempts + 1)
    }

    private fun settle(outcome: Any): ImportItem = when (outcome) {
        AlreadyAccepted -> copy(state = ImportState.AlreadyAccepted)
        is Retry -> copy(attempts = attempts + 1)
        is AuthStop -> this
        is Attention -> copy(state = ImportState.NeedsAttention(outcome.status, outcome.reasonCode, outcome.ownerMessage))
        else -> error("unexpected outcome $outcome")
    }
}
