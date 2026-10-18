package app.aaps.plugins.dexcomoneplus.gatt

import java.util.UUID

/**
 * Dex family GATT UUIDs used by xDrip Direct / Ob1 path.
 *
 * Provenance: NightscoutFoundation/xDrip `BluetoothServices.java`
 * at pin `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).
 * See `plugins/dexcom_oneplus/NOTICE` and docs/DEXCOM_ONEPLUS_LICENCE_MEMO.md.
 *
 * Historical file comment upstream: "Created by joeginley on 3/16/16."
 */
object OnePlusBluetoothUuids {

    val DeviceInfo: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    val Advertisement: UUID = UUID.fromString("0000FEBC-0000-1000-8000-00805F9B34FB")
    val CgmService: UUID = UUID.fromString("F8083532-849E-531C-C594-30F1F86A4EA5")
    val ServiceB: UUID = UUID.fromString("F8084532-849E-531C-C594-30F1F86A4EA5")

    val ManufacturerNameString: UUID = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")

    val Communication: UUID = UUID.fromString("F8083533-849E-531C-C594-30F1F86A4EA5")
    val Control: UUID = UUID.fromString("F8083534-849E-531C-C594-30F1F86A4EA5")
    val Authentication: UUID = UUID.fromString("F8083535-849E-531C-C594-30F1F86A4EA5")
    val ProbablyBackfill: UUID = UUID.fromString("F8083536-849E-531C-C594-30F1F86A4EA5")
    val ExtraData: UUID = UUID.fromString("F8083538-849E-531C-C594-30F1F86A4EA5")

    val CharacteristicE: UUID = UUID.fromString("F8084533-849E-531C-C594-30F1F86A4EA5")
    val CharacteristicF: UUID = UUID.fromString("F8084534-849E-531C-C594-30F1F86A4EA5")

    val CharacteristicUpdateNotification: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
