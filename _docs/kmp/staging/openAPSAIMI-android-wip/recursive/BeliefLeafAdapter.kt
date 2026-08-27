package app.aaps.plugins.aps.openAPSAIMI.recursive

/**
 * Adapter contract — one Kotlin source → one leaf reader (§15.10).
 */
interface BeliefLeafAdapter {
    val id: BeliefLeafId
    val scales: Set<Int>
    fun read(ctx: RecursiveBeliefTickContext): BeliefLeafReading?
}
