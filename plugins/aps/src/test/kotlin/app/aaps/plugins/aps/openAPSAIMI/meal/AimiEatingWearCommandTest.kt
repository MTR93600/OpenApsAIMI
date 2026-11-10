package app.aaps.plugins.aps.openAPSAIMI.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiEatingWearCommandTest {
    @Test fun stopActionRoutesOnlyToStopHandler() {
        assertThat(aimiEatingWearCommand(AimiEatingWearReceiver.ACTION_STOP)).isEqualTo(AimiEatingWearCommand.STOP)
        assertThat(aimiEatingWearCommand(AimiEatingWearReceiver.ACTION_START)).isEqualTo(AimiEatingWearCommand.START)
        assertThat(aimiEatingWearCommand(AimiEatingWearReceiver.ACTION_STATUS)).isEqualTo(AimiEatingWearCommand.STATUS)
        assertThat(aimiEatingWearCommand("unknown")).isNull()
    }
}
