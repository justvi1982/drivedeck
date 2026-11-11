package app.drivedeck

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Android only lets an app read other apps' "now playing" sessions if it is an
 * enabled notification listener, so this service mainly holds that permission.
 *
 * It also keeps the latest notification from ONE app — the one chosen for the
 * small tile (Flitsmeister by default) — so the tile can show its alerts.
 * Notifications from every other app are ignored and nothing is stored on disk.
 */
class MediaListener : NotificationListenerService() {

    data class Alert(val title: String, val text: String, val ongoing: Boolean)

    companion object {
        @Volatile
        var latest: Alert? = null
            private set

        private var instance: MediaListener? = null
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }

        /** Call after the tile's app changes. */
        fun rebuild() { instance?.rebuildNow() ?: run { latest = null; notifyListeners() } }

        private fun notifyListeners() = main.post { listeners.forEach { it() } }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        rebuildNow()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == tilePackage()) rebuildNow()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == tilePackage()) rebuildNow()
    }

    private fun tilePackage(): String? = Prefs.tileApp(Prefs.get(this))

    private fun rebuildNow() {
        val pkg = tilePackage()
        val mine: List<StatusBarNotification> = if (pkg == null) emptyList() else try {
            activeNotifications?.filter { it.packageName == pkg }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        // A real alert beats the app's permanent "running" notification; newest first.
        val pick = mine.filter { !it.isOngoing }.maxByOrNull { it.postTime }
            ?: mine.maxByOrNull { it.postTime }
        latest = pick?.let { toAlert(it) }
        notifyListeners()
    }

    private fun toAlert(sbn: StatusBarNotification): Alert? {
        val ex = sbn.notification.extras ?: return null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return Alert(title.trim(), text.trim(), sbn.isOngoing)
    }
}
