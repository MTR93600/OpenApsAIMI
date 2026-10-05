package app.aaps.plugins.aimiengine

/**
 * Gates [HoldAimiEngine] onto a common [AimiEngine] delegate.
 *
 * Off by default. While off, [HoldAimiEngine] returns `Hold("ENGINE_NOT_EXTRACTED")` even when a
 * delegate was supplied. Turning this on does not read `IosClientConfig.APS` (that flag stays
 * false), does not start the iOS loop, and does not write a pump.
 *
 * A test that sets [enabled] must set it back to false before it returns.
 */
object AimiCommonEngineSwitch {
    var enabled: Boolean = false
}
