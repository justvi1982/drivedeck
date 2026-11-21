package app.drivedeck

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Switches the TBox's Wi-Fi hotspot on or off.
 *
 * Android doesn't let normal apps do this directly, so DriveDeck opens the hotspot page
 * in Settings and taps its switch, using the home-button accessibility service, then goes
 * back. This is the only time that service looks at what's on screen, and only in Settings.
 */
object Hotspot {

    /** Android's own hotspot broadcast and states (not public API, but long-standing). */
    const val ACTION_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val STATE_DISABLING = 10
    private const val STATE_DISABLED = 11
    private const val STATE_ENABLING = 12
    private const val STATE_ENABLED = 13

    private const val POLL_MS = 350L
    private const val TIMEOUT_MS = 9000L
    private const val SETTLE_MS = 1500L

    private val SETTINGS_PKGS = setOf("com.android.settings", "com.google.android.settings")
    private val HOTSPOT_WORDS = listOf("hotspot", "tether", "toegangspunt", "zugangspunkt", "punto de acceso", "point d'acc")
    private val SKIP_WORDS = listOf("automati", "compatib", "extend", "uitbreid", "maximi", "6 ghz", "instant", "usb", "bluetooth", "ethernet")

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    /** true = on, false = off, null = unknown. */
    fun isOn(context: Context): Boolean? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val m = wm.javaClass.getMethod("isWifiApEnabled")
        m.invoke(wm) as? Boolean
    } catch (e: Throwable) {
        null
    }

    /** Hotspot state from [ACTION_STATE_CHANGED]: true on, false off, null in between. */
    fun stateFrom(intent: Intent): Boolean? = when (intent.getIntExtra("wifi_state", -1)) {
        STATE_ENABLED -> true
        STATE_DISABLED -> false
        STATE_ENABLING, STATE_DISABLING -> null
        else -> null
    }

    /**
     * Turn the hotspot on ([want] = true), off (false) or the other way round (null).
     * [goHomeAfter]: return to DriveDeck when done. [quiet]: no message when it's already right.
     */
    fun set(context: Context, want: Boolean?, goHomeAfter: Boolean = true, quiet: Boolean = false) {
        val app = context.applicationContext
        val now = isOn(app)
        // When toggling and the state can't be read, the switch on screen decides.
        val initial: Boolean? = want ?: now?.not()
        if (initial != null && now == initial) {
            if (!quiet) toast(app, if (initial) R.string.hotspot_already_on else R.string.hotspot_already_off)
            return
        }
        var target: Boolean? = initial
        val svc = HomeGestureService.instance
        if (svc == null) {
            // Without the accessibility service we can only open the page.
            openSettings(app)
            toast(app, R.string.hotspot_need_service)
            return
        }
        if (busy) return
        busy = true
        Diag.log("hotspot: switching ${when (initial) { true -> "on"; false -> "off"; null -> "over" }}")
        if (!openSettings(app)) {
            busy = false
            toast(app, R.string.hotspot_failed)
            return
        }
        val started = SystemClock.elapsedRealtime()
        var clickedAt = 0L
        val step = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started
                val sw = findSwitch(svc)
                if (target == null && sw != null) target = !sw.isChecked
                val t = target
                when {
                    t == null -> {}
                    sw != null && sw.isChecked == t && (clickedAt == 0L || SystemClock.elapsedRealtime() - clickedAt > SETTLE_MS) -> {
                        finish(svc, app, ok = true, target = t, goHomeAfter = goHomeAfter)
                        return
                    }
                    sw != null && sw.isChecked != t && clickedAt == 0L -> {
                        if (click(sw)) clickedAt = SystemClock.elapsedRealtime()
                    }
                    clickedAt != 0L && SystemClock.elapsedRealtime() - clickedAt > SETTLE_MS * 3 -> {
                        // Clicked but the switch didn't follow: try once more.
                        if (sw != null && sw.isChecked != t && click(sw)) clickedAt = SystemClock.elapsedRealtime()
                    }
                }
                if (elapsed > TIMEOUT_MS) {
                    val tt = target ?: true
                    finish(svc, app, ok = t != null && isOn(app) == tt, target = tt, goHomeAfter = goHomeAfter)
                    return
                }
                main.postDelayed(this, POLL_MS)
            }
        }
        main.postDelayed(step, 900)
    }

    private fun finish(svc: HomeGestureService, app: Context, ok: Boolean, target: Boolean, goHomeAfter: Boolean) {
        busy = false
        Diag.log("hotspot: ${if (ok) "done" else "couldn't switch"}")
        if (ok) {
            toast(app, if (target) R.string.hotspot_on else R.string.hotspot_off)
            if (goHomeAfter) svc.goHome()
        } else {
            // Leave Settings open so it can be switched by hand.
            toast(app, R.string.hotspot_failed)
        }
    }

    /** Opens the hotspot page of Settings, or the closest page there is. */
    fun openSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$WifiTetherSettingsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in candidates) {
            try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    // ---------------------------------------------------------------- reading the Settings screen

    private fun roots(svc: AccessibilityService): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { svc.rootInActiveWindow?.let { out.add(it) } } catch (e: Exception) {}
        try { svc.windows?.forEach { w -> w.root?.let { out.add(it) } } } catch (e: Exception) {}
        return out.filter { it.packageName?.toString() in SETTINGS_PKGS }
    }

    /** The hotspot's on/off switch, or null if it isn't on screen (yet). */
    private fun findSwitch(svc: AccessibilityService): AccessibilityNodeInfo? {
        for (root in roots(svc)) {
            val all = ArrayList<AccessibilityNodeInfo>()
            collect(root, all, 0)
            val screenText = all.joinToString(" ") { (it.text ?: "").toString() }.lowercase()
            val switches = all.filter { it.isCheckable }
            if (switches.isEmpty()) continue
            // Prefer a switch whose row talks about the hotspot.
            val labelled = switches.firstOrNull { sw ->
                val label = rowText(sw).lowercase()
                HOTSPOT_WORDS.any { label.contains(it) } && SKIP_WORDS.none { label.contains(it) }
            }
            if (labelled != null) return labelled
            // On the hotspot page itself the main switch is the first one.
            if (HOTSPOT_WORDS.any { screenText.contains(it) }) {
                switches.firstOrNull { sw -> SKIP_WORDS.none { rowText(sw).lowercase().contains(it) } }?.let { return it }
            }
        }
        return null
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40) return
        out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    /** The text in a switch's row: itself and its parents, up to (not including) the list. */
    private fun rowText(sw: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sw.text?.let { sb.append(it).append(' ') }
        sw.contentDescription?.let { sb.append(it).append(' ') }
        var p = sw.parent
        var levels = 0
        while (p != null && levels < 3) {
            val cls = p.className?.toString() ?: ""
            if (cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView")) break
            val texts = ArrayList<AccessibilityNodeInfo>()
            collect(p, texts, 0)
            texts.forEach { n -> n.text?.let { sb.append(it).append(' ') } }
            p = p.parent
            levels++
        }
        return sb.toString()
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var levels = 0
        while (n != null && levels < 4) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            levels++
        }
        return false
    }

    private fun toast(context: Context, res: Int) {
        main.post { Toast.makeText(context, res, Toast.LENGTH_SHORT).show() }
    }
}
