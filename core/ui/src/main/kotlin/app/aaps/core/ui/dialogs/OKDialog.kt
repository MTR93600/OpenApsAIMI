package app.aaps.core.ui.dialogs

import android.content.Context
import android.content.DialogInterface
import android.text.Spanned
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity

/**
 * Simple OK / confirmation dialogs for the View-based UI.
 */
object OKDialog {

    fun show(context: Context, title: String, message: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun show(activity: FragmentActivity, title: String, message: String) {
        show(activity as Context, title, message)
    }

    fun show(activity: FragmentActivity, title: String, message: String, onOk: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .show()
    }

    fun show(context: Context, title: String, message: Spanned, html: Boolean, onOk: Runnable?) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk?.run() }
            .show()
    }

    fun show(
        context: Context,
        title: String,
        message: String,
        @Suppress("UNUSED_PARAMETER") runOnDismiss: Boolean,
        onDismiss: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    fun show(activity: FragmentActivity, title: String, message: Spanned, html: Boolean, onOk: Runnable?) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk?.run() }
            .show()
    }

    fun showConfirmation(activity: FragmentActivity, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showConfirmation(activity: FragmentActivity, message: String, onConfirm: Runnable) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm.run() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showConfirmation(activity: FragmentActivity, message: Spanned, onConfirm: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showConfirmation(
        activity: FragmentActivity,
        title: String,
        message: Spanned,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    fun showConfirmation(
        activity: FragmentActivity,
        title: String,
        message: String,
        onConfirm: Runnable,
        onCancel: Runnable
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm.run() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel.run() }
            .show()
    }

    fun showConfirmation(context: Context, message: Spanned, onConfirm: () -> Unit, onCancel: () -> Unit) {
        AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    fun showConfirmation(
        context: Context,
        title: String,
        message: Spanned,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    fun showConfirmation(context: Context, title: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }

    fun showConfirmation(
        context: Context,
        title: String,
        message: String,
        onConfirm: DialogInterface.OnClickListener,
        onCancel: DialogInterface.OnClickListener
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, onConfirm)
            .setNegativeButton(android.R.string.cancel, onCancel)
            .show()
    }

    fun showYesNoCancel(
        context: Context,
        title: String,
        message: String,
        onYes: () -> Unit,
        onNo: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onNo() }
            .show()
    }
}
