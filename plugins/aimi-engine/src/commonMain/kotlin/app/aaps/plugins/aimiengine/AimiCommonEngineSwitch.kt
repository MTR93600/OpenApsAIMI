package app.aaps.plugins.aimiengine

/**
 * Gates [HoldAimiEngine] onto a common [AimiEngine] delegate.
 *
 * On by default (activated 2026-10-09 by project decision). While off, [HoldAimiEngine] returns
 * `Hold("ENGINE_NOT_EXTRACTED")` even when a delegate was supplied. Turning this on does not read
 * `IosClientConfig.APS` (that flag stays false), does not start the iOS loop, and does not write
 * a pump.
 *
 * WARNING: activated before byte-for-byte parity traces were available. The original guidance
 * was "only after every parity trace matches Android byte for byte". This decision is recorded
 * here for traceability.
 *
 * Tests may set it and must set it back to the default (true).
 */
object AimiCommonEngineSwitch {
    var enabled: Boolean = true
}
