package app.aaps.plugins.dexcomoneplus

import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusAuthRefusals
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusCgmDriverResumePolicyTest {

    @Test
    fun `complete stored session can resume`() {
        val stored = storedSession()

        assertThat(OnePlusCgmDriverResumePolicy.canResume(stored)).isTrue()
    }

    @Test
    fun `a key lost to a refusal of the same sensor can resume, to pair again`() {
        val lostKey = storedSession().copy(sharedKey = null)
        val refused = OnePlusAuthRefusals(address = lostKey.lastMac?.lowercase(), count = 1, lastAtMs = 1L, lastWithSavedKey = true)
        assertThat(OnePlusCgmDriverResumePolicy.canResume(lostKey, refused)).isTrue()
        // Another sensor's refusal, or a refusal without a saved key, does not count.
        assertThat(OnePlusCgmDriverResumePolicy.canResume(lostKey, refused.copy(address = "00:00:00:00:00:09"))).isFalse()
        assertThat(OnePlusCgmDriverResumePolicy.canResume(lostKey, refused.copy(lastWithSavedKey = false))).isFalse()
        assertThat(OnePlusCgmDriverResumePolicy.canResume(lostKey, refused.copy(count = 0))).isFalse()
    }

    @Test
    fun `resume requires valid pin MAC and shared key`() {
        assertThat(OnePlusCgmDriverResumePolicy.canResume(null)).isFalse()
        assertThat(
            OnePlusCgmDriverResumePolicy.canResume(
                storedSession().copy(lastMac = null),
            ),
        ).isFalse()
        assertThat(
            OnePlusCgmDriverResumePolicy.canResume(
                storedSession().copy(identity = OnePlusSensorIdentity(pin = "12")),
            ),
        ).isFalse()
        assertThat(
            OnePlusCgmDriverResumePolicy.canResume(
                storedSession().copy(sharedKey = null),
            ),
        ).isFalse()
        assertThat(
            OnePlusCgmDriverResumePolicy.canResume(
                storedSession().copy(sharedKey = ByteArray(15)),
            ),
        ).isFalse()
    }

    private fun storedSession() = OnePlusStoredSession(
        identity = OnePlusSensorIdentity(pin = "1234"),
        lastMac = "DA:3B:12:0D:5B:B7",
        lastDeviceName = "DX02aS",
        sharedKey = ByteArray(16),
    )
}
