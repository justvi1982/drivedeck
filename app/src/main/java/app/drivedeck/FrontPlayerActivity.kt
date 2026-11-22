package app.drivedeck

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * DriveDeck's own YouTube player for the front (experimental).
 *
 * The car plays the TBox's sound a little late, so in the YouTube app the picture runs
 * ahead of the sound. This player plays the sound with one hidden player and shows the
 * picture with a second, muted one that runs "sound delay" behind it, so they line up.
 * In Together mode the back screens follow this player too.
 */
class FrontPlayerActivity : Activity() {

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_START = "start"
        private const val ORIGIN = "https://drivedeck.app"

        /** What's playing here: the back follows this in Together mode. */
        @Volatile var videoId: String? = null
            private set
        @Volatile var positionMs = 0L
            private set
        @Volatile var playing = false
            private set
        @Volatile var updatedAt = 0L
            private set

        /** Where the sound is now. */
        fun positionNow(): Long =
            if (playing) positionMs + (SystemClock.elapsedRealtime() - updatedAt) else positionMs

        fun start(context: Context, video: String, startMs: Long) {
            context.startActivity(
                Intent(context, FrontPlayerActivity::class.java)
                    .putExtra(EXTRA_VIDEO, video)
                    .putExtra(EXTRA_START, startMs)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var controls: LinearLayout
    private lateinit var playBtn: TextView
    private lateinit var delayText: TextView
    private var loadedVideo: String? = null
    private val hideControls = Runnable { controls.animate().alpha(0f).setDuration(250).withEndAction { controls.visibility = View.GONE }.start() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RearLink.init(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "Android")
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Taps anywhere show the controls.
        val touch = View(this).apply { setOnClickListener { showControls() } }
        root.addView(touch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        controls = buildControls()
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
        setContentView(root)
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
    }

    override fun onDestroy() {
        // Stop the back too, if it was following this player.
        if (RearLink.ytVideo != null && RearLink.ytVideo == videoId) RearLink.stopTogether(this)
        videoId = null
        playing = false
        handler.removeCallbacksAndMessages(null)
        try { web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun load(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_VIDEO) ?: run { finish(); return }
        if (id == loadedVideo) return
        loadedVideo = id
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        videoId = id
        positionMs = startMs
        playing = false
        updatedAt = SystemClock.elapsedRealtime()
        val html = assets.open("front_player.html").bufferedReader().use { it.readText() }
            .replace("__VIDEO__", id)
            .replace("__START__", (startMs / 1000L).toString())
            .replace("__DELAY__", RearLink.delayMs.toString())
            .replace("__ORIGIN__", ORIGIN)
        web.loadDataWithBaseURL("$ORIGIN/", html, "text/html", "utf-8", null)
        showControls()
    }

    // ---------------------------------------------------------------- controls

    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xD91C1C1E.toInt()); cornerRadius = dp(100).toFloat() }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        bar.addView(button("✕") { finish() })
        bar.addView(button("−10") { js("seekBy(-10)") })
        playBtn = button("❚❚") { js("toggle()") }
        bar.addView(playBtn)
        bar.addView(button("+10") { js("seekBy(10)") })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(14), 1))
        bar.addView(TextView(this).apply {
            text = getString(R.string.front_delay)
            setTextColor(0xFF8E8E93.toInt())
            textSize = 13f
        })
        bar.addView(button("−") { changeDelay(-10) }.also { it.setOnLongClickListener { changeDelay(-100); true } })
        delayText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(70)
        }
        bar.addView(delayText)
        bar.addView(button("+") { changeDelay(10) }.also { it.setOnLongClickListener { changeDelay(100); true } })
        renderDelay()
        return bar
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { setColor(0xFF2C2C2E.toInt()); cornerRadius = dp(100).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(58), dp(52)).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener {
            onClick()
            showControls()
        }
    }

    private fun changeDelay(stepMs: Int) {
        RearLink.delayMs = RearLink.delayMs + stepMs
        js("setDelay(${RearLink.delayMs})")
        renderDelay()
        showControls()
    }

    private fun renderDelay() {
        delayText.text = String.format(Locale.getDefault(), "%.2f s", RearLink.delayMs / 1000f)
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        controls.animate().alpha(1f).setDuration(150).start()
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 4000)
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private inner class Bridge {
        @JavascriptInterface
        fun onTick(pos: Long, isPlaying: Boolean) {
            positionMs = pos
            playing = isPlaying
            updatedAt = SystemClock.elapsedRealtime()
            handler.post { playBtn.text = if (isPlaying) "❚❚" else "▶" }
        }

        @JavascriptInterface
        fun onState(state: Int) {
            if (state == 0) handler.post { finish() }   // ended
        }

        @JavascriptInterface
        fun onError(code: Int) {
            handler.post {
                Toast.makeText(this@FrontPlayerActivity, R.string.front_cant_embed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
