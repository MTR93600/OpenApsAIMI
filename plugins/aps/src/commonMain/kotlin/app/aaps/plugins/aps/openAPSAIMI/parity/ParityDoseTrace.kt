package app.aaps.plugins.aps.openAPSAIMI.parity

/**
 * One scenario, fixed field order. Doubles are raw IEEE-754 bits in hex.
 * A missing value is the token `absent`, never a formatted decimal.
 */
data class ParityDoseTrace(
    val scenario: String,
    val tbrRateUph: Double?,
    val tbrMinutes: Int?,
    val smbU: Double?,
    val eventualBg: Double?,
    val isfMgdl: Double?,
    val virtualCobG: Double?,
    val modelWord0: String?,
    val healthKit: String,
    val bgMgdl: Double?,
    val deltaMgdl: Double?,
    val port: String,
) {
    fun canonical(): String = buildString {
        appendLine("scenario=$scenario")
        appendLine("tbrRate=${tbrRateUph.bits()}")
        appendLine("tbrMinutes=${tbrMinutes ?: "absent"}")
        appendLine("smb=${smbU.bits()}")
        appendLine("eventual=${eventualBg.bits()}")
        appendLine("isf=${isfMgdl.bits()}")
        appendLine("virtualCob=${virtualCobG.bits()}")
        appendLine("modelWord0=${modelWord0 ?: "absent"}")
        appendLine("healthKit=$healthKit")
        appendLine("bg=${bgMgdl.bits()}")
        appendLine("delta=${deltaMgdl.bits()}")
        append("port=$port")
    }
}

/**
 * Output word of vector 0 in `plugins/aps/src/tfliteParity/vectors.txt`, the Android arm64-v8a
 * reference corpus. Vector 0 is the all-zero input, so this word is the model intercept. The file
 * has 67 rows of 19 words: 18 input words and this one output word. The tick does not apply it;
 * the harness only carries it so a trace says which model corpus it was taken against.
 */
const val PARITY_ANDROID_UAM_WORD0: String = "3f9eea89"

fun Double?.bits(): String {
    if (this == null) return "absent"
    return toRawBits().toULong().toString(16).padStart(16, '0')
}

/**
 * First line that differs. Null when the two canonical texts are the same bytes.
 * [reference] is the Android trace. [actual] is the iOS replay.
 */
fun firstParityDivergence(reference: String, actual: String): String? {
    val left = reference.split('\n')
    val right = actual.split('\n')
    val scenario = left.firstOrNull()?.substringAfter("scenario=") ?: "?"
    val width = maxOf(left.size, right.size)
    for (index in 0 until width) {
        val android = left.getOrNull(index) ?: "absent"
        val ios = right.getOrNull(index) ?: "absent"
        if (android != ios) {
            val field = android.substringBefore('=').ifEmpty { "line$index" }
            val androidValue = android.substringAfter('=', android)
            val iosValue = ios.substringAfter('=', ios)
            return "scenario=$scenario field=$field android=$androidValue ios=$iosValue"
        }
    }
    return null
}
