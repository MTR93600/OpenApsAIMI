package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse

/**
 * The last status the pod reported. The fields `OmnipodDashPodStateManagerImpl.updatePodState`
 * takes from a default or an alarm status response.
 *
 * @property lastProgrammingCommandSequence the sequence number of the last programming command the
 *   pod took; it decides whether an active command went through ([DashCommandConfirmation]).
 * @property receivedAtMs wall clock when it arrived, for display only. The order of events uses
 *   [DashPodState.statusResponseCount], which a clock change cannot move.
 */
data class DashPodStatus(
    val podStatus: PodStatus,
    val deliveryStatus: DeliveryStatus,
    val totalPulsesDelivered: Int,
    val reservoirPulsesRemaining: Int,
    val minutesSinceActivation: Int,
    val bolusPulsesRemaining: Int,
    val activeAlerts: Set<AlertType>,
    val lastProgrammingCommandSequence: Int,
    val receivedAtMs: Long,
) {

    companion object {

        fun of(response: DefaultStatusResponse, receivedAtMs: Long) = DashPodStatus(
            podStatus = response.podStatus,
            deliveryStatus = response.deliveryStatus,
            totalPulsesDelivered = response.totalPulsesDelivered.toInt(),
            reservoirPulsesRemaining = response.reservoirPulsesRemaining.toInt(),
            minutesSinceActivation = response.minutesSinceActivation.toInt(),
            bolusPulsesRemaining = response.bolusPulsesRemaining.toInt(),
            activeAlerts = response.activeAlerts,
            lastProgrammingCommandSequence = response.sequenceNumberOfLastProgrammingCommand.toInt(),
            receivedAtMs = receivedAtMs,
        )

        fun of(response: AlarmStatusResponse, receivedAtMs: Long) = DashPodStatus(
            podStatus = response.podStatus,
            deliveryStatus = response.deliveryStatus,
            totalPulsesDelivered = response.totalPulsesDelivered.toInt(),
            reservoirPulsesRemaining = response.reservoirPulsesRemaining.toInt(),
            minutesSinceActivation = response.minutesSinceActivation.toInt(),
            bolusPulsesRemaining = response.bolusPulsesRemaining.toInt(),
            activeAlerts = response.activeAlerts,
            lastProgrammingCommandSequence = response.sequenceNumberOfLastProgrammingCommand.toInt(),
            receivedAtMs = receivedAtMs,
        )
    }
}
