package app.drivedeck

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable,
)

object Apps {

    private val iconCache: MutableMap<String, Drawable> =
        java.util.Collections.synchronizedMap(HashMap())

    /** All launchable apps except this launcher, sorted by name. */
    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val sizePx = (context.resources.displayMetrics.density * 64).toInt()
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val comp = ComponentName(pkg, ri.activityInfo.name)
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    component = comp,
                    icon = roundedIcon(context, comp.flattenToString(), ri.loadIcon(pm), sizePx),
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

    fun iconFor(context: Context, pkg: String, sizePx: Int): Drawable? = try {
        val raw = context.packageManager.getApplicationIcon(pkg)
        roundedIcon(context, pkg, raw, sizePx)
    } catch (e: PackageManager.NameNotFoundException) {
        null
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
