// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.imports

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MobileImportContractAdoptionTest {
    private val schema = json("mobile-import-contract/v1.schema.json")
    private val vectors = json("mobile-import-contract/v1.vectors.json")["vectors"] as Map<*, *>
    private val defs = schema["\$defs"] as Map<*, *>

    @Test
    fun pinnedResourcesMatchTheAdoptedJournalCommit() {
        val adoption = json("mobile-import-contract/adoption.json")
        assertEquals("fcbc0395f1151309cec5dd8292e9ac3fdcf056cf", adoption["authority_commit"])
        assertEquals("app.solstone.core.imports.MobileImportContract", adoption["owner"])
        (adoption["files"] as List<*>).forEach { raw ->
            val entry = raw as Map<*, *>
            val path = entry["path"] as String
            val local = path.removePrefix("contracts/mobile-import/")
            assertEquals(entry["sha256"], sha256(bytes("mobile-import-contract/$local")), path)
        }
    }

    @Test
    fun routesLimitsAndVocabulariesMatchTheSchema() {
        val routes = schema["x-solstone-routes"] as Map<*, *>
        assertEquals("POST ${MobileImportContract.SAVE_PATH}", routes["save"])
        assertEquals("POST ${MobileImportContract.START_PATH}", routes["start"])

        val limits = (defs["save_request"] as Map<*, *>)["x-solstone-multipart-limits"] as Map<*, *>
        assertEquals(MobileImportContract.MAX_FILENAME_BYTES, int(limits["max_filename_bytes"]))
        assertEquals(MobileImportContract.MAX_FILE_BYTES, (limits["max_file_bytes"] as Number).toLong())

        val reasonStatus = (defs["reason_status"] as Map<*, *>)["properties"] as Map<*, *>
        assertEquals(enumOf("reason_code"), ImportReasonCode.entries.map { it.wire })
        ImportReasonCode.entries.forEach { code ->
            assertEquals(code.status, int((reasonStatus[code.wire] as Map<*, *>)["const"]), code.wire)
        }
        assertEquals(enumOf("source"), SavedSource.entries.map { it.wire })
    }

    @Test
    fun theSavedVectorStartsWithTheVectorStartRequest() {
        val staged = assertIs<SaveOutcome.Staged>(ImportResponses.save(200, body("save_staged_200")))
        assertEquals(false, staged.replay)
        assertEquals(SavedSource.DOCUMENT, staged.source)

        val start = ImportRequests.start(staged.saved)
        assertEquals("application/json", start.contentType)
        assertEquals(vectors["start_request"], parseJson(start.bytes.toString(Charsets.UTF_8)))

        val replay = assertIs<SaveOutcome.Staged>(ImportResponses.save(200, body("save_replay_200")))
        assertEquals(true, replay.replay)
        assertEquals(staged.saved, replay.saved)
    }

    @Test
    fun bothStartVectorsAreStarted() {
        for (name in listOf("start_queued_200", "start_inprocess_200")) {
            val started = assertIs<StartOutcome.Started>(ImportResponses.start(200, body(name)), name)
            assertEquals("20261019_101500", started.importId)
        }
    }

    @Test
    fun everyContractRefusalIsClassified() {
        (vectors["refusals"] as List<*>).forEach { raw ->
            val refusal = raw as Map<*, *>
            val status = int(refusal["status"])
            val code = refusal["reason_code"] as String
            val envelope = toJson(mapOf("error" to "owner sentence", "reason_code" to code)).toByteArray()
            for (outcome in listOf<Any>(ImportResponses.save(status, envelope), ImportResponses.start(status, envelope))) {
                if (status >= 500) {
                    assertEquals(Retry(status, code), outcome, code)
                } else {
                    assertEquals(Attention(status, code, "owner sentence"), outcome, code)
                }
            }
        }
    }

    private fun enumOf(def: String): List<*> = (defs[def] as Map<*, *>)["enum"] as List<*>
    private fun body(vector: String): ByteArray =
        toJson((vectors[vector] as Map<*, *>)["body"]).toByteArray(Charsets.UTF_8)
    private fun int(value: Any?): Int = (value as Number).toInt()
    private fun json(path: String): Map<*, *> = parseJson(bytes(path).toString(Charsets.UTF_8)) as Map<*, *>
    private fun bytes(path: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) { path }.use { it.readBytes() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
