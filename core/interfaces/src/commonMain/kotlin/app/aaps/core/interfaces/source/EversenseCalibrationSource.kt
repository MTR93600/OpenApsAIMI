package app.aaps.core.interfaces.source

/**
 * Way in to Eversense's own calibration over Bluetooth, for modules that must not depend on
 * `plugins:eversense` or `plugins:source`. Same pattern as [XDripSource] and [DexcomBoyda].
 *
 * This is not the generic calibration path. The generic one posts a value through the
 * xDrip broadcast; this one writes a fingerstick value straight to the transmitter, which then
 * re-scales every reading it sends afterwards. That is why the value is bounded and why the
 * transmitter must report that it is ready before anything is sent.
 *
 * The Eversense driver is Android only. On iOS and on the desktop the binding reports itself
 * disabled, so the calibration entry is never shown there.
 */
interface EversenseCalibrationSource {

    /** True when Eversense is the BG source in use right now. */
    fun isEnabled(): Boolean

    /** True when we are connected to the transmitter over Bluetooth. */
    fun isConnected(): Boolean

    /** True when the transmitter says it will accept a calibration now. */
    fun isReadyToCalibrate(): Boolean

    /**
     * Short text for the user saying why a calibration cannot be sent yet, already translated.
     * Empty when [isReadyToCalibrate] is true.
     */
    fun readinessMessage(): String

    /**
     * Sends a fingerstick value in mg/dL to the transmitter, connecting first (with a timeout) if
     * needed. The value must be inside [MIN_CALIBRATION_MGDL] .. [MAX_CALIBRATION_MGDL].
     * Returns true when the transmitter accepted the packet.
     */
    suspend fun calibrate(bgMgDl: Int): Boolean

    companion object {

        /**
         * Lowest and highest fingerstick value Eversense accepts as a calibration reference.
         * A value outside this range still reaches the transmitter and shifts every later reading,
         * so every entry point must check it. Both the quick launch dialog and the
         * Settings -> Eversense -> Calibration screen read these two numbers.
         */
        const val MIN_CALIBRATION_MGDL = 40
        const val MAX_CALIBRATION_MGDL = 400
    }
}
