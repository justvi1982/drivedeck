package app.kidsdeck

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import app.decklink.Link

/** Full-screen YouTube player that follows the front. */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO = "video"
        private const val ORIGIN = "https://kidsdeck.app"
        private const val SYNC_MS = 1000L

        @Volatile var current: PlayerActivity? = null
            private set
    }

    var videoId: String = ""
        private set

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private var ready = false

    private val tick = object : Runnable {
        override fun run() {
            LinkService.yt?.takeIf { it.videoId == videoId }?.let { sync(it) }
            handler.postDelayed(this, SYNC_MS)
        }
    }
    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(250).start() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        web = findViewById(R.id.web)
        overlay = findViewById(R.id.overlay)
        overlayText = findViewById(R.id.overlayText)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(0xFF000000.toInt())
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")

        findViewById<View>(R.id.touch).setOnClickListener { showOverlay() }
        findViewById<View>(R.id.btnClose).setOnClickListener {
            LinkService.dismissedVideo = videoId
            finish()
        }

        current = this
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        try { web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun load(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_VIDEO) ?: run { finish(); return }
        if (id == videoId && ready) return
        videoId = id
        ready = false
        val startSec = ((LinkService.yt?.takeIf { it.videoId == id }?.positionNow() ?: 0L) / 1000L).coerceAtLeast(0L)
        val html = assets.open("player.html").bufferedReader().use { it.readText() }
            .replace("__VIDEO__", id)
            .replace("__START__", startSec.toString())
            .replace("__ORIGIN__", ORIGIN)
        web.loadDataWithBaseURL("$ORIGIN/", html, "text/html", "utf-8", null)
        showOverlay()
    }

    /** Called about once a second with the front's latest state. */
    fun sync(state: LinkService.YtState) {
        if (!ready || state.videoId != videoId) return
        web.evaluateJavascript("sync(${state.positionNow()}, ${state.playing})", null)
    }

    private fun showOverlay() {
        overlayText.setText(R.string.playing_along)
        overlay.animate().alpha(1f).setDuration(180).start()
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3500)
    }

    /** Some videos may not play outside YouTube: open them in the YouTube app instead. */
    private fun openInYouTubeApp() {
        LinkService.inAppVideo = videoId
        val sec = (LinkService.yt?.positionNow() ?: 0L) / 1000L
        val url = Uri.parse("https://www.youtube.com/watch?v=$videoId&t=${sec}s")
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Apps.isInstalled(this, Link.YOUTUBE)) intent.setPackage(Link.YOUTUBE)
        try { startActivity(intent) } catch (e: Exception) {}
        finish()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            handler.post {
                ready = true
                LinkService.yt?.takeIf { it.videoId == videoId }?.let { sync(it) }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            // 100 removed/private, 101/150 owner blocks embedding, 152/153 embed refused.
            handler.post { if (code != 2 && code != 5) openInYouTubeApp() }
        }

        @JavascriptInterface
        fun onState(state: Int) {
            // 0 = ended: go back to the home screen.
            if (state == 0) handler.postDelayed({
                LinkService.dismissedVideo = videoId
                finish()
            }, 1500)
        }
    }
}
