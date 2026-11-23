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
import android.content.Intent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
        private const val DOUBLE_TAP_MS = 280L

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

    /** All open apps: Android's own app switcher, or DriveDeck's if that's chosen or missing. */
    fun openRecents() {
        Diag.log("opening app switcher")
        val own = prefs.getBoolean(Prefs.KEY_OWN_SWITCHER, false)
        if (own || !performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) {
            try {
                startActivity(
                    Intent(this, HomeActivity::class.java)
                        .putExtra(HomeActivity.EXTRA_SWITCHER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Split screen: the app in front moves to one half and Android lets you pick the other.
     * With DriveDeck's own picker (setting, or when Android has none), the picked app opens
     * next to the current one.
     */
    fun startSplitScreen(displayId: Int) {
        Diag.log("split screen")
        val own = prefs.getBoolean(Prefs.KEY_OWN_SPLIT, false)
        if (own || !performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) {
            overlays[displayId]?.showSplitPicker() ?: overlays[Display.DEFAULT_DISPLAY]?.showSplitPicker()
        }
    }

    /** Opens [pkg] next to the app in front (split screen). */
    fun openAdjacent(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        )
        try { startActivity(intent) } catch (e: Exception) { Diag.log("split open failed: $e") }
    }

    // Swiping sideways along the bar steps through recent apps, like an iPhone.
    private var stepList: List<String> = emptyList()
    private var stepIndex = 0
    private var stepAt = 0L

    /** [older] = true: the app before this one (swipe right); false: back again (swipe left). */
    fun stepApp(displayId: Int, older: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        // A new run of swipes: take a fresh list, newest first, with the current app at 0.
        if (now - stepAt > 4000 || stepList.isEmpty()) {
            val current = foreground[displayId]
            val recent = Apps.recent(this, emptySet(), 10)
            stepList = (listOfNotNull(current?.takeIf { it != packageName }) + recent).distinct()
            stepIndex = 0
        }
        stepAt = now
        if (stepList.isEmpty()) return
        val next = (stepIndex + if (older) 1 else -1).coerceIn(0, stepList.size - 1)
        if (next == stepIndex) return
        stepIndex = next
        Diag.log("switching to ${stepList[next]}")
        Apps.launch(this, stepList[next])
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
            var longPressed = false
            var lastTapAt = 0L
            val singleTapHome = Runnable { goHome() }
            val buttonLongPress = Runnable {
                if (!dragging) {
                    longPressed = true
                    button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    openRecents()
                }
            }
            button.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressed = false
                        handler.postDelayed(buttonLongPress, 550)
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
                        if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                            dragging = true
                            handler.removeCallbacks(buttonLongPress)
                        }
                        if (dragging) {
                            buttonParams.x = (startX + dx).roundToInt()
                            buttonParams.y = (startY + dy).roundToInt().coerceIn(0, maxOf(0, height() - size))
                            update(v, buttonParams)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(buttonLongPress)
                        v.scaleX = 1f; v.scaleY = 1f
                        if (longPressed) {
                            // Already opened the app switcher.
                        } else if (dragging) {
                            buttonParams.x = snapX(buttonParams.x)
                            update(v, buttonParams)
                            prefs.edit()
                                .putInt(Prefs.KEY_BTN_X + displayId, buttonParams.x)
                                .putInt(Prefs.KEY_BTN_Y + displayId, buttonParams.y)
                                .apply()
                        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                            v.performClick()
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTapAt < DOUBLE_TAP_MS) {
                                // Second tap: split screen instead of home.
                                handler.removeCallbacks(singleTapHome)
                                lastTapAt = 0L
                                startSplitScreen(displayId)
                            } else {
                                lastTapAt = now
                                handler.postDelayed(singleTapHome, DOUBLE_TAP_MS)
                            }
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
            val barSlop = ViewConfiguration.get(ctx).scaledTouchSlop
            var barDownY = 0f
            var barDownX = 0f
            var barDownAt = 0L
            var lastBarTapAt = 0L
            val sideTrigger = dp(50)
            var lastY = 0f
            var currentUp = 0f
            var switcherArmed = false
            // Like an iPhone: swipe up and pause = all open apps; a quick swipe = home.
            val armSwitcher = Runnable {
                if (currentUp > trigger) {
                    switcherArmed = true
                    bar.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    pill.animate().scaleX(1.6f).setDuration(120).start()
                }
            }
            bar.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        barDownY = e.rawY
                        barDownX = e.rawX
                        barDownAt = android.os.SystemClock.uptimeMillis()
                        lastY = e.rawY
                        currentUp = 0f
                        switcherArmed = false
                        handler.removeCallbacks(fadePill)
                        pill.animate().cancel()
                        pill.alpha = 1f
                        pill.scaleX = 1f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val up = (barDownY - e.rawY).coerceAtLeast(0f)
                        currentUp = up
                        pill.translationY = -minOf(up * 0.35f, dp(14).toFloat())
                        val side = e.rawX - barDownX
                        pill.translationX = (side * 0.3f).coerceIn(-dp(40).toFloat(), dp(40).toFloat())
                        if (abs(e.rawY - lastY) > barSlop) {
                            // Still moving: wait for a pause.
                            lastY = e.rawY
                            handler.removeCallbacks(armSwitcher)
                            if (!switcherArmed) handler.postDelayed(armSwitcher, 300)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(armSwitcher)
                        val up = barDownY - e.rawY
                        val side = e.rawX - barDownX
                        pill.animate().translationY(0f).translationX(0f).scaleX(1f).setDuration(150).start()
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            val longSwipe = up > height() * 0.4f
                            val sideways = abs(side) > sideTrigger && abs(side) > abs(up) * 1.5f
                            val isTap = abs(side) < barSlop && abs(up) < barSlop &&
                                android.os.SystemClock.uptimeMillis() - barDownAt < 300
                            when {
                                sideways -> {
                                    v.performClick()
                                    // Swipe right: the app before; swipe left: back again.
                                    stepApp(displayId, older = side > 0)
                                }
                                isTap -> {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastBarTapAt < DOUBLE_TAP_MS) {
                                        lastBarTapAt = 0L
                                        v.performClick()
                                        startSplitScreen(displayId)
                                    } else {
                                        lastBarTapAt = now
                                    }
                                }
                                switcherArmed && up > trigger || longSwipe -> {
                                    v.performClick()
                                    openRecents()
                                }
                                up > trigger -> {
                                    v.performClick()
                                    goHome()
                                }
                            }
                        }
                        switcherArmed = false
                        handler.postDelayed(fadePill, 3000)
                    }
                }
                true
            }
        }

        private var picker: View? = null
        private val autoHidePicker = Runnable { hideSplitPicker() }

        /** DriveDeck's own split-screen picker: tap an app to open it next to the current one. */
        fun showSplitPicker() {
            hideSplitPicker()
            val recent = Apps.recent(this@HomeGestureService, setOfNotNull(foreground[displayId]), 8)
            val all = Apps.load(this@HomeGestureService).map { it.packageName }
            val list = (recent + all).distinct().take(24)
            val dim = FrameLayout(ctx).apply {
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener { hideSplitPicker() }
            }
            val card = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xF21C1C1E.toInt()); cornerRadius = dp(26).toFloat()
                }
                setPadding(dp(20), dp(16), dp(20), dp(16))
                isClickable = true
            }
            card.addView(android.widget.TextView(ctx).apply {
                setText(R.string.split_pick)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                setPadding(dp(4), 0, 0, dp(12))
            })
            val grid = android.widget.GridLayout(ctx).apply { columnCount = 6 }
            list.forEach { pkg ->
                val cell = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener {
                        hideSplitPicker()
                        openAdjacent(pkg)
                    }
                }
                cell.addView(android.widget.ImageView(ctx).apply {
                    setImageDrawable(Apps.iconFor(this@HomeGestureService, pkg, dp(56)))
                }, android.widget.LinearLayout.LayoutParams(dp(56), dp(56)))
                cell.addView(android.widget.TextView(ctx).apply {
                    text = Apps.label(this@HomeGestureService, pkg)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, android.widget.LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                grid.addView(cell)
            }
            val scroll = android.widget.ScrollView(ctx).apply { addView(grid) }
            card.addView(scroll, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, minOf(dp(320), (height() * 0.7f).roundToInt())))
            dim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val p = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (add(dim, p, "split picker")) {
                picker = dim
                handler.removeCallbacks(autoHidePicker)
                handler.postDelayed(autoHidePicker, 20_000)
            }
        }

        fun hideSplitPicker() {
            picker?.let { remove(it) }
            picker = null
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
