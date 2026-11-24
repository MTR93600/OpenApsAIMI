package app.aaps.core.interfaces.nsclient

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT

interface ProcessedDeviceStatusData {

    companion object {

        /** JSON name of the extra number inside `openaps.iob`. */
        const val DISPLAYED_IOB_KEY = "displayed"
    }

    enum class Levels(val level: Int) {

        URGENT(2),
        WARN(1),
        INFO(0);
    }

    class PumpData {

        var clock = 0L
        var isPercent = false
        var percent = 0
        var voltage = 0.0
        var status = "N/A"
        /** Null when the master did not send it: it did not know it yet. That is no empty reservoir. */
        var reservoir: Double? = null
        var reservoirDisplayOverride = ""
        var extended: String? = null
        var activeProfileName: String? = null
    }

    var pumpData: PumpData?

    data class Device(
        val createdAt: Long,
        val device: String?
    )

    var device: Device?

    class Uploader {

        var clock = 0L
        var battery = 0
        var isCharging: Boolean? = null
    }

    /** Every uploader seen, with its newest report. A phone that stopped uploading stays in here. */
    val uploaderMap: HashMap<String, Uploader>

    /**
     * The uploaders that still report: [uploaderMap] without the phones whose last report is much older than
     * the newest report of any phone. Use this, not [uploaderMap], for anything shown to the user (#5547).
     */
    val activeUploaders: Map<String, Uploader>

    class OpenAPSData {

        var clockSuggested = 0L

        /**
         * Time of the newest `suggested` this client actually decoded.
         * When [clockSuggested] is newer, the latest document could not be read and the previous
         * result is still stored, so a screen must not treat it as the current one.
         */
        var clockSuggestedDecoded = 0L
        var clockEnacted = 0L
        var suggested: RT? = null
        var enacted: RT? = null

        /** `openaps.iob.iob` of the same document as [suggested]. */
        var iob: Double? = null

        /**
         * `openaps.iob.displayed` of the same document as [suggested].
         * The IOB the master screen shows. Null when that document did not send it.
         */
        var displayedIob: Double? = null
    }

    var openAPSData: OpenAPSData

    val openApsTimestamp: Long
    fun getAPSResult(): APSResult?
    val uploaderStatus: String
}