package app.aaps.plugins.main.general.overview.notifications

import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Helper used by both Overview and Dashboard to keep the notification list in sync with the
 * notification store. This centralises the subscription logic so both screens react to the same
 * update events without duplicating code.
 */
class NotificationUiBinder @Inject constructor(
    private val notificationStore: NotificationStore,
    private val aapsSchedulers: AapsSchedulers,
    private val fabricPrivacy: FabricPrivacy,
) {

    fun bind(
        overviewBus: RxBus,
        notificationsView: RecyclerView,
        disposable: CompositeDisposable,
    ) {
        // Ensure the current content is visible immediately when the fragment is shown.
        notificationStore.updateNotifications(notificationsView)

        disposable += overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                notificationStore.updateNotifications(notificationsView)
            }, fabricPrivacy::logException)
    }

    fun bindCompose(
        overviewBus: RxBus,
        onSnapshot: (List<NotificationStore.NotificationComposeItem>) -> Unit,
        disposable: CompositeDisposable,
    ) {
        onSnapshot(notificationStore.snapshotForCompose())
        disposable += overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                onSnapshot(notificationStore.snapshotForCompose())
            }, fabricPrivacy::logException)
    }

    fun dismissCompose(id: Int): List<NotificationStore.NotificationComposeItem> {
        notificationStore.dismissFromCompose(id)
        return notificationStore.snapshotForCompose()
    }
}
