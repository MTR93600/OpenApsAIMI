package app.aaps.pump.omnipod.dashctl.history

/**
 * [DashHistoryRecord] list as bytes, big-endian, version 1:
 * - u8 version, u32 count, then each record:
 *   u64 createdAtMs, u8 type ordinal, u8 initial result ordinal, u8 flags (bit0 bolus units, bit1 bolus
 *   type, bit2 rate, bit3 minutes, bit4 delivered, bit5 resolved result, bit6 resolved at), then the
 *   present ones in that order: u64 (double bits), u8 ordinal, u64 (double bits), u32, u64 (double
 *   bits), u8 ordinal, u64.
 *
 * Anything malformed throws; the history then starts empty (it is never needed for dosing).
 */
object DashPodHistoryCodec {

    private const val VERSION: Int = 1

    private const val F_UNITS = 0x01
    private const val F_BOLUS_TYPE = 0x02
    private const val F_RATE = 0x04
    private const val F_MINUTES = 0x08
    private const val F_DELIVERED = 0x10
    private const val F_RESOLVED = 0x20
    private const val F_RESOLVED_AT = 0x40

    fun encode(records: List<DashHistoryRecord>): ByteArray {
        val out = ArrayList<Byte>()
        fun u8(v: Int) = out.add(v.toByte())
        fun u32(v: Int) = (3 downTo 0).forEach { out.add((v shr (it * 8)).toByte()) }
        fun u64(v: Long) = (7 downTo 0).forEach { out.add((v shr (it * 8)).toByte()) }
        u8(VERSION)
        u32(records.size)
        records.forEach { r ->
            u64(r.createdAtMs)
            u8(r.type.ordinal)
            u8(r.initialResult.ordinal)
            var flags = 0
            if (r.bolusUnits != null) flags = flags or F_UNITS
            if (r.bolusType != null) flags = flags or F_BOLUS_TYPE
            if (r.tempBasalRate != null) flags = flags or F_RATE
            if (r.tempBasalMinutes != null) flags = flags or F_MINUTES
            if (r.totalAmountDelivered != null) flags = flags or F_DELIVERED
            if (r.resolvedResult != null) flags = flags or F_RESOLVED
            if (r.resolvedAtMs != null) flags = flags or F_RESOLVED_AT
            u8(flags)
            r.bolusUnits?.let { u64(it.toRawBits()) }
            r.bolusType?.let { u8(it.ordinal) }
            r.tempBasalRate?.let { u64(it.toRawBits()) }
            r.tempBasalMinutes?.let { u32(it) }
            r.totalAmountDelivered?.let { u64(it.toRawBits()) }
            r.resolvedResult?.let { u8(it.ordinal) }
            r.resolvedAtMs?.let { u64(it) }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<DashHistoryRecord> {
        var p = 0
        fun need(n: Int) = require(p + n <= bytes.size) { "truncated" }
        fun u8(): Int {
            need(1)
            return bytes[p++].toInt() and 0xff
        }

        fun u32(): Int {
            need(4)
            var v = 0
            repeat(4) { v = (v shl 8) or (bytes[p++].toInt() and 0xff) }
            return v
        }

        fun u64(): Long {
            need(8)
            var v = 0L
            repeat(8) { v = (v shl 8) or (bytes[p++].toLong() and 0xff) }
            return v
        }

        require(u8() == VERSION) { "unsupported history version" }
        val count = u32()
        require(count >= 0) { "bad count" }
        val records = List(count) {
            val createdAt = u64()
            val type = DashHistoryType.entries.getOrNull(u8()) ?: throw IllegalArgumentException("bad type")
            val initial = DashHistoryInitialResult.entries.getOrNull(u8()) ?: throw IllegalArgumentException("bad initial result")
            val flags = u8()
            require(flags and 0x7f.inv() == 0) { "bad flags" }
            DashHistoryRecord(
                createdAtMs = createdAt,
                type = type,
                initialResult = initial,
                bolusUnits = if (flags and F_UNITS != 0) Double.fromBits(u64()) else null,
                bolusType = if (flags and F_BOLUS_TYPE != 0) DashHistoryBolusType.entries.getOrNull(u8()) ?: throw IllegalArgumentException("bad bolus type") else null,
                tempBasalRate = if (flags and F_RATE != 0) Double.fromBits(u64()) else null,
                tempBasalMinutes = if (flags and F_MINUTES != 0) u32() else null,
                totalAmountDelivered = if (flags and F_DELIVERED != 0) Double.fromBits(u64()) else null,
                resolvedResult = if (flags and F_RESOLVED != 0) DashHistoryResolvedResult.entries.getOrNull(u8()) ?: throw IllegalArgumentException("bad resolved result") else null,
                resolvedAtMs = if (flags and F_RESOLVED_AT != 0) u64() else null,
            )
        }
        require(p == bytes.size) { "trailing bytes" }
        return records
    }
}
