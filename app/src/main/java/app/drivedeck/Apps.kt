package app.drivedeck

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.telecom.TelecomManager

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable,
)

object Apps {

    private val iconCache: MutableMap<String, Drawable> =
        java.util.Collections.synchronizedMap(HashMap())

    @Volatile
    private var phonePkgCache: String? = null
    @Volatile
    private var phonePkgChecked = false

    private val NAV_CANDIDATES = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        "app.organicmaps",
        "net.osmand.plus",
        "net.osmand",
        "com.here.app.maps",
        "com.sygic.aura",
        "com.tomtom.gplay.navapp",
    )

    /** All launchable apps except this launcher, sorted by name. */
    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val sizePx = (context.resources.displayMetrics.density * 64).toInt()
        val phone = phonePackage(context)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val comp = ComponentName(pkg, ri.activityInfo.name)
                val icon = if (pkg == phone) phoneIcon(context, sizePx)
                else roundedIcon(context, comp.flattenToString(), ri.loadIcon(pm), sizePx)
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    component = comp,
                    icon = icon,
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun iconFor(context: Context, pkg: String, sizePx: Int): Drawable? {
        if (pkg == phonePackage(context)) return phoneIcon(context, sizePx)
        return try {
            val raw = context.packageManager.getApplicationIcon(pkg)
            roundedIcon(context, pkg, raw, sizePx)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Launch an app by package name. Returns false if it couldn't be opened. */
    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent); true
        } catch (e: Exception) {
            false
        }
    }

    fun launch(context: Context, app: AppEntry): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent); true
        } catch (e: Exception) {
            launch(context, app.packageName)
        }
    }

    // ---------------------------------------------------------------- navigation app

    /** The navigation app: the user's choice, else the first one installed (Google Maps first). */
    fun navPackage(context: Context): String? {
        val chosen = Prefs.get(context).getString(Prefs.KEY_NAV, null)
        if (chosen != null && isInstalled(context, chosen)) return chosen
        return NAV_CANDIDATES.firstOrNull { isInstalled(context, it) }
    }

    // ---------------------------------------------------------------- phone app

    /** Best guess at the TBox's phone (dialer) app, or null. */
    @Suppress("DEPRECATION")
    fun phonePackage(context: Context): String? {
        if (phonePkgChecked) return phonePkgCache
        val pm = context.packageManager
        var found: String? = null
        try {
            val tm = context.getSystemService(TelecomManager::class.java)
            found = tm?.defaultDialerPackage?.takeIf { isInstalled(context, it) }
        } catch (e: Exception) {
        }
        if (found == null) {
            found = try {
                pm.resolveActivity(Intent(Intent.ACTION_DIAL), 0)?.activityInfo?.packageName
                    ?.takeIf { it != "android" && isInstalled(context, it) }
            } catch (e: Exception) {
                null
            }
        }
        if (found == null) {
            val names = listOf("phone", "telefoon", "telefon", "téléphone", "dialer", "bt phone", "bluetooth phone")
            found = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).firstOrNull { ri ->
                val l = ri.loadLabel(pm).toString().lowercase().trim()
                names.any { l == it }
            }?.activityInfo?.packageName
        }
        phonePkgCache = found
        phonePkgChecked = true
        return found
    }

    /** iPhone-style phone icon: white handset on a green rounded square. */
    private fun phoneIcon(context: Context, sizePx: Int): Drawable {
        val key = "phone-icon@$sizePx"
        iconCache[key]?.let { return it }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF34C759.toInt()
        c.drawRoundRect(RectF(0f, 0f, s, s), s * 0.225f, s * 0.225f, paint)
        context.getDrawable(R.drawable.ic_phone_handset)?.let { h ->
            val inset = (s * 0.2f).toInt()
            h.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            h.draw(c)
        }
        val out = BitmapDrawable(context.resources, bmp)
        iconCache[key] = out
        return out
    }

    // ---------------------------------------------------------------- recent apps

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Most recently used launchable apps, newest first (needs Usage access). */
    @Suppress("DEPRECATION")
    fun recent(context: Context, exclude: Set<String>, max: Int): List<String> {
        if (max <= 0 || !hasUsageAccess(context)) return emptyList()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val last = HashMap<String, Long>()
        try {
            val events = usm.queryEvents(now - 3L * 24 * 60 * 60 * 1000, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last[e.packageName] = e.timeStamp
                }
            }
        } catch (ex: Exception) {
            return emptyList()
        }
        val homes = homePackages(context)
        return last.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it != context.packageName && it !in exclude && it !in homes && isInstalled(context, it) }
            .take(max)
    }

    /** Home-screen apps (including Carlinkit's own), which shouldn't show up as "recent". */
    @Suppress("DEPRECATION")
    private fun homePackages(context: Context): Set<String> = try {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        ).map { it.activityInfo.packageName }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    // ---------------------------------------------------------------- icons

    /**
     * Draws every icon as the same rounded square ("squircle-ish") tile so the
     * grid looks uniform, regardless of the shape the system would use.
     */
    private fun roundedIcon(context: Context, key: String, src: Drawable, sizePx: Int): Drawable {
        val cacheKey = "$key@$sizePx"
        iconCache[cacheKey]?.let { return it }

        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = sizePx.toFloat()
        if (src is AdaptiveIconDrawable) {
            val clip = Path().apply {
                addRoundRect(RectF(0f, 0f, s, s), s * 0.225f, s * 0.225f, Path.Direction.CW)
            }
            canvas.clipPath(clip)
            // Adaptive layers are 108dp with the visible 72dp in the middle.
            val e = sizePx / 4
            src.background?.let { it.setBounds(-e, -e, sizePx + e, sizePx + e); it.draw(canvas) }
            src.foreground?.let { it.setBounds(-e, -e, sizePx + e, sizePx + e); it.draw(canvas) }
        } else {
            src.setBounds(0, 0, sizePx, sizePx)
            src.draw(canvas)
        }
        val out = BitmapDrawable(context.resources, bmp)
        iconCache[cacheKey] = out
        return out
    }
}
