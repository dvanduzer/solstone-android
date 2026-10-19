// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

/**
 * The journal's mobile import contract: a multipart save stages an owner-chosen item, then a
 * JSON start with the save's `path` and `timestamp` runs it. Mirrors solstone-journal's
 * `mobile_import_contract`; the pinned copy under `src/test/resources/mobile-import-contract/`
 * is checked against these values.
 */
object MobileImportContract {
    const val SAVE_PATH = "/app/import/api/save"
    const val START_PATH = "/app/import/api/start"

    /** The door an Android item comes through, recorded by the journal as `imported_via`. */
    const val IMPORTED_VIA = "mobile_share"

    const val MAX_FILENAME_BYTES = 128
    const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
}

/** The refusals a save or a start can return. Each code has exactly one HTTP status. */
enum class ImportReasonCode(val wire: String, val status: Int) {
    MISSING_REQUIRED_FIELD("missing_required_field", 400),
    INGEST_NO_FILES("ingest_no_files", 400),
    MULTIPART_PART_TOO_LARGE("multipart_part_too_large", 413),
    STRAVA_DOWNLOAD("strava_download", 422),
    INVALID_OPERATION_FOR_STATE("invalid_operation_for_state", 400),
    IMPORT_NOT_FOUND("import_not_found", 404),
    IMPORT_CONFLICT("import_conflict", 409),
    IMPORT_PREVIEW_REQUIRED("import_preview_required", 409),
    IMPORT_QUEUE_UNREACHABLE("import_queue_unreachable", 503),
    IMPORT_METADATA_FAILED("import_metadata_failed", 500),
    ;

    companion object {
        fun fromWire(wire: String?): ImportReasonCode? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the journal recorded a saved item as, which decides how it imports. */
enum class SavedSource(val wire: String) {
    IMAGE("image"),
    DOCUMENT("document"),
    AUDIO("audio"),
    TEXT("text"),
    ;

    companion object {
        fun fromWire(wire: String?): SavedSource? = entries.firstOrNull { it.wire == wire }
    }
}
