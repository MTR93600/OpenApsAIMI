package app.aaps.pump.omnipod.dashctl.pod.util

import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType

object AlertUtil {

    fun decodeAlertSet(encoded: Byte): Set<AlertType> {
        val encodedInt = encoded.toInt() and 0xff

        val alertList = AlertType.entries
            .filter { it != AlertType.UNKNOWN } // 0xff && <something> will always be true
            .filter { (it.value.toInt() and 0xff) and encodedInt != 0 }
            .toList()

        return if (alertList.isEmpty()) {
            emptySet<AlertType>()
        } else {
            alertList.toSet()
        }
    }

    fun encodeAlertSet(alertSet: Set<AlertType>): Byte =
        alertSet.fold(0) { out, slot ->
            out or (slot.value.toInt() and 0xff)
        }.toByte()
}
