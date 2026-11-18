package app.aaps.plugins.aps.openAPSAIMI

/** Desktop: no UAM predictor (see `aimiPlatformUamPredictor`), so there is no model to install. */
internal actual fun installBundledUamModel(): AimiUamModelInstall.Outcome = AimiUamModelInstall.Outcome.NOT_BUNDLED
