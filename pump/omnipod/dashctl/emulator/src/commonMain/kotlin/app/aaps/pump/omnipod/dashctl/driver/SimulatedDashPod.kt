package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus

/**
 * One simulated pod for its whole life, for a test of the app that does not know it talks to a test
 * pod: new and not paired, found by the scan of the activation (it advertises as a new pod), paired
 * and activated by the driver ([ActivatingPodModel]), then a pod that runs ([RunningPodModel])
 * until it is deactivated.
 *
 * - [central] is the BLE central the driver gets: it behaves as CoreBluetooth does for the held
 *   connection ([HeldPodCentral]). Give its events to the connection (`central.listener`).
 * - The pod computes its own keys ([FakeDashPod.PodKeys.Generated]), so the phone may use real random
 *   keys and nonces.
 * - The pod becomes a running pod in the answer that inserts the cannula (pod status
 *   RUNNING_ABOVE_MIN_VOLUME), with the sequence the activation left and the basal of part 2 running.
 *
 * The answers are given under one lock: the driver may reach the pod from more than one thread
 * (the queue thread, the worker of the held connection), one call at a time.
 */
class SimulatedDashPod(val podId: String = HeldPodCentral.POD_ID) {

    val pod = FakeDashPod(FakeDashPod.PodKeys.Generated())
    val central = HeldPodCentral(pod)
    val activation = ActivatingPodModel()
    val running = RunningPodModel()

    private val lock = AapsLock()

    /** True once the cannula is inserted: from then on [running] answers. */
    var activated = false
        private set

    init {
        central.advertisements += HeldPodCentral.advertisement(podId)
        pod.answerFor = ::answer
    }

    private fun answer(command: ByteArray): ByteArray = lock.withLock {
        if (activated) return@withLock running.answer(command)
        val answer = activation.answer(command)
        if (activation.podStatus == PodStatus.RUNNING_ABOVE_MIN_VOLUME) {
            activated = true
            running.lastProgrammingSequence = activation.lastProgrammingSequence
            running.basalRunning = true
        }
        answer
    }

    /** Stops the event thread of the pod link, if any. Call it at the end of the test. */
    fun close() = pod.close()
}
