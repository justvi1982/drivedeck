package app.drivedeck

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
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
 * Car boxes like the TBox can run apps on a different internal display than the
 * home screen, so the overlays are placed on EVERY display, and each display
 * tracks its own app in front.
 *
 * Runs as an accessibility service because that is the only way to draw over
 * full-screen apps, know which app is in front (to hide in chosen apps), and
 * press Home. It looks at which app is open, never at what's in it.
 */
class HomeGestureService : AccessibilityService() {

    companion object {
        @Volatile
        var homeVisible = false
            private set

        @Volatile
        var homeDisplay = Display.DEFAULT_DISPLAY
            private set

        @Volatile
        var instance: HomeGestureService? = null
            private set

        /** Called by the home screen itself (same process), so we never have to guess when it's showing. */
        fun setHomeVisible(visible: Boolean, displayId: Int) {
            homeVisible = visible
            homeDisplay = displayId
            Diag.log(if (visible) "home screen shown (display $displayId)" else "home screen left")
            instance?.let { s -> s.handler.post { s.refresh() } }
        }
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var displayManager: DisplayManager? = null

    private val overlays = HashMap<Int, DisplayOverlay>()
    private val foreground = HashMap<Int, String>()
    private val failedDisplays = HashMap<Int, String>()
    private val activityCache = HashMap<String, Boolean>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncDisplays()
        override fun onDisplayRemoved(displayId: Int) = syncDisplays()
        override fun onDisplayChanged(displayId: Int) {
            overlays[displayId]?.relayout()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, handler)
        instance = this
        Diag.log("service connected")
        syncDisplays()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Our own windows (including these overlays appearing) say nothing about which app is open.
        if (pkg == packageName) return
        val cls = event.className?.toString() ?: return
        val display = if (Build.VERSION.SDK_INT >= 30) event.displayId else Display.DEFAULT_DISPLAY
        // Only count real app screens; pop-ups, toasts, keyboards and floating docks are ignored.
        if (!isActivity(pkg, cls)) return
        if (foreground[display] == pkg) return
        foreground[display] = pkg
        Diag.log("app in front on display $display: $pkg")
        refresh()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { overlays.values.forEach { it.relayout() } }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Diag.log("service stopped")
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) } catch (e: Exception) {}
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (e: Exception) {}
        overlays.values.forEach { it.destroy() }
        overlays.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- public helpers

    fun goHome() {
        Diag.log("going home")
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /** One line per display, for the status screen. */
    fun statusLines(): List<String> {
        val dm = displayManager ?: return listOf("No display service")
        return dm.displays.map { d ->
            val o = overlays[d.displayId]
            val state = when {
                o != null -> "button ${if (o.buttonShown) "ON" else "off"}, swipe ${if (o.barShown) "ON" else "off"}" +
                    (o.lastError?.let { ", error: $it" } ?: "")
                failedDisplays[d.displayId] != null -> "can't draw here: ${failedDisplays[d.displayId]}"
                else -> "not used"
            }
            val app = foreground[d.displayId] ?: "?"
            "Display ${d.displayId} \"${d.name}\" ${d.mode.physicalWidth}×${d.mode.physicalHeight}: $state; app: $app"
        }
    }

    // ---------------------------------------------------------------- displays

    private fun syncDisplays() {
        if (!::prefs.isInitialized) return
        val dm = displayManager ?: return
        val current = dm.displays.associateBy { it.displayId }
        overlays.keys.filter { it !in current }.forEach { id ->
            overlays.remove(id)?.destroy()
            Diag.log("display $id removed")
        }
        for ((id, display) in current) {
            if (id in overlays) continue
            try {
                val ctx: Context = if (id == Display.DEFAULT_DISPLAY) this else createDisplayContext(display)
                overlays[id] = DisplayOverlay(id, ctx)
                failedDisplays.remove(id)
                Diag.log("display $id \"${display.name}\" ready")
            } catch (e: Exception) {
                failedDisplays[id] = e.javaClass.simpleName
                Diag.log("display $id unusable: ${e.javaClass.simpleName}")
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!::prefs.isInitialized) return
        val wantButton = prefs.getBoolean(Prefs.KEY_HOME_BUTTON, true)
        val wantBar = prefs.getBoolean(Prefs.KEY_SWIPE_HOME, true)
        val onHomeToo = prefs.getBoolean(Prefs.KEY_SHOW_ON_HOME, false)
        val hidden = Prefs.hideIn(prefs)
        for ((id, o) in overlays) {
            val homeHere = homeVisible && homeDisplay == id && !onHomeToo
            val appHidden = !(homeVisible && homeDisplay == id) && foreground[id]?.let { it in hidden } == true
            val show = !homeHere && !appHidden
            o.setShown(wantButton && show, wantBar && show)
        }
    }

    private fun isActivity(pkg: String, cls: String): Boolean =
        activityCache.getOrPut("$pkg/$cls") {
            try {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            } catch (e: Exception) {
                false
            }
        }

    // ---------------------------------------------------------------- one display's overlays

    private inner class DisplayOverlay(val displayId: Int, val ctx: Context) {
        private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
        private val density = ctx.resources.displayMetrics.density
        private val button: View
        private val buttonParams: WindowManager.LayoutParams
        private val bar: FrameLayout
        private val pill: View
        private val barParams: WindowManager.LayoutParams
        var buttonShown = false
            private set
        var barShown = false
            private set
        var lastError: String? = null
            private set

        init {
            // ---- floating button
            val size = dp(52)
            button = View(ctx)
            button.setBackgroundResource(R.drawable.bg_home_button)
            button.contentDescription = ctx.getString(R.string.cd_home)
            buttonParams = overlayParams(size, size)
            buttonParams.gravity = Gravity.TOP or Gravity.START
            val savedX = prefs.getInt(Prefs.KEY_BTN_X + displayId, -1)
            val savedY = prefs.getInt(Prefs.KEY_BTN_Y + displayId, -1)
            buttonParams.x = if (savedX < 0) width() - size - dp(8) else savedX
            buttonParams.y = if (savedY < 0) height() / 3 else savedY

            val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            button.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = buttonParams.x; startY = buttonParams.y
                        dragging = false
                        handler.removeCallbacks(fadeButton)
                        v.animate().cancel()
                        v.alpha = 1f
                        v.scaleX = 0.92f; v.scaleY = 0.92f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                        if (dragging) {
                            buttonParams.x = (startX + dx).roundToInt()
                            buttonParams.y = (startY + dy).roundToInt().coerceIn(0, maxOf(0, height() - size))
                            update(v, buttonParams)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.scaleX = 1f; v.scaleY = 1f
                        if (dragging) {
                            buttonParams.x = snapX(buttonParams.x)
                            update(v, buttonParams)
                            prefs.edit()
                                .putInt(Prefs.KEY_BTN_X + displayId, buttonParams.x)
                                .putInt(Prefs.KEY_BTN_Y + displayId, buttonParams.y)
                                .apply()
                        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                            goHome()
                        }
                        handler.postDelayed(fadeButton, 3000)
                    }
                }
                true
            }

            // ---- swipe-up bar
            bar = FrameLayout(ctx)
            pill = View(ctx)
            pill.setBackgroundResource(R.drawable.bg_home_pill)
            bar.addView(pill, FrameLayout.LayoutParams(dp(120), dp(5), Gravity.CENTER))
            barParams = overlayParams(barWidth(), dp(24))
            barParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val trigger = dp(30)
            var barDownY = 0f
            bar.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        barDownY = e.rawY
                        handler.removeCallbacks(fadePill)
                        pill.animate().cancel()
                        pill.alpha = 1f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val up = (barDownY - e.rawY).coerceAtLeast(0f)
                        pill.translationY = -minOf(up * 0.35f, dp(14).toFloat())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val up = barDownY - e.rawY
                        pill.animate().translationY(0f).setDuration(150).start()
                        if (e.actionMasked == MotionEvent.ACTION_UP && up > trigger) {
                            v.performClick()
                            goHome()
                        }
                        handler.postDelayed(fadePill, 3000)
                    }
                }
                true
            }
        }

        private val fadeButton = Runnable { button.animate().alpha(0.28f).setDuration(400).start() }
        private val fadePill = Runnable { pill.animate().alpha(0.22f).setDuration(400).start() }

        fun setShown(showButton: Boolean, showBar: Boolean) {
            if (showButton != buttonShown) {
                if (showButton) {
                    if (add(button, buttonParams, "button")) {
                        buttonShown = true
                        button.alpha = 1f
                        handler.removeCallbacks(fadeButton)
                        handler.postDelayed(fadeButton, 3000)
                    }
                } else {
                    remove(button); buttonShown = false
                }
            }
            if (showBar != barShown) {
                if (showBar) {
                    barParams.width = barWidth()
                    if (add(bar, barParams, "swipe bar")) {
                        barShown = true
                        pill.alpha = 0.7f
                        handler.removeCallbacks(fadePill)
                        handler.postDelayed(fadePill, 3000)
                    }
                } else {
                    remove(bar); barShown = false
                }
            }
        }

        fun relayout() {
            barParams.width = barWidth()
            if (barShown) update(bar, barParams)
            buttonParams.x = snapX(buttonParams.x)
            buttonParams.y = buttonParams.y.coerceIn(0, maxOf(0, height() - dp(56)))
            if (buttonShown) update(button, buttonParams)
        }

        fun destroy() {
            remove(button); remove(bar)
            buttonShown = false; barShown = false
            handler.removeCallbacks(fadeButton)
            handler.removeCallbacks(fadePill)
        }

        private fun add(v: View, p: WindowManager.LayoutParams, what: String): Boolean = try {
            wm.addView(v, p)
            lastError = null
            Diag.log("$what shown on display $displayId")
            true
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            Diag.log("$what FAILED on display $displayId: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            false
        }

        private fun remove(v: View) {
            if (v.parent == null && !v.isAttachedToWindow) return
            try { wm.removeView(v) } catch (e: Exception) {}
        }

        private fun update(v: View, p: WindowManager.LayoutParams) {
            try { wm.updateViewLayout(v, p) } catch (e: Exception) {}
        }

        private fun snapX(x: Int): Int {
            val size = dp(52)
            val margin = dp(8)
            val maxX = maxOf(0, width() - size - margin)
            return if (x + size / 2 < width() / 2) margin else maxX
        }

        /** The swipe zone covers only the middle of the bottom edge, so app buttons in the corners stay usable. */
        private fun barWidth(): Int = (width() * 0.4f).roundToInt()

        private fun width(): Int =
            if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
            else ctx.resources.displayMetrics.widthPixels

        private fun height(): Int =
            if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height()
            else ctx.resources.displayMetrics.heightPixels

        private fun dp(v: Int): Int = (v * density).roundToInt()
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )
}
