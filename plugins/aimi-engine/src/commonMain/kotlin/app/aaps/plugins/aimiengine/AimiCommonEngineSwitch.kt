package app.aaps.plugins.aimiengine

/**
 * Gates [HoldAimiEngine] onto a common [AimiEngine] delegate.
 *
 * Off by default. While off, [HoldAimiEngine] returns `Hold("ENGINE_NOT_EXTRACTED")` even when a
 * delegate was supplied. Turning this on does not read `IosClientConfig.APS` (that flag stays
 * false), does not start the iOS loop, and does not write a pump.
 *
 * Production iOS may set [enabled] only after every parity trace matches Android byte for byte.
 * The neutral values behind this switch are temporary. Tests may set it and must set it back.
 *
 * A test that sets [enabled] must set it back to false before it returns.
 */
object AimiCommonEngineSwitch {
    var enabled: Boolean = false
}
