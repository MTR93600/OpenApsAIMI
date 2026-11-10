package app.aaps.core.interfaces.rx.events

import android.content.Context

/** Fired when the user selects the AAPS directory (SAF tree). */
class EventAAPSDirectorySelected(val status: String) : EventStatus() {

    override fun getStatus(context: Context): String = status
}
