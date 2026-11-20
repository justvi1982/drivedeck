package app.drivedeck

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.decklink.Link

/**
 * Appears as "Play in the back" in YouTube's Share menu. Sends the video to the back
 * screen and returns straight to YouTube, which keeps playing at the front.
 */
class ShareToBackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val id = Link.youtubeId(text)
        if (id == null) {
            Toast.makeText(this, R.string.rear_not_youtube, Toast.LENGTH_LONG).show()
        } else {
            RearLink.startYouTube(this, id, Link.youtubeStartMs(text))
            val backs = RearLink.connectedBacks()
            val msg = if (backs.isEmpty()) getString(R.string.rear_sent_searching)
            else getString(R.string.rear_sent, backs.joinToString { it.lowercase().replaceFirstChar { c -> c.titlecase() } })
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
