package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * The health data exists, but the platform does not let the app read it right now.
 *
 * On an iPhone, HealthKit data is encrypted while the phone is locked, and every read then fails
 * with "Protected health data is inaccessible". That is not "no data": a run that went on would read
 * sleep, HRV and resting heart rate as missing, store an UNKNOWN context over the last good one, and
 * add an empty point to the rolling baseline. So the data source throws this, [PhysioPipeline.run]
 * stops before it stores anything and passes it on, and the caller tries again once the phone is
 * unlocked.
 *
 * Android never throws it: Health Connect can be read with the screen locked.
 */
class PhysioDataLockedException(message: String) : IllegalStateException(message)
