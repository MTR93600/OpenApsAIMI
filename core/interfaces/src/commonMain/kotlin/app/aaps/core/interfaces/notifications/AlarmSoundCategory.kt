package app.aaps.core.interfaces.notifications

/**
 * Which user sound choice applies to an alarm.
 *
 * - [LOW_GLUCOSE]: the low-glucose alarms, which have their own soft melody choice.
 * - [OTHER]: every other urgent alarm, which share the second melody choice.
 * - [CHOSEN]: the user chose the sound of this one alarm (a Trio sound of a CGM alarm). That
 *   choice wins: no melody replaces it.
 */
enum class AlarmSoundCategory { LOW_GLUCOSE, OTHER, CHOSEN }
