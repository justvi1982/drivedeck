package app.drivedeck

import android.service.notification.NotificationListenerService

/**
 * Android only lets an app read other apps' "now playing" sessions if it is
 * an enabled notification listener. This service exists purely to hold that
 * permission; it doesn't read or store any notifications.
 */
class MediaListener : NotificationListenerService()
