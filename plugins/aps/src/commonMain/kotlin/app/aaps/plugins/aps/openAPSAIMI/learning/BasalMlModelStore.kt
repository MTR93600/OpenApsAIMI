package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Basal / T3C weight files, as the loader sees them.
 *
 * [candidates] gives the target and then its `.bak`, in the order the loader must try them. It only reads:
 * deciding whether a model may dose is [BasalHeadHealthGate]'s job, and `BasalNeuralLearner` runs it on each
 * candidate in turn.
 *
 * [quarantine] moves a refused target aside to `.rejected`. That matters because of how a publish writes:
 * `AimiStorage.replaceKeepingBackup` rotates the CURRENT target into `.bak` (Android and iOS both do this only
 * when a target exists). A refused target left in place would become the backup at the next publish, and the
 * good backup would be lost. Once it is moved aside there is no target, the next publish rotates nothing, and
 * the backup stays the last good model.
 *
 * The write side lives in `AimiNeuralModelStore.save` (through `AimiStorage.replaceKeepingBackup`, which
 * writes a `.tmp` and renames it). Do not add a `save` here that is not atomic: a half-written weight file
 * that still parses is a model nobody trained.
 */
internal object BasalMlModelStore {

    /** Which file a candidate came from. */
    enum class Slot { TARGET, BACKUP }

    /**
     * One weight file that exists. [net] is `null` when the file cannot be read as a network (bad JSON, old
     * schema); [stamp] is the gate stamp the publish step wrote, `null` for a file published before the gate.
     */
    class Candidate(
        val slot: Slot,
        val path: AimiPath,
        val net: AimiNeuralNetwork?,
        val stamp: BasalHeadHealthGate.Stamp?,
    )

    /** Suffix of a refused target that was moved aside. Kept for forensics, never loaded. */
    const val REJECTED_SUFFIX = ".rejected"

    /** The target and then its `.bak`, for each file that exists. Never throws. */
    fun candidates(storage: AimiStorage, target: AimiPath): List<Candidate> {
        val slots = listOf(Slot.TARGET to target, Slot.BACKUP to storage.sibling(target, ".bak"))
        return slots.mapNotNull { (slot, path) ->
            try {
                if (!storage.exists(path)) return@mapNotNull null
                val text = storage.readText(path)
                Candidate(
                    slot = slot,
                    path = path,
                    net = text?.let { AimiNeuralNetwork.fromJsonString(it) },
                    stamp = text?.let { BasalHeadHealthGate.readStamp(it) },
                )
            } catch (_: Exception) {
                Candidate(slot, path, net = null, stamp = null)
            }
        }
    }

    /**
     * Moves [path] aside to its `.rejected` sibling: copy, then delete. The delete runs even when the copy
     * fails, because a refused model must never become the next `.bak`; losing the forensic copy is the smaller
     * harm. Returns `true` when [path] is gone.
     */
    fun quarantine(storage: AimiStorage, path: AimiPath): Boolean =
        try {
            storage.copy(path, storage.sibling(path, REJECTED_SUFFIX))
            storage.delete(path)
        } catch (_: Exception) {
            false
        }
}
