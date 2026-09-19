package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

/**
 * Tail of an AIMI CSV for the support package: header first, then the newest rows.
 *
 * Behaviour from `origin/dev_OAPSAIMI` @ `02c90656b1` (`addCsvTail`). The tail, not the whole
 * file: the corpus grows for ever and a support package must stay small enough to send.
 * [MAX_CSV_ROWS_IN_PACKAGE] rows are about two days at the one minute loop rate.
 *
 * Rows are counted, not dated. The date column is written with the user's locale format, so
 * parsing it back to filter on time would break on some devices; counting lines cannot.
 */
internal object AimiSupportCsvTail {

    /**
     * How many CSV rows the support package carries, newest last.
     *
     * About two days at the one minute loop rate, which matches the 24 hour window the decision
     * log uses, with room for the delay `SmbTrainingRowBuffer` adds before a row is written.
     */
    const val MAX_CSV_ROWS_IN_PACKAGE = 3000

    data class Selected(
        val header: String,
        val body: List<String>,
    )

    fun select(lines: List<String>): Selected? {
        if (lines.isEmpty()) return null
        return Selected(
            header = lines.first(),
            body = lines.drop(1).takeLast(MAX_CSV_ROWS_IN_PACKAGE),
        )
    }

    fun toText(tail: Selected): String {
        val sb = StringBuilder()
        sb.append(tail.header).append('\n')
        tail.body.forEach { row -> sb.append(row).append('\n') }
        return sb.toString()
    }
}
