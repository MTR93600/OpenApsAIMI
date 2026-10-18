package app.aaps.pump.medtrum.ios

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The plugin wrapper around the iOS Medtrum driver.
 *
 * The driver itself ([IosMedtrumPump]) has existed for a while and is covered by tests, but nothing
 * registered it as a plugin, so `ActivePlugin.activePump` on iOS could only ever be the virtual
 * pump. This class is that registration. All of [Pump] is delegated to the driver through
 * [IosMedtrumDriverHost], so this file holds plugin lifecycle and nothing about therapy.
 *
 * ## It is switched off on purpose, and cannot be switched on
 *
 * The plugin declares [EnforcedState.Disabled], so the user cannot select it and the loop can never
 * reach it. That is not a placeholder, it is the honest answer to a missing piece: **an iPhone
 * cannot start a Medtrum patch.** On Android the user types the pump serial number in the driver's
 * settings and then runs a wizard that fills, primes and activates the patch. Neither exists here:
 *
 * - there is no screen to enter the serial number, so `MedtrumSessionState.pumpSN` stays `0` and
 *   the transport can never match a pump in an advertisement;
 * - nothing calls `MedtrumSession.prime` or `MedtrumSession.activate`, so a fresh patch stays dead;
 * - nothing calls `MedtrumSession.deactivatePatch`, so a patch could not be ended from here either.
 *
 * A pump the user can pick but that can never pair is the worst shape this could take: the loop
 * would look closed and no insulin would be delivered. So the plugin is listed, visibly locked, and
 * says why in its own description. Lifting the enforcement is one line, and the work it waits for
 * is a serial number input and a patch activation flow.
 *
 * ## Keys and buckets
 *
 * `@MetroIntKey(1120)` is the same number the Android `MedtrumPlugin` uses, so the two platforms
 * order Medtrum in the same place. They never meet: this file is in `iosMain` and the Android one
 * is in `pump/medtrum/src/main`.
 *
 * No `@PumpDriver` qualifier, unlike Android. `IosAppGraph` merges only the unqualified
 * `Map<Int, PluginBase>` bucket - an iOS build is a follower client - so a qualified entry would
 * compile and then simply not be in the list.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(1120)
@SingleIn(AppScope::class)
@Inject
class IosMedtrumPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    preferences: Preferences,
    commandQueue: CommandQueue,
    notificationManager: NotificationManager,
    private val host: IosMedtrumDriverHost
) : PumpPluginBase(
    pluginDescription = medtrumIosPluginDescription(),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.driver {

    /**
     * Opens the radio once the plugin is really running.
     *
     * Not reachable while the enforcement above holds - a forced-off plugin is stopped, never
     * started - and kept correct so that lifting the enforcement needs no second change here.
     */
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "IosMedtrumPlugin onStart()")
        host.openRadio()
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "IosMedtrumPlugin onStop()")
        host.shutdown()
        super.onStop()
    }
}

/** The pump brand, so it is not translated. */
const val MEDTRUM_IOS_PLUGIN_NAME: String = "Medtrum"

/**
 * Shown under the plugin name in the config builder, next to the locked switch.
 *
 * A literal rather than a translated resource: this module has no string table of its own yet, and
 * adding one would be a bigger change than the text is worth while the plugin cannot be switched
 * on. It has to become a real resource before the enforcement is lifted.
 */
const val MEDTRUM_IOS_NOT_READY_REASON: String =
    "Medtrum patch pump. Not usable on iPhone yet: this app cannot enter the pump serial number " +
        "and cannot start a patch, so it can never connect to your pump. The switch stays off " +
        "until that is built."

/**
 * What the plugin tells the rest of the app about itself.
 *
 * A function rather than an expression inside the constructor call so that a test can read the
 * enforcement without building the plugin and the radio behind it.
 */
fun medtrumIosPluginDescription(): PluginDescription = PluginDescription()
    .mainType(PluginType.PUMP)
    .pluginName(TextRef.Literal(MEDTRUM_IOS_PLUGIN_NAME))
    .description(TextRef.Literal(MEDTRUM_IOS_NOT_READY_REASON))
    .enforce(EnforcedState.Disabled, TextRef.Literal(MEDTRUM_IOS_NOT_READY_REASON))
