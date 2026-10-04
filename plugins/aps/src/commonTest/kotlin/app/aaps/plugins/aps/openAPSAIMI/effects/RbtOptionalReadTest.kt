package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RbtOptionalReadTest {

    @Test
    fun exceptionBecomesAFailedReadAndALogLine() {
        val log = mutableListOf<String>()
        val read = readRbtOptional("endometriosis", log) {
            throw IllegalStateException("flare")
        }
        val failed = assertIs<OptionalSignal.Failed>(read)
        assertEquals("endometriosis", failed.source)
        assertEquals("IllegalStateException", failed.errorType)
        assertEquals("flare", failed.message)
        assertEquals(null, read.valueOrNull())
        assertEquals(
            listOf("RBT endometriosis failed (IllegalStateException): flare — value null"),
            log,
        )
    }

    @Test
    fun auditorCacheExceptionUsesTheSameFallback() {
        val log = mutableListOf<String>()
        val read = readRbtOptional("auditorCache", log) {
            throw IllegalArgumentException("stale")
        }
        assertIs<OptionalSignal.Failed>(read)
        assertEquals(null, read.valueOrNull())
        assertEquals(
            listOf("RBT auditorCache failed (IllegalArgumentException): stale — value null"),
            log,
        )
    }

    @Test
    fun successStaysReadyAndWritesNoLog() {
        val log = mutableListOf<String>()
        val read = readRbtOptional("auditorCache", log) { 0.7 }
        assertIs<OptionalSignal.Ready<Double>>(read)
        assertEquals(0.7, read.valueOrNull())
        assertTrue(log.isEmpty())
    }

    @Test
    fun nullFromTheReadIsAbsenceNotFailure() {
        val log = mutableListOf<String>()
        val read = readRbtOptional<String?>("auditorCache", log) { null }
        assertIs<OptionalSignal.Ready<String?>>(read)
        assertEquals(null, read.valueOrNull())
        assertTrue(log.isEmpty())
    }

    @Test
    fun customFailureLineKeepsTheNullFallback() {
        val log = mutableListOf<String>()
        val read = readRbtOptional(
            source = "t3cHrSnapshot",
            consoleLog = log,
            failureLine = { type, _ -> "🫁 T3c CFRD: hr snapshot failed ($type) — boost 0.00" },
        ) { throw IllegalStateException("wearable") }
        assertIs<OptionalSignal.Failed>(read)
        assertEquals(null, read.valueOrNull())
        assertEquals(
            listOf("🫁 T3c CFRD: hr snapshot failed (IllegalStateException) — boost 0.00"),
            log,
        )
    }

    @Test
    fun errorIsNotSwallowed() {
        val log = mutableListOf<String>()
        assertFailsWith<NotImplementedError> {
            readRbtOptional("auditorCache", log) { TODO("no") }
        }
        assertTrue(log.isEmpty())
    }
}
