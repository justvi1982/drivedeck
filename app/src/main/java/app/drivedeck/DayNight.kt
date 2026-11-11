package app.drivedeck

import android.content.SharedPreferences
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Decides whether DriveDeck should be light or dark.
 * "auto" follows sunrise and sunset at the car's last known position
 * (Amsterdam until the first GPS fix).
 */
object DayNight {
    const val MODE_AUTO = "auto"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    private const val DEFAULT_LAT = 52.37
    private const val DEFAULT_LON = 4.90

    fun mode(prefs: SharedPreferences): String =
        prefs.getString(Prefs.KEY_THEME, MODE_AUTO) ?: MODE_AUTO

    fun isNight(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean =
        when (mode(prefs)) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> {
                val lat = prefs.getFloat(Prefs.KEY_LAT, DEFAULT_LAT.toFloat()).toDouble()
                val lon = prefs.getFloat(Prefs.KEY_LON, DEFAULT_LON.toFloat()).toDouble()
                isSunDown(lat, lon, now)
            }
        }

    /**
     * Sunrise equation (accurate to a few minutes, which is plenty here).
     * Uses the solar day nearest to [now] at this longitude.
     */
    fun isSunDown(lat: Double, lon: Double, now: Long): Boolean {
        val rad = PI / 180.0
        val jDate = now / 86_400_000.0 + 2440587.5
        val n = floor(jDate - 2451545.0 + 0.0008 + lon / 360.0 + 0.5)
        val jStar = n - lon / 360.0
        val m = ((357.5291 + 0.98560028 * jStar) % 360.0 + 360.0) % 360.0
        val c = 1.9148 * sin(m * rad) + 0.02 * sin(2 * m * rad) + 0.0003 * sin(3 * m * rad)
        val lambda = ((m + c + 180.0 + 102.9372) % 360.0 + 360.0) % 360.0
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(m * rad) - 0.0069 * sin(2 * lambda * rad)
        val decl = asin(sin(lambda * rad) * sin(23.4397 * rad))
        val cosW = (sin(-0.833 * rad) - sin(lat * rad) * sin(decl)) / (cos(lat * rad) * cos(decl))
        if (cosW > 1.0) return true   // polar night
        if (cosW < -1.0) return false // midnight sun
        val w = acos(cosW) / rad
        val rise = jTransit - w / 360.0
        val set = jTransit + w / 360.0
        return jDate < rise || jDate > set
    }
}
