package app.aaps.plugins.sync.nsclientV3

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Notices when this iPhone changes between Nightscout client and master, and makes the first upload
 * as master safe.
 *
 * The first iOS builds were clients. A client uploads nothing, so on a phone that ran one every
 * `*LastSyncedId` is still 0. The first upload as master would then walk the whole database and send
 * rows that only ever lived on this phone - see `DataSyncSelectorV3.markAllAsSynced` for why that is
 * harmful. Android never meets this: a client and a master are two different apps there, with two
 * different databases.
 *
 * The rule: when the role is master now and was not master the last time the app ran, every row
 * already in the database counts as uploaded. A phone that never ran a client is not hurt by this:
 * its database is empty the first time, so nothing moves. Going from master to client needs nothing,
 * because a client does not upload.
 *
 * The role is remembered in the key-value store under a raw key rather than as a preference key. It
 * is a fact about this install, not a setting, and it must not appear in the settings screens, the
 * preference schema or a settings export that is imported on another phone.
 *
 * Must run before the Nightscout plugin starts, or its first upload could run before the marks are
 * moved.
 */
@SingleIn(AppScope::class)
@Inject
class IosNsRoleChange(
    private val aapsLogger: AAPSLogger,
    private val config: Config,
    private val keyValueStore: KeyValueStore,
    private val dataSyncSelectorV3: DataSyncSelectorV3
) {

    suspend fun applyIfChanged() {
        NsRoleChangeRule(
            readRole = { keyValueStore.getStringOrNull(ROLE_KEY, null) },
            writeRole = { keyValueStore.putString(ROLE_KEY, it) },
            markAllAsSynced = { dataSyncSelectorV3.markAllAsSynced() },
            alreadyMaster = { dataSyncSelectorV3.hasUploadedAsMaster() },
            log = { aapsLogger.info(LTag.NSCLIENT, it) }
        ).apply(isClient = config.AAPSCLIENT)
    }

    private companion object {

        /** Not a preference key on purpose - see the class comment. */
        const val ROLE_KEY = "ios_ns_last_role"
    }
}

/**
 * The decision in [IosNsRoleChange], without the store and the database, so it can be tested.
 *
 * The stored value is [CLIENT] or [MASTER]. A missing value is treated like [CLIENT], except when
 * the phone has already uploaded as a master: one master build ran before the role was written.
 */
internal class NsRoleChangeRule(
    private val readRole: () -> String?,
    private val writeRole: (String) -> Unit,
    private val markAllAsSynced: suspend () -> Unit,
    /** Whether this phone already uploaded as a master, on a build that did not write the role yet. */
    private val alreadyMaster: () -> Boolean = { false },
    private val log: (String) -> Unit
) {

    suspend fun apply(isClient: Boolean) {
        val now = if (isClient) CLIENT else MASTER
        val before = readRole()
        if (before == now) return
        // No role stored, but the phone already uploaded as master: a master build ran before the
        // role was written. Its rows that wait for upload must still go up, so nothing is skipped.
        if (now == MASTER && before == null && alreadyMaster()) {
            log("This phone already ran as the Nightscout master: nothing is skipped")
            writeRole(now)
            return
        }
        if (now == MASTER) {
            log("This phone was ${before ?: "an older build"} and is now the Nightscout master: rows written before now will not be uploaded")
            markAllAsSynced()
        }
        // Written only after the marks moved. If the app is killed in between, the next start does
        // it again, which is harmless: a mark is only ever moved up.
        writeRole(now)
    }

    companion object {

        const val CLIENT = "client"
        const val MASTER = "master"
    }
}
