package app.kidsdeck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts listening for DriveDeck as soon as the screen has started. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkService.start(context)
    }
}
