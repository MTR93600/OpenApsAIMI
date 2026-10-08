package app.aaps.pump.omnipod.dashctl.pod.command

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.apache.commons.codec.binary.Hex
import org.junit.jupiter.api.Test

class SetUniqueIdCommandTest {

    @Test fun testEncoding() {
        val initializationTime =
            LocalDateTime(3921, 2, 10, 14, 41).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val encoded = SetUniqueIdCommand.Builder()
            .setUniqueId(37879811)
            .setSequenceNumber(6.toShort())
            .setLotNumber(135556289)
            .setPodSequenceNumber(681767)
            .setInitializationTime(initializationTime)
            .build()
            .encoded

        assertThat(encoded).asList().containsExactlyElementsIn(Hex.decodeHex("FFFFFFFF18150313024200031404020A150E2908146CC1000A67278344").asList()).inOrder()
    }
}
