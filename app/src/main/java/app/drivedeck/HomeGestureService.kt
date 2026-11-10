package app.drivedeck

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws two small overlays on top of every app:
 *  - a floating home button you can drag to either edge, which fades out when idle
 *  - a thin bar at the bottom centre: swipe up on it to go home, like an iPhone
 *
 * Runs as an accessibility service because that is the only way to draw over
 * full-screen apps, know which app is in front (to hide in chosen apps), and
 * press Home on the user's behalf. It reads nothing on screen.
 */
class HomeGestureService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var button: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var buttonShown = false

    private var bar: FrameLayout? = null
    private var pill: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var barShown = false

    private var foreground: String? = null
    private val launchableCache = HashMap<String, Boolean>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val fadeButton = Runnable { button?.animate()?.alpha(0.28f)?.setDuration(400)?.start() }
    private val fadePill = Runnable { pill?.animate()?.alpha(0.22f)?.setDuration(400)?.start() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        createButton()
        createBar()
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Ignore pop-ups, keyboards and system UI: only real apps (and our home screen) count.
        if (pkg != packageName && !isLaunchable(pkg)) return
        if (pkg == foreground) return
        foreground = pkg
        refresh()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            barParams?.let { p ->
                p.width = barWidth()
                if (barShown) bar?.let { wm.updateViewLayout(it, p) }
            }
            buttonParams?.let { p ->
                p.x = snapX(p.x)
                p.y = p.y.coerceIn(0, maxOf(0, screenHeight() - dp(56)))
                if (buttonShown) button?.let { wm.updateViewLayout(it, p) }
            }
        }
    }

    override fun onDestroy() {
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (e: Exception) {}
        setButtonShown(false)
        setBarShown(false)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- show / hide

    private fun refresh() {
        val fg = foreground
        val atHome = fg == null || fg == packageName
        val hiddenHere = fg != null && fg in Prefs.hideIn(prefs)
        val wantButton = prefs.getBoolean(Prefs.KEY_HOME_BUTTON, true) && !atHome && !hiddenHere
        val wantBar = prefs.getBoolean(Prefs.KEY_SWIPE_HOME, true) && !atHome && !hiddenHere
        setButtonShown(wantButton)
        setBarShown(wantBar)
    }

    private fun setButtonShown(show: Boolean) {
        val v = button ?: return
        val p = buttonParams ?: return
        if (show == buttonShown) return
        try {
            if (show) {
                wm.addView(v, p)
                v.alpha = 1f
                handler.removeCallbacks(fadeButton)
                handler.postDelayed(fadeButton, 3000)
            } else {
                wm.removeView(v)
            }
            buttonShown = show
        } catch (e: Exception) {
            buttonShown = false
        }
    }

    private fun setBarShown(show: Boolean) {
        val v = bar ?: return
        val p = barParams ?: return
        if (show == barShown) return
        try {
            if (show) {
                p.width = barWidth()
                wm.addView(v, p)
                pill?.alpha = 0.7f
                handler.removeCallbacks(fadePill)
                handler.postDelayed(fadePill, 3000)
            } else {
                wm.removeView(v)
            }
            barShown = show
        } catch (e: Exception) {
            barShown = false
        }
    }

    // ---------------------------------------------------------------- floating button

    private fun createButton() {
        val size = dp(52)
        val v = View(this)
        v.setBackgroundResource(R.drawable.bg_home_button)
        v.contentDescription = getString(R.string.cd_home)

        val p = overlayParams(size, size)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = prefs.getInt(Prefs.KEY_BTN_X, -1).let { if (it < 0) screenWidth() - size - dp(8) else it }
        p.y = prefs.getInt(Prefs.KEY_BTN_Y, -1).let { if (it < 0) screenHeight() / 3 else it }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                    handler.removeCallbacks(fadeButton)
                    view.animate().cancel()
                    view.alpha = 1f
                    view.scaleX = 0.92f; view.scaleY = 0.92f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = (startX + dx).roundToInt()
                        p.y = (startY + dy).roundToInt().coerceIn(0, maxOf(0, screenHeight() - size))
                        safeUpdate(view, p)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.scaleX = 1f; view.scaleY = 1f
                    if (dragging) {
                        p.x = snapX(p.x)
                        safeUpdate(view, p)
                        prefs.edit().putInt(Prefs.KEY_BTN_X, p.x).putInt(Prefs.KEY_BTN_Y, p.y).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                        goHome()
                    }
                    handler.postDelayed(fadeButton, 3000)
                }
            }
            true
        }
        button = v
        buttonParams = p
    }

    /** Snap the button to whichever side edge is closer. */
    private fun snapX(x: Int): Int {
        val size = dp(52)
        val margin = dp(8)
        val maxX = maxOf(0, screenWidth() - size - margin)
        return if (x + size / 2 < screenWidth() / 2) margin else maxX
    }

    // ---------------------------------------------------------------- swipe-up bar

    private fun createBar() {
        val frame = FrameLayout(this)
        val handle = View(this)
        handle.setBackgroundResource(R.drawable.bg_home_pill)
        frame.addView(handle, FrameLayout.LayoutParams(dp(120), dp(5), Gravity.CENTER))

        val p = overlayParams(barWidth(), dp(22))
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

        val trigger = dp(36)
        var downY = 0f
        frame.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    handler.removeCallbacks(fadePill)
                    handle.animate().cancel()
                    handle.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val up = (downY - e.rawY).coerceAtLeast(0f)
                    handle.translationY = -minOf(up * 0.35f, dp(14).toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val up = downY - e.rawY
                    handle.animate().translationY(0f).setDuration(150).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP && up > trigger) {
                        view.performClick()
                        goHome()
                    }
                    handler.postDelayed(fadePill, 3000)
                }
            }
            true
        }
        bar = frame
        pill = handle
        barParams = p
    }

    /** The swipe zone covers only the middle of the bottom edge, so app buttons in the corners stay usable. */
    private fun barWidth(): Int = (screenWidth() * 0.4f).roundToInt()

    // ---------------------------------------------------------------- helpers

    private fun goHome() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (e: Exception) {}
    }

    private fun isLaunchable(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
