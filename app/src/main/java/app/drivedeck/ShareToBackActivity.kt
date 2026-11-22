package app.drivedeck

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Appears as "Play in the back" in the Share menu of YouTube, Netflix and Prime. Sends the
 * video or title to the back screen (together or back only, as set in the panel) and returns.
 */
class ShareToBackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        BackActions.sendLink(this, text, backToYouTube = false)
        finish()
    }
}
