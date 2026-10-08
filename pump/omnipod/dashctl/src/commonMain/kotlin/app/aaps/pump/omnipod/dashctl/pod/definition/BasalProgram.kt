package app.aaps.pump.omnipod.dashctl.pod.definition

import kotlinx.datetime.*

class BasalProgram(
    segments: List<Segment>
) {

    private val mutableSegments: MutableList<Segment> = segments.toMutableList()

    val segments: List<Segment>
        get() = mutableSegments.toList()

    fun addSegment(segment: Segment) {
        mutableSegments.add(segment)
    }

    fun hasZeroUnitSegments() = segments.any { it.basalRateInHundredthUnitsPerHour == 0 }

    fun rateAt(date: Long): Double {
        val ldt = Instant.fromEpochMilliseconds(date).toLocalDateTime(TimeZone.currentSystemDefault())
        val hourOfDay = ldt.hour
        val minuteOfHour = ldt.minute
        val slotIndex = hourOfDay * 2 + minuteOfHour.div(30)
        val slot = segments.find { it.startSlotIndex <= slotIndex && slotIndex < it.endSlotIndex }
        return (slot?.basalRateInHundredthUnitsPerHour ?: 0).toDouble() / 100
    }

    class Segment(
        val startSlotIndex: Short,
        val endSlotIndex: Short,
        val basalRateInHundredthUnitsPerHour: Int
    ) {

        fun getPulsesPerHour(): Short {
            return (basalRateInHundredthUnitsPerHour * PULSES_PER_UNIT / 100).toShort()
        }

        fun getNumberOfSlots(): Short {
            return (endSlotIndex - startSlotIndex).toShort()
        }

        override fun toString(): String {
            return "Segment{" +
                "startSlotIndex=" + startSlotIndex +
                ", endSlotIndex=" + endSlotIndex +
                ", basalRateInHundredthUnitsPerHour=" + basalRateInHundredthUnitsPerHour +
                '}'
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Segment

            if (startSlotIndex != other.startSlotIndex) return false
            if (endSlotIndex != other.endSlotIndex) return false
            if (basalRateInHundredthUnitsPerHour != other.basalRateInHundredthUnitsPerHour) return false

            return true
        }

        override fun hashCode(): Int {
            var result: Int = startSlotIndex.toInt()
            result = 31 * result + endSlotIndex
            result = 31 * result + basalRateInHundredthUnitsPerHour
            return result
        }

        companion object {

            private const val PULSES_PER_UNIT: Byte = 20
        }
    }

    override fun toString(): String {
        return "BasalProgram{" +
            "segments=" + segments +
            '}'
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BasalProgram

        return segments == other.segments
    }

    override fun hashCode(): Int {
        return segments.hashCode()
    }
}
