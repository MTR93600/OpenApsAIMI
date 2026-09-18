package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.DS
import app.aaps.core.data.model.EB
import app.aaps.core.data.model.EPS
import app.aaps.core.data.model.FD
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.NE
import app.aaps.core.data.model.PS
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SC
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TDD
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.model.UE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.DatabaseMaintenanceInfo
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.PersistenceLayer.Calibration
import app.aaps.core.interfaces.db.PersistenceLayer.TransactionResult
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * CommonTest PersistenceLayer that only serves [TE] notes to [Therapy].
 *
 * mockk is JVM-only; this stub lets TherapyAnticipation/FCL detection tests run in commonTest
 * through the real snapshot path.
 */
internal class TherapyNotePersistence(private val events: List<TE>) : PersistenceLayer {

    private fun unused(): Nothing = error("TherapyNotePersistence: unused PersistenceLayer method")

    override suspend fun clearDatabases() { unused() }
    override suspend fun clearApsResults() { unused() }
    override suspend fun cleanupDatabase(keepDays: Long, deleteTrackedChanges: Boolean): String = unused()
    override suspend fun vacuumDatabase() { unused() }
    override suspend fun databaseMaintenanceInfo(retentionDays: Long): DatabaseMaintenanceInfo = unused()
    override fun <T : Any> observeChanges(type: KClass<T>): Flow<List<T>> = unused()
    override fun observeAnyChange(): Flow<Set<KClass<*>>> = unused()
    override val databaseClearedFlow: Flow<Unit> get() = unused()
    override suspend fun getNewestBolus(): BS? = unused()
    override suspend fun getOldestBolus(): BS? = unused()
    override suspend fun getNewestBolusOfType(type: BS.Type): BS? = unused()
    override suspend fun getLastBolusId(): Long? = unused()
    override suspend fun getBolusByNSId(nsId: String): BS? = unused()
    override suspend fun getBoluses(): List<BS> = unused()
    override suspend fun getBolusesFromTime(startTime: Long, ascending: Boolean): List<BS> = unused()
    override suspend fun getBolusesFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<BS> = unused()
    override suspend fun getBolusesFromTimeIncludingInvalid(startTime: Long, ascending: Boolean): List<BS> = unused()
    override suspend fun getNextSyncElementBolus(id: Long): Pair<BS, BS>? = unused()
    override suspend fun insertOrUpdateBolus(bolus: BS, action: Action, source: Sources, note: String?): TransactionResult<BS> = unused()
    override suspend fun insertBolusWithTempId(bolus: BS): TransactionResult<BS> = unused()
    override suspend fun invalidateBolus(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<BS> = unused()
    override suspend fun syncPumpBolus(bolus: BS, type: BS.Type?): TransactionResult<BS> = unused()
    override suspend fun syncPumpBolusWithTempId(bolus: BS, type: BS.Type?): TransactionResult<BS> = unused()
    override suspend fun syncNsBolus(boluses: List<BS>, doLog: Boolean): TransactionResult<BS> = unused()
    override suspend fun updateBolusesNsIds(boluses: List<BS>): TransactionResult<BS> = unused()
    override suspend fun getNewestCarbs(): CA? = unused()
    override suspend fun getOldestCarbs(): CA? = unused()
    override suspend fun getLastCarbsId(): Long? = unused()
    override suspend fun getCarbsByNSId(nsId: String): CA? = unused()
    override suspend fun getCarbsFromTime(startTime: Long, ascending: Boolean): List<CA> = unused()
    override suspend fun getCarbsFromTimeIncludingInvalid(startTime: Long, ascending: Boolean): List<CA> = unused()
    override suspend fun getCarbsFromTimeExpanded(startTime: Long, ascending: Boolean): List<CA> = unused()
    override suspend fun getCarbsFromTimeNotExpanded(startTime: Long, ascending: Boolean): List<CA> = unused()
    override suspend fun getCarbsFromTimeToTimeExpanded(startTime: Long, endTime: Long, ascending: Boolean): List<CA> = unused()
    override suspend fun getNextSyncElementCarbs(id: Long): Pair<CA, CA>? = unused()
    override suspend fun insertOrUpdateCarbs(carbs: CA, action: Action, source: Sources, note: String?): TransactionResult<CA> = unused()
    override suspend fun insertPumpCarbsIfNewByTimestamp(carbs: CA): TransactionResult<CA> = unused()
    override suspend fun invalidateCarbs(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<CA> = unused()
    override suspend fun cutCarbs(id: Long, timestamp: Long): TransactionResult<CA> = unused()
    override suspend fun syncNsCarbs(carbs: List<CA>, doLog: Boolean): TransactionResult<CA> = unused()
    override suspend fun updateCarbsNsIds(carbs: List<CA>): TransactionResult<CA> = unused()
    override suspend fun getBolusCalculatorResultByNSId(nsId: String): BCR? = unused()
    override suspend fun getBolusCalculatorResultsFromTime(startTime: Long, ascending: Boolean): List<BCR> = unused()
    override suspend fun getBolusCalculatorResultsIncludingInvalidFromTime(startTime: Long, ascending: Boolean): List<BCR> = unused()
    override suspend fun getNextSyncElementBolusCalculatorResult(id: Long): Pair<BCR, BCR>? = unused()
    override suspend fun getLastBolusCalculatorResultId(): Long? = unused()
    override suspend fun insertOrUpdateBolusCalculatorResult(bolusCalculatorResult: BCR): TransactionResult<BCR> = unused()
    override suspend fun syncNsBolusCalculatorResults(bolusCalculatorResults: List<BCR>): TransactionResult<BCR> = unused()
    override suspend fun updateBolusCalculatorResultsNsIds(bolusCalculatorResults: List<BCR>): TransactionResult<BCR> = unused()
    override suspend fun invalidateBolusCalculatorResult(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<BCR> = unused()
    override suspend fun getLastGlucoseValue(): GV? = unused()
    override suspend fun getLastGlucoseValueId(): Long? = unused()
    override suspend fun getNextSyncElementGlucoseValue(id: Long): Pair<GV, GV>? = unused()
    override suspend fun getBgReadingsDataFromTimeToTime(start: Long, end: Long, ascending: Boolean): List<GV> = unused()
    override suspend fun getBgReadingsDataFromTime(timestamp: Long, ascending: Boolean): List<GV> = unused()
    override suspend fun getBgReadingByNSId(nsId: String): GV? = unused()
    override suspend fun invalidateGlucoseValue(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<GV> = unused()
    override suspend fun insertCgmSourceData(caller: Sources, glucoseValues: List<GV>, calibrations: List<Calibration>, sensorInsertionTime: Long?): TransactionResult<GV> = unused()
    override suspend fun updateGlucoseValuesNsIds(glucoseValues: List<GV>): TransactionResult<GV> = unused()
    override suspend fun getLastCalibrationEntryId(): Long? = unused()
    override suspend fun getNextSyncElementCalibrationEntry(id: Long): Pair<CAL, CAL>? = unused()
    override suspend fun getValidCalibrationEntriesSince(from: Long): List<CAL> = unused()
    override suspend fun getAllValidCalibrationEntries(): List<CAL> = unused()
    override suspend fun insertOrUpdateCalibrationEntry(calibrationEntry: CAL): TransactionResult<CAL> = unused()
    override suspend fun syncNsCalibrationEntries(calibrationEntries: List<CAL>): TransactionResult<CAL> = unused()
    override suspend fun invalidateCalibrationEntry(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<CAL> = unused()
    override suspend fun updateCalibrationEntriesNsIds(calibrationEntries: List<CAL>): TransactionResult<CAL> = unused()
    override suspend fun getEffectiveProfileSwitches(): List<EPS> = unused()
    override suspend fun getOldestEffectiveProfileSwitch(): EPS? = unused()
    override suspend fun getEffectiveProfileSwitchActiveAt(timestamp: Long): EPS? = unused()
    override suspend fun getEffectiveProfileSwitchByNSId(nsId: String): EPS? = unused()
    override suspend fun getEffectiveProfileSwitchesFromTime(startTime: Long, ascending: Boolean): List<EPS> = unused()
    override suspend fun getEffectiveProfileSwitchesIncludingInvalidFromTime(startTime: Long, ascending: Boolean): List<EPS> = unused()
    override suspend fun getEffectiveProfileSwitchesFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<EPS> = unused()
    override suspend fun getNextSyncElementEffectiveProfileSwitch(id: Long): Pair<EPS, EPS>? = unused()
    override suspend fun getLastEffectiveProfileSwitchId(): Long? = unused()
    override suspend fun insertOrUpdateEffectiveProfileSwitch(effectiveProfileSwitch: EPS): TransactionResult<EPS> = unused()
    override suspend fun invalidateEffectiveProfileSwitch(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<EPS> = unused()
    override suspend fun syncNsEffectiveProfileSwitches(effectiveProfileSwitches: List<EPS>, doLog: Boolean): TransactionResult<EPS> = unused()
    override suspend fun updateEffectiveProfileSwitchesNsIds(effectiveProfileSwitches: List<EPS>): TransactionResult<EPS> = unused()
    override suspend fun getProfileSwitchActiveAt(timestamp: Long): PS? = unused()
    override suspend fun getProfileSwitchByNSId(nsId: String): PS? = unused()
    override suspend fun getPermanentProfileSwitchActiveAt(timestamp: Long): PS? = unused()
    override suspend fun getProfileSwitches(): List<PS> = unused()
    override suspend fun getProfileSwitchesFromTime(startTime: Long, ascending: Boolean): List<PS> = unused()
    override suspend fun getProfileSwitchesIncludingInvalidFromTime(startTime: Long, ascending: Boolean): List<PS> = unused()
    override suspend fun getNextSyncElementProfileSwitch(id: Long): Pair<PS, PS>? = unused()
    override suspend fun getLastProfileSwitchId(): Long? = unused()
    override suspend fun insertOrUpdateProfileSwitch(profileSwitch: PS, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<PS> = unused()
    override suspend fun invalidateProfileSwitch(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<PS> = unused()
    override suspend fun cancelProfileSwitch(id: Long, timestamp: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>)): TransactionResult<PS> = unused()
    override suspend fun syncNsProfileSwitches(profileSwitches: List<PS>, doLog: Boolean): TransactionResult<PS> = unused()
    override suspend fun updateProfileSwitchesNsIds(profileSwitches: List<PS>): TransactionResult<PS> = unused()
    override suspend fun getRunningModeActiveAt(timestamp: Long): RM = unused()
    override suspend fun getRunningModeByNSId(nsId: String): RM? = unused()
    override suspend fun getPermanentRunningModeActiveAt(timestamp: Long): RM = unused()
    override suspend fun getRunningModes(): List<RM> = unused()
    override suspend fun getRunningModesFromTime(startTime: Long, ascending: Boolean): List<RM> = unused()
    override suspend fun getRunningModesFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<RM> = unused()
    override suspend fun getRunningModesIncludingInvalidFromTime(startTime: Long, ascending: Boolean): List<RM> = unused()
    override suspend fun getNextSyncElementRunningMode(id: Long): Pair<RM, RM>? = unused()
    override suspend fun getLastRunningModeId(): Long? = unused()
    override suspend fun cancelCurrentRunningMode(timestamp: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>)): TransactionResult<RM> = unused()
    override suspend fun cancelRunningMode(id: Long, timestamp: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>)): TransactionResult<RM> = unused()
    override suspend fun insertOrUpdateRunningMode(runningMode: RM, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<RM> = unused()
    override suspend fun invalidateRunningMode(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<RM> = unused()
    override suspend fun syncNsRunningModes(runningModes: List<RM>, doLog: Boolean): TransactionResult<RM> = unused()
    override suspend fun updateRunningModesNsIds(runningModes: List<RM>): TransactionResult<RM> = unused()
    override suspend fun getTemporaryBasalActiveAt(timestamp: Long): TB? = unused()
    override suspend fun getOldestTemporaryBasalRecord(): TB? = unused()
    override suspend fun getLastTemporaryBasalId(): Long? = unused()
    override suspend fun getTemporaryBasalByNSId(nsId: String): TB? = unused()
    override suspend fun getTemporaryBasalsActiveBetweenTimeAndTime(startTime: Long, endTime: Long): List<TB> = unused()
    override suspend fun getTemporaryBasalsStartingFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<TB> = unused()
    override suspend fun getTemporaryBasalsStartingFromTime(startTime: Long, ascending: Boolean): List<TB> = unused()
    override suspend fun getTemporaryBasalsStartingFromTimeIncludingInvalid(startTime: Long, ascending: Boolean): List<TB> = unused()
    override suspend fun getNextSyncElementTemporaryBasal(id: Long): Pair<TB, TB>? = unused()
    override suspend fun invalidateTemporaryBasal(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<TB> = unused()
    override suspend fun syncNsTemporaryBasals(temporaryBasals: List<TB>, doLog: Boolean): TransactionResult<TB> = unused()
    override suspend fun updateTemporaryBasalsNsIds(temporaryBasals: List<TB>): TransactionResult<TB> = unused()
    override suspend fun syncPumpTemporaryBasal(temporaryBasal: TB, type: TB.Type?): TransactionResult<TB> = unused()
    override suspend fun syncPumpCancelTemporaryBasalIfAny(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): TransactionResult<TB> = unused()
    override suspend fun syncPumpInvalidateTemporaryBasalWithTempId(temporaryId: Long): TransactionResult<TB> = unused()
    override suspend fun syncPumpInvalidateTemporaryBasalWithPumpId(pumpId: Long, pumpType: PumpType, pumpSerial: String): TransactionResult<TB> = unused()
    override suspend fun syncPumpTemporaryBasalWithTempId(temporaryBasal: TB, type: TB.Type?): TransactionResult<TB> = unused()
    override suspend fun insertTemporaryBasalWithTempId(temporaryBasal: TB): TransactionResult<TB> = unused()
    override suspend fun getExtendedBolusActiveAt(timestamp: Long): EB? = unused()
    override suspend fun getOldestExtendedBolusRecord(): EB? = unused()
    override suspend fun getLastExtendedBolusId(): Long? = unused()
    override suspend fun getExtendedBolusByNSId(nsId: String): EB? = unused()
    override suspend fun getExtendedBolusesStartingFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<EB> = unused()
    override suspend fun getExtendedBolusesStartingFromTime(startTime: Long, ascending: Boolean): List<EB> = unused()
    override suspend fun getExtendedBolusStartingFromTimeIncludingInvalid(startTime: Long, ascending: Boolean): List<EB> = unused()
    override suspend fun getNextSyncElementExtendedBolus(id: Long): Pair<EB, EB>? = unused()
    override suspend fun invalidateExtendedBolus(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<EB> = unused()
    override suspend fun syncNsExtendedBoluses(extendedBoluses: List<EB>, doLog: Boolean): TransactionResult<EB> = unused()
    override suspend fun updateExtendedBolusesNsIds(extendedBoluses: List<EB>): TransactionResult<EB> = unused()
    override suspend fun syncPumpExtendedBolus(extendedBolus: EB): TransactionResult<EB> = unused()
    override suspend fun syncPumpStopExtendedBolusWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): TransactionResult<EB> = unused()
    override suspend fun getTemporaryTargetActiveAt(timestamp: Long): TT? = unused()
    override suspend fun getLastTemporaryTargetId(): Long? = unused()
    override suspend fun getTemporaryTargetByNSId(nsId: String): TT? = unused()
    override suspend fun getTemporaryTargetDataFromTime(timestamp: Long, ascending: Boolean): List<TT> = unused()
    override suspend fun getTemporaryTargetDataIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<TT> = unused()
    override suspend fun getNextSyncElementTemporaryTarget(id: Long): Pair<TT, TT>? = unused()
    override suspend fun invalidateTemporaryTarget(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<TT> = unused()
    override suspend fun insertAndCancelCurrentTemporaryTarget(temporaryTarget: TT, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<TT> = unused()
    override suspend fun cancelCurrentTemporaryTargetIfAny(timestamp: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<TT> = unused()
    override suspend fun syncNsTemporaryTargets(temporaryTargets: List<TT>, doLog: Boolean): TransactionResult<TT> = unused()
    override suspend fun updateTemporaryTargetsNsIds(temporaryTargets: List<TT>): TransactionResult<TT> = unused()
    override suspend fun getLastTherapyEventId(): Long? = unused()
    override suspend fun getTherapyEventByNSId(nsId: String): TE? = unused()
    override suspend fun getLastTherapyRecordUpToNow(type: TE.Type): TE? = unused()
    override suspend fun getTherapyEventDataFromToTime(from: Long, to: Long): List<TE> = unused()
    override suspend fun getTherapyEventDataIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<TE> = unused()
    override suspend fun getTherapyEventDataFromTime(timestamp: Long, ascending: Boolean): List<TE> = events
    override suspend fun getTherapyEventDataFromTime(timestamp: Long, type: TE.Type, ascending: Boolean): List<TE> = unused()
    override suspend fun getNextSyncElementTherapyEvent(id: Long): Pair<TE, TE>? = unused()
    override suspend fun insertPumpTherapyEventIfNewByTimestamp( therapyEvent: TE, timestamp: Long).toEpochMilliseconds(), action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit> ): TransactionResult<TE> = unused()
    override suspend fun insertOrUpdateTherapyEvent(therapyEvent: TE): TransactionResult<TE> = unused()
    override suspend fun invalidateTherapyEvent(id: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>): TransactionResult<TE> = unused()
    override suspend fun cancelTherapyEvent(id: Long, timestamp: Long, action: Action, source: Sources, note: String?, listValues: List<ValueWithUnit>)): TransactionResult<TE> = unused()
    override suspend fun invalidateTherapyEventsWithNote(note: String, action: Action, source: Sources): TransactionResult<TE> = unused()
    override suspend fun syncNsTherapyEvents(therapyEvents: List<TE>, doLog: Boolean): TransactionResult<TE> = unused()
    override suspend fun updateTherapyEventsNsIds(therapyEvents: List<TE>): TransactionResult<TE> = unused()
    override suspend fun getNextSyncElementDeviceStatus(id: Long): DS? = unused()
    override suspend fun getLastDeviceStatusId(): Long? = unused()
    override suspend fun insertDeviceStatus(deviceStatus: DS) { unused() }
    override suspend fun updateDeviceStatusesNsIds(deviceStatuses: List<DS>): TransactionResult<DS> = unused()
    override suspend fun getHeartRatesFromTime(startTime: Long): List<HR> = unused()
    override suspend fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR> = unused()
    override suspend fun insertOrUpdateHeartRates(heartRates: List<HR>): TransactionResult<HR> = unused()
    override suspend fun getFoods(): List<FD> = unused()
    override suspend fun getNextSyncElementFood(id: Long): Pair<FD, FD>? = unused()
    override suspend fun getLastFoodId(): Long? = unused()
    override suspend fun insertOrUpdateFood(food: FD): TransactionResult<FD> = unused()
    override suspend fun invalidateFood(id: Long, action: Action, source: Sources): TransactionResult<FD> = unused()
    override suspend fun syncNsFood(foods: List<FD>): TransactionResult<FD> = unused()
    override suspend fun updateFoodsNsIds(foods: List<FD>): TransactionResult<FD> = unused()
    override suspend fun insertUserEntries(entries: List<UE>): TransactionResult<UE> = unused()
    override suspend fun getUserEntryDataFromTime(timestamp: Long): List<UE> = unused()
    override suspend fun getUserEntryFilteredDataFromTime(timestamp: Long): List<UE> = unused()
    override suspend fun deleteLastEventMatchingKeyword(noteKeyword: String) {}
    override suspend fun clearCachedTddData(timestamp: Long) { unused() }
    override suspend fun getLastTotalDailyDoses(count: Int, ascending: Boolean): List<TDD> = unused()
    override suspend fun getCalculatedTotalDailyDose(timestamp: Long): TDD? = unused()
    override suspend fun insertOrUpdateCachedTotalDailyDose(totalDailyDose: TDD): TransactionResult<TDD> = unused()
    override suspend fun insertOrUpdateTotalDailyDose(totalDailyDose: TDD): TransactionResult<TDD> = unused()
    override suspend fun getStepsCountFromTime(from: Long): List<SC> = unused()
    override suspend fun getStepsCountFromTimeToTime(startTime: Long, endTime: Long): List<SC> = unused()
    override suspend fun getLastStepsCountFromTimeToTime(startTime: Long, endTime: Long): SC? = unused()
    override suspend fun insertOrUpdateStepsCounts(stepsCounts: List<SC>): TransactionResult<SC> = unused()
    override suspend fun insertVersionChangeIfChanged(versionName: String, versionCode: Int, gitRemote: String?, commitHash: String?) { unused() }
    override suspend fun collectNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int): NE = unused()
    override suspend fun getApsResultCloseTo(timestamp: Long): APSResult? = unused()
    override suspend fun getApsResults(start: Long, end: Long): List<APSResult> = unused()
    override suspend fun insertOrUpdateApsResult(apsResult: APSResult): TransactionResult<APSResult> = unused()
    override suspend fun getGlucoseValueByPumpIdAndSource(source: SourceSensor, pumpId: Long): GV? = unused()
    override suspend fun getGlucoseValuesByPumpIdRange(source: SourceSensor, startPumpId: Long, endPumpId: Long): List<GV> = unused()
}
