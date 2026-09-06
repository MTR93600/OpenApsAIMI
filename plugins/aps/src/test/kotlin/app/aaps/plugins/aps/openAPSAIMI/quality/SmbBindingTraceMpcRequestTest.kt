package app.aaps.plugins.aps.openAPSAIMI.quality

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * Locks the one reading the binding trace could not make before: "the solver asked for nothing"
 * against "the barrier removed what the solver asked for".
 *
 * `model_output_u` is taken after the barrier, so it is 0 in both cases. `mpc_requested_u` is taken
 * before it, and stays absent — never 0 — on ticks where no solver ran.
 */
class SmbBindingTraceMpcRequestTest {

    private fun draft(): SmbBindingTrace.Draft = SmbBindingTrace.Draft(timestampMs = 1_757_100_000_000L)

    @Test
    fun `a request wiped by the barrier is still readable`() {
        val json: JSONObject = draft()
            .copy(
                originOwner = "AutodriveV3",
                modelOutputU = 0.0,
                mpcOutputU = 0.0,
                mpcRequestedU = 1.5,
            )
            .build(finalU = 0.0)
            .toJsonObject()

        assertThat(json.getDouble("mpc_requested_u")).isWithin(1e-9).of(1.5)
        assertThat(json.getDouble("model_output_u")).isWithin(1e-9).of(0.0)
        assertThat(json.getDouble("final_u")).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a tick without autodrive leaves the request unknown, not zero`() {
        val json: JSONObject = draft()
            .copy(originOwner = "GlobalAIMI", modelOutputU = 0.0)
            .build(finalU = 0.0)
            .toJsonObject()

        assertThat(json.isNull("mpc_requested_u")).isTrue()
        assertThat(json.optDouble("mpc_requested_u", -1.0)).isEqualTo(-1.0)
    }

    @Test
    fun `a non-finite request is exported as unknown`() {
        val trace = draft()
            .copy(originOwner = "AutodriveV3", mpcRequestedU = Double.NaN)
            .build(finalU = 0.0)

        assertThat(trace.mpcRequestedU).isNull()
        assertThat(trace.toJsonObject().isNull("mpc_requested_u")).isTrue()
    }

    @Test
    fun `a real zero request stays a zero and is not turned into unknown`() {
        val trace = draft()
            .copy(originOwner = "AutodriveV3", mpcRequestedU = 0.0)
            .build(finalU = 0.0)

        assertThat(trace.mpcRequestedU!!).isWithin(1e-9).of(0.0)
        assertThat(trace.toJsonObject().getDouble("mpc_requested_u")).isWithin(1e-9).of(0.0)
    }
}
