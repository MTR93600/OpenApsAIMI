package app.aaps.wear.complications

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.aaps.wear.data.ComplicationData as Store

/** Dedicated hit target for the visible "Я ЕМ" control on the AIMI dial. */
class AimiEatingButtonComplication : ModernBaseComplicationProviderService() {
    override fun buildComplicationData(
        type: ComplicationType,
        data: Store,
        complicationPendingIntent: PendingIntent
    ): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("Я ЕМ").build(),
            contentDescription = PlainComplicationText.Builder("Открыть режим Я ем").build()
        ).setTapAction(complicationPendingIntent).build()
    }

    override fun getComplicationAction() = ComplicationAction.AIMI_EATING
}
