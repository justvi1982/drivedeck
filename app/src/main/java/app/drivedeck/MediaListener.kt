package app.drivedeck

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Android only lets an app read other apps' "now playing" sessions if it is an
 * enabled notification listener, so this service mainly holds that permission.
 *
 * It also keeps the latest notification from exactly two apps:
 *  - the small tile's app (Flitsmeister by default), to show its alerts
 *  - the navigation app (e.g. Google Maps), to show the next turn on the map
 * Notifications from every other app are ignored, and nothing is stored on disk.
 */
class MediaListener : NotificationListenerService() {

    data class Alert(
        val title: String,
        val text: String,
        val sub: String,
        val ongoing: Boolean,
        val icon: Icon?,
    )

    companion object {
        @Volatile
        var tile: Alert? = null
            private set

        @Volatile
        var nav: Alert? = null
            private set

        private var instance: MediaListener? = null
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())

        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }

        /** Call after the tile's or navigation app changes. */
        fun rebuild() {
            val s = instance
            if (s != null) s.rebuildNow() else { tile = null; nav = null; notifyListeners() }
        }

        private fun notifyListeners() { main.post { listeners.forEach { it() } } }
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
        if (isWatched(sbn.packageName)) rebuildNow()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isWatched(sbn.packageName)) rebuildNow()
    }

    private fun isWatched(pkg: String): Boolean =
        pkg == Prefs.tileApp(Prefs.get(this)) || pkg == Apps.navPackage(this)

    private fun rebuildNow() {
        val all: List<StatusBarNotification> = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        val tilePkg = Prefs.tileApp(Prefs.get(this))
        val mine = all.filter { it.packageName == tilePkg }
        // A real alert beats the app's permanent "running" notification; newest first.
        val tilePick = mine.filter { !it.isOngoing }.maxByOrNull { it.postTime }
            ?: mine.maxByOrNull { it.postTime }
        tile = tilePick?.let { toAlert(it) }

        val navPkg = Apps.navPackage(this)
        val navs = all.filter { it.packageName == navPkg }
        // Turn-by-turn guidance is the app's ongoing "navigation" notification.
        val navPick = navs.filter { it.notification.category == Notification.CATEGORY_NAVIGATION }
            .maxByOrNull { it.postTime }
            ?: navs.filter { it.isOngoing }.maxByOrNull { it.postTime }
        nav = navPick?.let { toAlert(it) }

        notifyListeners()
    }

    private fun toAlert(sbn: StatusBarNotification): Alert? {
        val n = sbn.notification
        val ex = n.extras ?: return null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return Alert(title.trim(), text.trim(), sub.trim(), sbn.isOngoing, n.getLargeIcon())
    }
}
