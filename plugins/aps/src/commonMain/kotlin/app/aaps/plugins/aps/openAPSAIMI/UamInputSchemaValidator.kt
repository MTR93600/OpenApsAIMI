package app.aaps.plugins.aps.openAPSAIMI

internal object UamInputSchemaValidator {

    fun expectedFeatureCount(shape: IntArray): Int? =
        when {
            shape.isEmpty() -> null
            shape.size == 1 -> shape[0].takeIf { it > 0 }
            else -> shape.last().takeIf { it > 0 }
        }

    fun mismatchReason(expectedCount: Int, actualCount: Int): String =
        "⚠ UAM input schema mismatch: expected $expectedCount features, got $actualCount"
}
