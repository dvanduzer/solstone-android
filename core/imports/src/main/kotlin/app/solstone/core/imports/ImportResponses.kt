// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import app.solstone.core.pl.parseJson

/** How a save answered. */
sealed interface SaveOutcome {
    /** The item is staged; start it with this [ImportState.Saved]. [replay] means it was staged before. */
    data class Staged(val saved: ImportState.Saved, val replay: Boolean, val source: SavedSource?) : SaveOutcome
}

/** How a start answered. */
sealed interface StartOutcome {
    data class Started(val taskId: String, val importId: String) : StartOutcome
}

/** The journal already has this item, so there is nothing more to send. */
data object AlreadyAccepted : SaveOutcome, StartOutcome

/** Try the same step again later. [status] is null when no response arrived. */
data class Retry(val status: Int?, val reasonCode: String?) : SaveOutcome, StartOutcome

/** The journal refused this device's credentials: stop sending until the pairing is repaired. */
data class AuthStop(val status: Int) : SaveOutcome, StartOutcome

/**
 * The journal will not take this item as it is. [ownerMessage] is the journal's own sentence,
 * written to be shown to the owner as is; it is null when the response carried none.
 */
data class Attention(val status: Int, val reasonCode: String?, val ownerMessage: String?) : SaveOutcome, StartOutcome

object ImportResponses {
    // The journal tells these refusals apart only by `detail`
    // (core/crates/solstone-core-import-web/src/lifecycle.rs and src/preview.rs).
    internal const val SAVE_ALREADY_IMPORTED = "content already imported"
    internal val START_ALREADY_ACCEPTED = setOf(
        "content already imported; will not start",
        "import already started or processed",
    )
    internal const val START_ALREADY_RUNNING = "this import is already running"

    fun save(status: Int, body: ByteArray): SaveOutcome {
        common(status, body)?.let { return it as SaveOutcome }
        if (status == 200) return staged(body) ?: undecodable(status)
        val refusal = Refusal.parse(body)
        if (refusal.code == ImportReasonCode.INVALID_OPERATION_FOR_STATE.wire &&
            refusal.detail == SAVE_ALREADY_IMPORTED
        ) {
            return AlreadyAccepted
        }
        return refusal.attention(status)
    }

    fun start(status: Int, body: ByteArray): StartOutcome {
        common(status, body)?.let { return it as StartOutcome }
        if (status == 200) return started(body) ?: undecodable(status)
        val refusal = Refusal.parse(body)
        val accepted = when (refusal.code) {
            ImportReasonCode.INVALID_OPERATION_FOR_STATE.wire -> refusal.detail in START_ALREADY_ACCEPTED
            ImportReasonCode.IMPORT_PREVIEW_REQUIRED.wire -> refusal.error == START_ALREADY_RUNNING
            else -> false
        }
        return if (accepted) AlreadyAccepted else refusal.attention(status)
    }

    /** Outcomes that do not depend on the step: credentials and transient failures. */
    private fun common(status: Int, body: ByteArray): Any? = when {
        status == 401 || status == 403 -> AuthStop(status)
        status == 408 || status == 425 || status == 429 || status in 500..599 ->
            Retry(status, Refusal.parse(body).code)
        else -> null
    }

    private fun staged(body: ByteArray): SaveOutcome.Staged? {
        val json = parseObject(body) ?: return null
        if (json["status"] != "staged" || json["recommended_action"] != "start") return null
        val path = (json["path"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val timestamp = (json["timestamp"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return SaveOutcome.Staged(
            ImportState.Saved(path, timestamp),
            replay = json["replay"] == true,
            source = SavedSource.fromWire(json["source"] as? String),
        )
    }

    private fun started(body: ByteArray): StartOutcome.Started? {
        val json = parseObject(body) ?: return null
        if (json["status"] != "ok") return null
        val taskId = (json["task_id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val importId = (json["import_id"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return StartOutcome.Started(taskId, importId)
    }

    private fun undecodable(status: Int) = Attention(status, reasonCode = null, ownerMessage = null)

    private class Refusal(val error: String?, val code: String?, val detail: String?) {
        fun attention(status: Int) = Attention(status, code, error)

        companion object {
            fun parse(body: ByteArray): Refusal {
                val json = parseObject(body) ?: return Refusal(null, null, null)
                return Refusal(
                    json["error"] as? String,
                    json["reason_code"] as? String,
                    json["detail"] as? String,
                )
            }
        }
    }
}

private fun parseObject(body: ByteArray): Map<*, *>? =
    runCatching { parseJson(body.toString(Charsets.UTF_8)) as? Map<*, *> }.getOrNull()
