package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three things the auditor tells a user when a call to the AI never came back.
 *
 * These three lines used to be a `when` over `java.net.UnknownHostException`,
 * `java.net.SocketTimeoutException` and `java.io.IOException` inside `AuditorAIService`, so no
 * shared test could reach them and no target but Android could run them. They now live in
 * [AuditorStatusTracker.statusForTransportFailure] and this runs on every target.
 *
 * The user does not read the status name. They read what the status badge turns it into: "No network
 * connection", "Request timeout" or "Exception occurred". Getting the mapping wrong would not fail
 * anything, it would just tell somebody the wrong reason their auditor verdict is missing.
 */
class AuditorTransportStatusTest {

    @Test
    fun `a request that never reached a server reads as no network`() {
        assertEquals(
            AuditorStatusTracker.Status.OFFLINE_NO_NETWORK,
            AuditorStatusTracker.statusForTransportFailure(AimiHttpFailure.NO_NETWORK)
        )
    }

    @Test
    fun `a request that ran out of time reads as a timeout`() {
        assertEquals(
            AuditorStatusTracker.Status.ERROR_TIMEOUT,
            AuditorStatusTracker.statusForTransportFailure(AimiHttpFailure.TIMEOUT)
        )
    }

    @Test
    fun `anything else reads as a plain exception`() {
        assertEquals(
            AuditorStatusTracker.Status.ERROR_EXCEPTION,
            AuditorStatusTracker.statusForTransportFailure(AimiHttpFailure.OTHER)
        )
    }

    @Test
    fun `every failure the seam can name has its own status`() {
        // A fourth entry added to the enum without a status of its own would land on one of these
        // three by accident, and the user would be told the wrong reason. This makes that visible.
        val statuses = AimiHttpFailure.entries.map { AuditorStatusTracker.statusForTransportFailure(it) }

        assertEquals(AimiHttpFailure.entries.size, statuses.toSet().size)
    }
}
