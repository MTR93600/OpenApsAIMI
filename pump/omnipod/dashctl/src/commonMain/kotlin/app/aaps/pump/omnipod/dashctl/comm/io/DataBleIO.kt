package app.aaps.pump.omnipod.dashctl.comm.io

/**
 * The DATA characteristic ([CharacteristicType.DATA]): carries the packets of a message (see
 * `PayloadSplitter` and `PayloadJoiner`). Used by `MessageIO`.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.DataBleIO`.
 * Everything in the [BleCharacteristicIO] contract applies.
 */
interface DataBleIO : BleCharacteristicIO
