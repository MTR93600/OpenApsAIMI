package app.aaps.implementation.androidNotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.alerts.LocalAlertUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Handles the "Hypo treated" action on the low-glucose alarm notification: holds the alarm for the
 * time carbs need to work and clears the current alert, through
 * [LocalAlertUtils.snoozeHypoAfterTreatment].
 *
 * The action lives on the Android notification because that is the only surface the user sees during
 * a real hypo — the phone is usually locked and the app is not open.
 *
 * Reaches [LocalAlertUtils] through a Hilt entry point since a manifest [BroadcastReceiver] is not
 * itself injected (same pattern as [AlarmMuteReceiver]).
 */
class HypoTreatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        EntryPointAccessors.fromApplication(context.applicationContext, Entry::class.java)
            .localAlertUtils()
            .snoozeHypoAfterTreatment()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {

        fun localAlertUtils(): LocalAlertUtils
    }
}
