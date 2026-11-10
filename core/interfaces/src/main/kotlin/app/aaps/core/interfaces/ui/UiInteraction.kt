package app.aaps.core.interfaces.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentActivity
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import app.aaps.core.interfaces.R

/**
 * Interface to use activities located in different modules
 * usage: startActivity(Intent(context, activityNames.xxxx))
 */
interface UiInteraction {

    companion object {

        /** Intent extra: plugin [Class.simpleName] for plugin-specific preference screens. */
        const val PLUGIN_NAME = "plugin"
    }

    /** The main activity of the application. */
    val mainActivity: Class<*>

    /** The activity for displaying error information. */
    val errorHelperActivity: Class<*>

    /**
     * Display names for units preferences.
     */
    val unitsEntries: Array<CharSequence>

    /**
     * Value names for units preferences.
     */
    val unitsValues: Array<CharSequence>

    /**
     * Show ErrorHelperActivity and start alarm.
     * @param status message inside dialog
     * @param title title of dialog
     * @param soundId sound resource. if == 0 alarm is not started
     */
    fun runAlarm(status: String, title: String, @RawRes soundId: Int = 0)

    /**
     * Defines modes for the site rotation dialog.
     */
    enum class SiteMode(val i: Int) {

        /** View existing site change history. */
        VIEW(1),

        /** Record a new site change. */
        EDIT(2)
    }

    /**
     * Defines types of care portal events.
     */
    enum class EventType {

        /** A blood glucose check. */
        BGCHECK,

        /** A CGM sensor insertion. */
        SENSOR_INSERT,

        /** A pump battery change. */
        BATTERY_CHANGE,

        /** A general note. */
        NOTE,

        /** An exercise event. */
        EXERCISE,

        /** A question/prompt. */
        QUESTION,

        /** An announcement. */
        ANNOUNCEMENT
    }

    /**
     * Posts a sound-bearing notification for an in-app notification (overview/dashboard list).
     * Replaces the old foreground AlarmSoundService path.
     */
    fun postNotificationSoundAlarm(notificationKey: Int, @RawRes soundId: Int, title: String, body: String, urgent: Boolean)

    /**
     * Cancels the sound notification for a single in-app notification key without stopping other active alarms.
     */
    fun cancelNotificationSoundAlarm(notificationKey: Int)

    /**
     * Stops any currently playing alarm (cancels FSI + all sound notifications).
     * Per-AAPS-notification cancellation happens internally inside the implementation module.
     * @param reason A string describing why the alarm is being stopped.
     */
    fun stopAlarm(reason: String)

    /** *******************************************************************************
     * Displays a simple alert dialog with a title, a message, and an OK button.
     *
     * @param context The context to use for displaying the dialog.
     * @param title The title of the dialog.
     * @param message The message to display in the dialog. HTML formatted text is accepted.
     * @param onFinish The action to perform when the OK button is clicked or the dialog is dismissed. Run in UI thread.
     */
    fun showOkDialog(context: Context, title: String, message: String, onFinish: (() -> Unit)? = null)

    /** @see showOkDialog */
    fun showOkDialog(context: Context, @StringRes title: Int, @StringRes message: Int, onFinish: (() -> Unit)? = null)

    /**
     * Displays a confirmation dialog with a title, a message, and OK/Cancel buttons.
     *
     * @param context The host activity.
     * @param title The title of the dialog.
     * @param message The message to display in the dialog. HTML formatted text is accepted.
     * @param ok The action to perform when the OK button is clicked. Run in UI thread.
     * @param cancel The action to perform when the Cancel button is clicked or the dialog is dismissed. Run in UI thread.
     * @param icon Add icon if providec
     */
    fun showOkCancelDialog(context: Context, @StringRes title: Int = R.string.confirmation, @StringRes message: Int, ok: (() -> Unit)?, cancel: (() -> Unit)? = null, @DrawableRes icon: Int? = null)

    /** @see showOkCancelDialog */
    fun showOkCancelDialog(context: Context, title: String = context.getString(R.string.confirmation), message: String, ok: (() -> Unit)?, cancel: (() -> Unit)? = null, @DrawableRes icon: Int? = null)

    /**
     * Displays an alert dialog with a title, two messages, a custom icon, and OK/Cancel buttons.
     *
     * @param context The context to use for displaying the dialog.
     * @param title The title of the dialog.
     * @param message The primary message to display in the dialog. HTML formatted text is accepted.
     * @param secondMessage The secondary message to display in the dialog (styled with accent color).
     * @param ok The action to perform when the OK button is clicked. Run in UI thread.
     * @param cancel The action to perform when the Cancel button is clicked or the dialog is dismissed. Run in UI thread.
     * @param icon The drawable resource ID for the custom icon. Defaults to a check icon if null.1
     */
    fun showOkCancelDialog(context: Context, title: String = context.getString(R.string.confirmation), message: String, secondMessage: String, ok: (() -> Unit)?, cancel: (() -> Unit)? = null, @DrawableRes icon: Int? = null)

    /**
     * Displays a dialog with a title, a message, and Yes/No/Cancel buttons.
     *
     * @param context The context to use for displaying the dialog.
     * @param title The title of the dialog.
     * @param message The message to display in the dialog. HTML formatted text is accepted.
     * @param yes The action to perform when the Yes button is clicked. Run in UI thread.
     * @param no The action to perform when the No button is clicked. The dialog is dismissed on cancel. Run in UI thread.
     */
    fun showYesNoCancel(context: Context, @StringRes title: Int, @StringRes message: Int, yes: (() -> Unit)?, no: (() -> Unit)? = null)

    /** @see showYesNoCancel */
    fun showYesNoCancel(context: Context, title: String, message: String, yes: (() -> Unit)?, no: (() -> Unit)? = null)

    /**
     * Displays a error dialog with a title, a message, a warning icon, and Dismiss/optional positive button.
     *
     * @param context The context to use for displaying the dialog.
     * @param title The title of the dialog.
     * @param message The message to display in the dialog. HTML formatted text is accepted.
     * @param positiveButton The resource ID for the positive button text, or -1 if no positive button.
     * @param ok The action to perform when the positive button is clicked. Run in UI thread.
     * @param cancel The action to perform when the Dismiss button is clicked or the dialog is dismissed. Run in UI thread.
     */
    fun showError(context: Context, title: String, message: String, @StringRes positiveButton: Int? = null, ok: (() -> Unit)? = null, cancel: (() -> Unit)? = null)

    /** Opens running mode management in the Compose-based main activity. */
    fun openRunningModeScreen(activity: FragmentActivity)

    /** Opens the insulin bolus screen in the Compose main activity. */
    fun openInsulinScreen(activity: FragmentActivity)

    /** Opens temp target management in the Compose main activity. */
    fun openTempTargetManagementScreen(activity: FragmentActivity)

    /** Opens profile management in the Compose main activity. */
    fun openProfileManagementScreen(activity: FragmentActivity)

    /** Opens the profile activation / switch flow in the Compose main activity (legacy long-press on active profile). */
    fun openProfileActivationScreen(activity: FragmentActivity, profileIndex: Int = 0)

    /** Opens plugin preferences in the Compose-based preference UI. */
    fun runPreferencesForPlugin(activity: FragmentActivity, pluginSimpleName: String)

    /** Brings [mainActivity] to front and requests navigation to the given route (see Compose host extras). */
    fun openComposeMainAtRoute(context: Context, navRoute: String)
}
