package app.aaps.ui.dialogs

import android.app.Dialog
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.dialogs.ErrorDialog
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.dialogs.YesNoCancelDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlertDialogs(
    private val preferences: Preferences,
    @Suppress("unused") private val rxBus: RxBus
) {

    private class ComposeDialogOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val _viewModelStore = ViewModelStore()
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = _viewModelStore
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            _viewModelStore.clear()
        }
    }

    fun showOkDialog(context: Context, title: String, message: String, onFinish: (() -> Unit)? = null) {
        showOkComposeDialog(context, title, message, onFinish)
    }

    fun showOkDialog(context: Context, @StringRes title: Int, @StringRes message: Int, onFinish: (() -> Unit)? = null) {
        showOkComposeDialog(context, context.getString(title), context.getString(message), onFinish)
    }

    fun showOkCancelDialog(context: Context, @StringRes title: Int, @StringRes message: Int, ok: (() -> Unit)?, cancel: (() -> Unit)?, @DrawableRes icon: Int?) {
        showOkCancelComposeDialog(context, context.getString(title), context.getString(message), null, ok, cancel, icon)
    }

    fun showOkCancelDialog(context: Context, title: String, message: String, ok: (() -> Unit)?, cancel: (() -> Unit)?, @DrawableRes icon: Int?) {
        showOkCancelComposeDialog(context, title, message, null, ok, cancel, icon)
    }

    fun showOkCancelDialog(context: Context, title: String, message: String, secondMessage: String, ok: (() -> Unit)?, cancel: (() -> Unit)?, @DrawableRes icon: Int?) {
        showOkCancelComposeDialog(context, title, message, secondMessage, ok, cancel, icon)
    }

    fun showYesNoCancel(context: Context, @StringRes title: Int, @StringRes message: Int, yes: (() -> Unit)?, no: (() -> Unit)? = null) {
        showYesNoCancelComposeDialog(context, context.getString(title), context.getString(message), yes, no)
    }

    fun showYesNoCancel(context: Context, title: String, message: String, yes: (() -> Unit)?, no: (() -> Unit)? = null) {
        showYesNoCancelComposeDialog(context, title, message, yes, no)
    }

    fun showError(context: Context, title: String, message: String, @StringRes positiveButton: Int?, ok: (() -> Unit)? = null, cancel: (() -> Unit)? = null) {
        showErrorComposeDialog(context, title, message, positiveButton, ok, cancel)
    }

    private fun showOkComposeDialog(context: Context, title: String, message: String, onFinish: (() -> Unit)?) {
        val dialog = Dialog(context)
        val owner = ComposeDialogOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
            setContent {
                CompositionLocalProvider(LocalPreferences provides preferences) {
                    AapsTheme {
                        OkDialog(
                            title = title.ifEmpty { context.getString(R.string.message) },
                            message = message,
                            onDismiss = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    onFinish?.invoke()
                                }
                            }
                        )
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { owner.destroy() }
        dialog.show()
    }

    private fun showYesNoCancelComposeDialog(context: Context, title: String, message: String, yes: (() -> Unit)?, no: (() -> Unit)?) {
        val dialog = Dialog(context)
        val owner = ComposeDialogOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
            setContent {
                CompositionLocalProvider(LocalPreferences provides preferences) {
                    AapsTheme {
                        YesNoCancelDialog(
                            title = title,
                            message = message,
                            onYes = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    yes?.invoke()
                                }
                            },
                            onNo = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    no?.invoke()
                                }
                            },
                            onCancel = { dialog.dismiss() }
                        )
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { owner.destroy() }
        dialog.show()
    }

    private fun showErrorComposeDialog(context: Context, title: String, message: String, @StringRes positiveButton: Int?, ok: (() -> Unit)?, cancel: (() -> Unit)?) {
        val dialog = Dialog(context)
        val owner = ComposeDialogOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
            setContent {
                CompositionLocalProvider(LocalPreferences provides preferences) {
                    AapsTheme {
                        ErrorDialog(
                            title = title,
                            message = message,
                            positiveButton = positiveButton?.let { context.getString(it) },
                            onDismiss = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    cancel?.invoke()
                                }
                            },
                            onPositive = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    ok?.invoke()
                                }
                            }
                        )
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { owner.destroy() }
        dialog.show()
    }

    private fun showOkCancelComposeDialog(
        context: Context,
        title: String,
        message: String,
        secondMessage: String?,
        ok: (() -> Unit)?,
        cancel: (() -> Unit)?,
        @DrawableRes icon: Int?
    ) {
        val dialog = Dialog(context)
        val owner = ComposeDialogOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
            setContent {
                CompositionLocalProvider(LocalPreferences provides preferences) {
                    AapsTheme {
                        OkCancelDialog(
                            title = title,
                            message = message,
                            secondMessage = secondMessage,
                            onConfirm = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    ok?.invoke()
                                }
                            },
                            onDismiss = {
                                dialog.dismiss()
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(100)
                                    cancel?.invoke()
                                }
                            }
                        )
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { owner.destroy() }
        dialog.show()
    }
}
