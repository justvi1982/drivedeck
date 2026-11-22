package app.kidsdeck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A factory-screen look: dark anthracite with a soft light from above, a fine honeycomb
 * like a grille that fades in towards the bottom, a dark vignette, and a thin red line.
 * Drawn once into a bitmap, so it costs nothing while the screen is in use.
 */
class OemBackground(private val density: Float) : Drawable() {

    private var cache: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        cache?.recycle()
        cache = null
        if (bounds.width() > 0 && bounds.height() > 0) cache = render(bounds.width(), bounds.height())
    }

    override fun draw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), bitmapPaint) }
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Anthracite, a little lighter at the top.
        p.shader = LinearGradient(0f, 0f, 0f, fh, 0xFF1B1D21.toInt(), 0xFF060607.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)

        // Soft light from above, like a lit dashboard.
        p.shader = RadialGradient(fw / 2f, -fh * 0.15f, max(fw, fh) * 0.75f,
            0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = null

        // Fine honeycomb, fading in towards the bottom.
        val r = 11f * density                    // hexagon radius
        val hexW = sqrt(3f) * r
        val rowH = 1.5f * r
        val hex = Path()
        for (i in 0 until 6) {
            val a = Math.toRadians((60.0 * i) - 30.0)
            val x = (r * cos(a)).toFloat()
            val y = (r * sin(a)).toFloat()
            if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
        }
        hex.close()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 0.8f * density)
            color = 0xFFFFFFFF.toInt()
        }
        var row = 0
        var y = 0f
        while (y < fh + r) {
            val t = (y / fh).coerceIn(0f, 1f)
            // Invisible at the top, strongest at the bottom.
            val fade = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
            line.alpha = (fade * fade * 16).toInt()
            if (line.alpha > 0) {
                var x = if (row % 2 == 0) 0f else hexW / 2f
                while (x < fw + hexW) {
                    c.save()
                    c.translate(x, y)
                    c.drawPath(hex, line)
                    c.restore()
                    x += hexW
                }
            }
            y += rowH
            row++
        }

        // Dark edges.
        p.shader = RadialGradient(fw / 2f, fh / 2f, max(fw, fh) * 0.72f,
            intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)

        // A thin red line along the bottom, brightest in the middle.
        val lineH = max(1f, 1.5f * density)
        p.shader = LinearGradient(0f, 0f, fw, 0f,
            intArrayOf(0x00F50537, 0xCCF50537.toInt(), 0x00F50537), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(fw * 0.2f, fh - lineH, fw * 0.8f, fh, p)
        p.shader = null
        return bmp
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
