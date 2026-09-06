package app.aaps.core.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LocalProfileLastChange("local_profile_last_change", 0L),

    BtWatchdogLastBark("bt_watchdog_last", 0L),
    ActivePumpChangeTimestamp("active_pump_change_timestamp", 0L),
    LastCleanupRun("last_cleanup_run", 0L),

    // NSCv3 client-control pairing (excluded from export — replay protection regresses if restored)
    NsClientControlCounterSent("nsclient_control_counter_sent", 0L, exportable = false),

    // When this client paired with master. Used by OrphanDetector to suppress false-positive
    // orphan signals on settings/aaps docs whose srvModified predates the pairing (master
    // hasn't republished the roster yet). Not exported — re-pair regenerates this.
    NsClientControlPairedAt("nsclient_control_paired_at", 0L, exportable = false),
    LastVacuumRun("last_vacuum_run", 0L),

    // Bumped whenever the PK/PD learned state (aimi_pkpd_state_dia_h / _peak_min) is written from
    // outside the loop (reset button, insulin preset). Long-lived PkPdIntegration instances cache
    // that state in memory; they compare this counter on each tick to know they must re-seed from
    // prefs. Any change is a signal, up or down. So an overflow or a restore from backup are
    // both safe.
    OApsAIMIPkpdLearnedStateGeneration("aimi_pkpd_learned_state_generation", 0L),
}

