package app.aaps.pump.omnipod.dashctl.comm.io

/**
 * The two GATT characteristics of the Dash pod service.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CharacteristicType`.
 * Deliberate difference: Android also has a `uuid` property of type `java.util.UUID`. It is left
 * out here because `java.util` does not exist in common code. A platform implementation builds
 * its own UUID type from [value] (for example `CBUUID(string:)` on iOS).
 */
enum class CharacteristicType(val value: String) {
    CMD("1a7e2441-e3ed-4464-8b7e-751e03d0dc5f"), DATA("1a7e2442-e3ed-4464-8b7e-751e03d0dc5f");

    companion object {

        fun byValue(value: String): CharacteristicType =
            CharacteristicType.entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown Characteristic Type: $value")
    }
}
