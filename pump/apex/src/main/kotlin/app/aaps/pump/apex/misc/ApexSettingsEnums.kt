package app.aaps.pump.apex.misc

// Thresholds credits: Medtronic pump driver
enum class BatteryType(val lowVoltage: Double, val highVoltage: Double) {
    Custom(0.0, 0.0),
    Alkaline(1.2, 1.47),
    Lithium(1.22, 1.64),
    NiZn(1.4, 1.7),
    NiMh(1.1, 1.4),
}
