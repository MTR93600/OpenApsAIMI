package app.aaps.database.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntryWithTime
import app.aaps.database.entities.interfaces.TraceableDBEntry

@Entity(
    tableName = TABLE_GLUCOSE_VALUES,
    foreignKeys = [ForeignKey(
        entity = GlucoseValue::class,
        parentColumns = ["id"],
        childColumns = ["referenceId"]
    )],
    indices = [
        Index("nightscoutId"),
        Index("referenceId"),
        Index("timestamp")
    ]
)
data class GlucoseValue(
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
    override var version: Int = 0,
    override var dateCreated: Long = -1,
    override var isValid: Boolean = true,
    override var referenceId: Long? = null,
    @Embedded
    override var interfaceIDs_backing: InterfaceIDs? = InterfaceIDs(),
    override var timestamp: Long,
    override var utcOffset: Long = defaultUtcOffset(timestamp),
    var raw: Double?,
    var value: Double,
    var trendArrow: TrendArrow,
    var noise: Double?,
    var sourceSensor: SourceSensor
) : TraceableDBEntry, DBEntryWithTime {

    fun contentEqualsTo(other: GlucoseValue): Boolean =
        isValid == other.isValid &&
            timestamp == other.timestamp &&
            utcOffset == other.utcOffset &&
            raw == other.raw &&
            value == other.value &&
            trendArrow == other.trendArrow &&
            noise == other.noise &&
            sourceSensor == other.sourceSensor

    enum class TrendArrow {
        NONE,
        TRIPLE_UP,
        DOUBLE_UP,
        SINGLE_UP,
        FORTY_FIVE_UP,
        FLAT,
        FORTY_FIVE_DOWN,
        SINGLE_DOWN,
        DOUBLE_DOWN,
        TRIPLE_DOWN
        ;
    }

    /**
     * One entry per sensor. Names that older versions stored are mapped on read by
     * `Converters.toSourceSensor`, so a row written before the entries were merged keeps its meaning.
     */
    /**
     * Stored by name, so **no value may ever be removed**: a row written by an older build names a
     * constant, and a constant that is gone cannot be read back.
     *
     * That is why this is the union of the upstream list and this fork's older, per-bridge one.
     * Upstream consolidated its model enum to one entry per sensor; the values below that upstream
     * no longer has are kept because real databases contain them. New rows use the upstream
     * spelling, except for the three native plugins this fork ships, which stay distinct because
     * AIMI reads them to decide how much a sensor lags — see `SourceSensor`'s own note.
     */
    enum class SourceSensor {
        DEXCOM_UNKNOWN,
        DEXCOM_G6,
        DEXCOM_G7,
        LIBRE_1,
        LIBRE_2,
        LIBRE_3,
        MEDTRUM_A6,
        MEDTRUM_UNKNOWN,
        MM_600_SERIES,
        MM_SIMPLERA,
        MM_UNKNOWN,
        SIBIONIC_UNKNOWN,
        SIBIONIC_GS1,
        SIBIONIC_GS3,
        ACCU_CHEK,
        CARESENS_AIR,
        AIDEX,
        AIDEX_X,
        POCTECH_NATIVE,
        GLUNOVO_NATIVE,
        INTELLIGO_NATIVE,
        SINOCARE,
        ANYTIME,
        GLUTEC,
        GLUPRO,
        EVERSENSE,
        SYAI_TAG,
        INSTARA,
        RANDOM,
        UNKNOWN,

        // ---- This fork's own entries, and values written by older builds of it ----
        // Still produced today, because AIMI distinguishes them:
        DEXCOM_G6_NATIVE,
        DEXCOM_G7_NATIVE,
        DEXCOM_ONEPLUS_NATIVE,
        LIBRE_3_NATIVE,
        EVERSENSE_E3,
        EVERSENSE_365,

        // Read only: older rows carry these. New readings land on the consolidated value above.
        DEXCOM_NATIVE_UNKNOWN,
        DEXCOM_G6_NATIVE_XDRIP,
        DEXCOM_G7_NATIVE_XDRIP,
        DEXCOM_G7_XDRIP,
        LIBRE_1_OTHER,
        LIBRE_1_NET,
        LIBRE_1_BLUE,
        LIBRE_1_PL,
        LIBRE_1_BLUCON,
        LIBRE_1_TOMATO,
        LIBRE_1_RF,
        LIBRE_1_LIMITTER,
        LIBRE_1_BUBBLE,
        LIBRE_1_ATOM,
        LIBRE_1_GLIMP,
        LIBRE_2_NATIVE,
        OTTAI,
        SIBIONIC,
        SINO,

        IOB_PREDICTION,
        A_COB_PREDICTION,
        COB_PREDICTION,
        UAM_PREDICTION,
        ZT_PREDICTION,
        ;
    }
}
