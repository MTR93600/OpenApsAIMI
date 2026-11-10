package app.aaps.pump.medtrum

import app.aaps.core.interfaces.maintenance.HandoverFields
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.util.MedtrumDeviceName

/**
 * The Medtrum pairing a "Transfer to another phone" export carries, the same for the Android and the
 * iPhone driver: both keep their state in a [MedtrumProtocolState].
 *
 * The patch only answers a phone that sends its session token, which the phone that activated the
 * patch made up. So the token, the serial and the sequence numbers are what lets the next phone go on
 * with the patch that is on the body, without a new one. The synced sequence number is what keeps the
 * pump history from being read twice.
 *
 * The user settings of the pump (alarm, hourly and daily maximum) are preferences and travel with the
 * rest of the settings, so they are not here.
 */
object MedtrumHandover {

    const val ID = "medtrum"

    private const val SERIAL = "serial"
    private const val DEVICE_TYPE = "deviceType"
    private const val SW_VERSION = "swVersion"
    private const val PATCH_ID = "patchId"
    private const val SESSION_TOKEN = "sessionToken"
    private const val PATCH_START = "patchStartTime"
    private const val SEQUENCE = "currentSequenceNumber"
    private const val SYNCED_SEQUENCE = "syncedSequenceNumber"
    private const val BASAL_PROFILE = "actualBasalProfile"
    private const val TIME_ZONE_OFFSET = "pumpTimeZoneOffset"
    private const val PUMP_STATE = "pumpState"
    private const val LAST_BOLUS_TIME = "lastBolusTime"
    private const val LAST_BOLUS_AMOUNT = "lastBolusAmount"

    /** The pairing in [state], or null when no pump is paired (no serial). */
    fun export(state: MedtrumProtocolState): HandoverFields? {
        if (state.pumpSN == 0L) return null
        return buildMap {
            put(SERIAL, MedtrumDeviceName.serialHex(state.pumpSN))
            put(DEVICE_TYPE, state.deviceType.toString())
            put(SW_VERSION, state.swVersion)
            put(PATCH_ID, state.patchId.toString())
            put(SESSION_TOKEN, state.patchSessionToken.toString())
            put(PATCH_START, state.patchStartTime.toString())
            put(SEQUENCE, state.currentSequenceNumber.toString())
            put(SYNCED_SEQUENCE, state.syncedSequenceNumber.toString())
            put(BASAL_PROFILE, state.actualBasalProfile.toHex())
            put(TIME_ZONE_OFFSET, state.pumpTimeZoneOffset.toString())
            put(PUMP_STATE, state.pumpState.state.toString())
            state.lastBolusTime?.let { put(LAST_BOLUS_TIME, it.toString()) }
            state.lastBolusAmount?.let { put(LAST_BOLUS_AMOUNT, it.toString()) }
        }
    }

    /**
     * Writes [fields] into [state] and returns the pump serial as hex text, the form both drivers
     * store it in. Returns null, and writes nothing, when a field is missing or cannot be read.
     */
    fun import(fields: HandoverFields, state: MedtrumProtocolState): String? {
        val parsed = Parsed.of(fields) ?: return null
        state.pumpSN = parsed.serial
        state.deviceType = parsed.deviceType
        state.swVersion = parsed.swVersion
        state.patchId = parsed.patchId
        state.patchSessionToken = parsed.sessionToken
        state.patchStartTime = parsed.patchStartTime
        state.currentSequenceNumber = parsed.sequence
        state.syncedSequenceNumber = parsed.syncedSequence
        state.actualBasalProfile = parsed.basalProfile
        state.pumpTimeZoneOffset = parsed.timeZoneOffset
        state.pumpState = parsed.pumpState
        state.lastBolusTime = parsed.lastBolusTime
        state.lastBolusAmount = parsed.lastBolusAmount
        return MedtrumDeviceName.serialHex(parsed.serial)
    }

    /** All fields read first, so a bad file changes nothing. */
    private class Parsed(
        val serial: Long,
        val deviceType: Int,
        val swVersion: String,
        val patchId: Long,
        val sessionToken: Long,
        val patchStartTime: Long,
        val sequence: Int,
        val syncedSequence: Int,
        val basalProfile: ByteArray,
        val timeZoneOffset: Int,
        val pumpState: MedtrumPumpState,
        val lastBolusTime: Long?,
        val lastBolusAmount: Double?
    ) {

        companion object {

            fun of(f: HandoverFields): Parsed? = runCatching {
                Parsed(
                    serial = MedtrumDeviceName.serialFromHex(f[SERIAL])?.takeIf { it != 0L } ?: return null,
                    deviceType = f.getValue(DEVICE_TYPE).toInt(),
                    swVersion = f[SW_VERSION].orEmpty(),
                    patchId = f.getValue(PATCH_ID).toLong(),
                    sessionToken = f.getValue(SESSION_TOKEN).toLong(),
                    patchStartTime = f.getValue(PATCH_START).toLong(),
                    sequence = f.getValue(SEQUENCE).toInt(),
                    syncedSequence = f.getValue(SYNCED_SEQUENCE).toInt(),
                    basalProfile = f.getValue(BASAL_PROFILE).fromHex() ?: return null,
                    timeZoneOffset = f.getValue(TIME_ZONE_OFFSET).toInt(),
                    pumpState = MedtrumPumpState.fromByte(f.getValue(PUMP_STATE).toByte()) ?: return null,
                    lastBolusTime = f[LAST_BOLUS_TIME]?.toLong(),
                    lastBolusAmount = f[LAST_BOLUS_AMOUNT]?.toDouble()
                )
            }.getOrNull()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray? =
        if (length % 2 != 0) null
        else runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
