package app.kidsdeck

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

data class AppEntry(val label: String, val packageName: String, val component: ComponentName)

object Apps {

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent); true
        } catch (e: Exception) {
            false
        }
    }

    /** The app's icon as an iOS-style rounded square. */
    fun icon(context: Context, pkg: String, sizePx: Int): Drawable? {
        val raw = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = sizePx * 0.225f
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, Path.Direction.CW) }
        if (raw is AdaptiveIconDrawable) {
            canvas.clipPath(clip)
            // Adaptive layers are 108dp with the visible part in the middle 72dp.
            val pad = (sizePx * 0.25f).toInt()
            raw.background?.let { it.setBounds(-pad, -pad, sizePx + pad, sizePx + pad); it.draw(canvas) }
            raw.foreground?.let { it.setBounds(-pad, -pad, sizePx + pad, sizePx + pad); it.draw(canvas) }
        } else {
            raw.setBounds(0, 0, sizePx, sizePx)
            raw.draw(canvas)
        }
        return BitmapDrawable(context.resources, bmp)
    }
}
