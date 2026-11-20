package app.kidsdeck

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent

/**
 * Android only lets an app pause or start other apps' video (Netflix, Prime…) if it is an
 * enabled notification listener. This service only holds that permission; it reads nothing.
 */
class MediaAccess : NotificationListenerService() {

    companion object {
        fun enabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
        }

        /** Play or pause what's playing here, preferring [pkg]. Falls back to media keys. */
        fun control(context: Context, play: Boolean, pkg: String?) {
            if (enabled(context)) {
                try {
                    val msm = context.getSystemService(MediaSessionManager::class.java)
                    val list: List<MediaController> =
                        msm?.getActiveSessions(ComponentName(context, MediaAccess::class.java)).orEmpty()
                    val c = list.firstOrNull { it.packageName == pkg }
                        ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: list.firstOrNull()
                    if (c != null) {
                        if (play) c.transportControls.play() else c.transportControls.pause()
                        return
                    }
                } catch (e: Exception) {
                }
            }
            val am = context.getSystemService(AudioManager::class.java) ?: return
            val code = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }
}
