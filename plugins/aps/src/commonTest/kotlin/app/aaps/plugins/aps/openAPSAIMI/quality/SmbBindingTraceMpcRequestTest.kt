package app.aaps.plugins.aps.openAPSAIMI.quality

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Locks the one reading the binding trace could not make before: "the solver asked for nothing"
 * against "the barrier removed what the solver asked for".
 *
 * `model_output_u` is taken after the barrier, so it is 0 in both cases. `mpc_requested_u` is taken
 * before it, and stays absent — never 0 — on ticks where no solver ran.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `db21308e6c` (unchanged on tip `c5db5a0333`).
 * Study source set: [SmbBindingTrace] is commonMain and uses kotlinx JSON, so the tests live in
 * `commonTest` (`kotlin.test`). Not `org.json`.
 */
class SmbBindingTraceMpcRequestTest {

    private fun draft(): SmbBindingTrace.Draft = SmbBindingTrace.Draft(timestampMs = 1_757_100_000_000L)

    @Test
    fun `a request wiped by the barrier is still readable`() {
        val json = draft()
            .copy(
                originOwner = "AutodriveV3",
                modelOutputU = 0.0,
                mpcOutputU = 0.0,
                mpcRequestedU = 1.5,
            )
            .build(finalU = 0.0)
            .toJsonObject()

        assertEquals(1.5, json.getValue("mpc_requested_u").jsonPrimitive.double, 1e-9)
        assertEquals(0.0, json.getValue("model_output_u").jsonPrimitive.double, 1e-9)
        assertEquals(0.0, json.getValue("final_u").jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `a tick without autodrive leaves the request unknown not zero`() {
        val json = draft()
            .copy(originOwner = "GlobalAIMI", modelOutputU = 0.0)
            .build(finalU = 0.0)
            .toJsonObject()

        assertEquals(JsonNull, json["mpc_requested_u"])
    }

    @Test
    fun `a non-finite request is exported as unknown`() {
        val trace = draft()
            .copy(originOwner = "AutodriveV3", mpcRequestedU = Double.NaN)
            .build(finalU = 0.0)

        assertNull(trace.mpcRequestedU)
        assertEquals(JsonNull, trace.toJsonObject()["mpc_requested_u"])
    }

    @Test
    fun `a real zero request stays a zero and is not turned into unknown`() {
        val trace = draft()
            .copy(originOwner = "AutodriveV3", mpcRequestedU = 0.0)
            .build(finalU = 0.0)

        assertEquals(0.0, trace.mpcRequestedU!!, 1e-9)
        assertEquals(0.0, trace.toJsonObject().getValue("mpc_requested_u").jsonPrimitive.double, 1e-9)
    }
}
