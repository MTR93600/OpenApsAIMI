package app.aaps.pump.omnipod.dashctl.comm

import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink

/**
 * The two BLE node ids of a link: ours ([myId]) and the pod's ([podId]).
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.Ids`.
 * Difference from Android: the constructor takes the pod unique id itself, not the Android
 * `OmnipodDashPodStateManager` (it only reads `uniqueId` from it). The `dashctl` state manager is
 * a different class. The ids are the same: `Id.fromLong(uniqueId)`, or `myId.increment()` when the
 * pod has no unique id yet.
 */
class Ids(uniqueId: Long?) {

    val myId = Id.fromInt(DashBleLink.CONTROLLER_ID)
    val podId = uniqueId?.let(Id::fromLong)
        ?: myId.increment() // pod not activated

    companion object {

        /** Android `PodScanner.POD_ID_NOT_ACTIVATED`: the address of a pod that is not paired yet. */
        const val POD_ID_NOT_ACTIVATED = 0xFFFFFFFEL

        fun notActivated(): Id {
            return Id.fromLong(
                POD_ID_NOT_ACTIVATED
            )
        }
    }
}
