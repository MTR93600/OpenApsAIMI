package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.pod.response.VersionResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the Android `OmnipodDashPodStateManagerImpl` stores from the activation answers
 * (`updateFromVersionResponse`, `updateFromSetUniqueIdResponse`) and from the first default status
 * (`activationTime`), and the `expiry` it computes. The answers are the Android test vectors of
 * `VersionResponseTest` and `SetUniqueIdResponseTest`.
 */
class DashPodActivationFieldsTest {

    /** Keeps the state as bytes, so each save goes through the codec as on the phone. */
    private class CodecStore : DashPodStateStore {
        var bytes: ByteArray? = null
        override fun load(): DashPodState? = bytes?.let { DashPodStateCodec.decode(it) }
        override fun save(state: DashPodState) {
            bytes = DashPodStateCodec.encode(state)
        }

        override fun clear() {
            bytes = null
        }
    }

    private val versionResponse = VersionResponse(parityHex("0115040A00010300040208146CC1000954D400FFFFFFFF"))
    private val setUniqueIdResponse = SetUniqueIdResponse(parityHex("011B13881008340A50040A00010300040308146CC1000954D402420001"))

    private fun status(minutes: Int, receivedAtMs: Long, podStatus: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME) = DashPodStatus(
        podStatus = podStatus,
        deliveryStatus = DeliveryStatus.BASAL_ACTIVE,
        totalPulsesDelivered = 0,
        reservoirPulsesRemaining = 1023,
        minutesSinceActivation = minutes,
        bolusPulsesRemaining = 0,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = 0,
        receivedAtMs = receivedAtMs,
    )

    @Test
    fun `Version stores the versions the lot and the sequence of the pod`() {
        val store = CodecStore()
        val manager = DashPodStateManager(store)

        manager.updateFromVersionResponse(versionResponse, 1_000L)

        assertEquals(SoftwareVersion(4, 10, 0), manager.firmwareVersion)
        assertEquals(SoftwareVersion(1, 3, 0), manager.bleVersion)
        assertEquals(135556289L, manager.lotNumber)
        assertEquals(611540L, manager.podSequenceNumber)
        assertEquals(PodStatus.FILLED, manager.reportedPodStatus)
        assertEquals(1_000L, manager.lastUpdatedSystemMs)
        assertNull(manager.uniqueId, "Android does not take the unique id of the Version answer")
        assertEquals(manager.currentState(), DashPodStateManager(store).currentState(), "stored at once")
    }

    @Test
    fun `SetUniqueId stores every field Android stores`() {
        val store = CodecStore()
        val manager = DashPodStateManager(store)

        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)

        assertEquals(16.toShort(), manager.pulseRate)
        assertEquals(8.toShort(), manager.primePulseRate)
        assertEquals(52.toShort(), manager.firstPrimeBolusVolume)
        assertEquals(10.toShort(), manager.secondPrimeBolusVolume)
        assertEquals(80.toShort(), manager.podLifeInHours)
        assertEquals(SoftwareVersion(4, 10, 0), manager.firmwareVersion)
        assertEquals(SoftwareVersion(1, 3, 0), manager.bleVersion)
        assertEquals(135556289L, manager.lotNumber)
        assertEquals(611540L, manager.podSequenceNumber)
        assertEquals(PodStatus.UID_SET, manager.reportedPodStatus)
        assertEquals(37879809L, manager.uniqueId)
        assertEquals(2_000L, manager.lastUpdatedSystemMs)
        assertEquals(manager.currentState(), DashPodStateManager(store).currentState(), "survives a restart")
    }

    @Test
    fun `SetUniqueId leaves the key and the counters alone`() {
        val manager = DashPodStateManager(CodecStore())
        val ltk = ByteArray(16) { 3 }
        manager.updateFromPairing(37879809L, ltk)
        manager.increaseMessageSequenceNumber()

        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)

        assertEquals(ltk.toList(), manager.ltk?.toList())
        assertEquals(1, manager.messageSequenceNumber)
        assertEquals(1L, manager.eapAkaSequenceNumber)
    }

    @Test
    fun `the first default status sets the activation time`() {
        val manager = DashPodStateManager(CodecStore())

        manager.updateFromDefaultStatus(status(minutes = 30, receivedAtMs = 10_000_000L))

        assertEquals(10_000_000L - 30 * 60_000L, manager.activationTime)
        assertEquals(PodStatus.RUNNING_ABOVE_MIN_VOLUME, manager.reportedPodStatus)
        assertEquals(10_000_000L, manager.lastUpdatedSystemMs)
    }

    @Test
    fun `a later default status keeps the activation time`() {
        val manager = DashPodStateManager(CodecStore())
        manager.updateFromDefaultStatus(status(minutes = 30, receivedAtMs = 10_000_000L))

        manager.updateFromDefaultStatus(status(minutes = 40, receivedAtMs = 99_000_000L))

        assertEquals(10_000_000L - 30 * 60_000L, manager.activationTime)
        assertEquals(99_000_000L, manager.lastUpdatedSystemMs)
    }

    @Test
    fun `an alarm status does not set the activation time`() {
        val manager = DashPodStateManager(CodecStore())

        manager.updateFromStatus(status(minutes = 30, receivedAtMs = 10_000_000L, podStatus = PodStatus.ALARM))

        assertNull(manager.activationTime)
        assertEquals(PodStatus.ALARM, manager.reportedPodStatus)
        assertEquals(10_000_000L, manager.lastUpdatedSystemMs)
    }

    @Test
    fun `a status after SetUniqueId replaces the reported pod status`() {
        val manager = DashPodStateManager(CodecStore())
        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)

        manager.updateFromDefaultStatus(status(minutes = 0, receivedAtMs = 3_000L, podStatus = PodStatus.CLUTCH_DRIVE_ENGAGED))

        assertEquals(PodStatus.CLUTCH_DRIVE_ENGAGED, manager.reportedPodStatus)
    }

    @Test
    fun `the expiry is the Android expiry`() {
        val manager = DashPodStateManager(CodecStore())
        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)
        manager.updateFromDefaultStatus(status(minutes = 60, receivedAtMs = 50_000_000L))

        // Android: now + 80 h - 60 min - (now - lastUpdatedSystem) - 8 h.
        assertEquals(50_000_000L - 60 * 60_000L + 72 * 3_600_000L, manager.expiryMs)
    }

    @Test
    fun `no expiry without the pod life or a status`() {
        val manager = DashPodStateManager(CodecStore())
        manager.updateFromDefaultStatus(status(minutes = 60, receivedAtMs = 50_000_000L))
        assertNull(manager.expiryMs, "no pod life yet")

        val other = DashPodStateManager(CodecStore())
        other.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)
        assertNull(other.expiryMs, "no status yet")
    }

    @Test
    fun `reset clears the activation fields`() {
        val manager = DashPodStateManager(CodecStore())
        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 2_000L)
        manager.updateFromDefaultStatus(status(minutes = 60, receivedAtMs = 50_000_000L))

        manager.reset()

        assertEquals(DashPodState(), manager.currentState())
    }
}
