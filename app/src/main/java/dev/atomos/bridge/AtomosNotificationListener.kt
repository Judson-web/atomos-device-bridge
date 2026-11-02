package dev.atomos.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Notification access is opt-in. No notification contents are stored in this starter. */
class AtomosNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) { /* Future: expose only on explicit request. */ }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
