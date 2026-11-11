package app.drivedeck

import android.content.Context
import android.content.SharedPreferences

/** Settings shared between the home screen and the home-gesture service. */
object Prefs {
    const val NAME = "drivedeck"

    const val KEY_DOCK = "dock"
    const val KEY_NAV = "nav"
    const val KEY_HOME_BUTTON = "home_button"
    const val KEY_SWIPE_HOME = "swipe_home"
    const val KEY_HIDE_IN = "hide_in"
    const val KEY_BTN_X = "btn_x"
    const val KEY_BTN_Y = "btn_y"
    const val KEY_TILE_APP = "tile_app"
    const val KEY_THEME = "theme"
    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"

    const val DEFAULT_TILE_APP = "nl.flitsmeister"

    /** The app shown in the small tile; Flitsmeister unless the user picked another. */
    fun tileApp(prefs: SharedPreferences): String? =
        prefs.getString(KEY_TILE_APP, DEFAULT_TILE_APP)?.takeIf { it.isNotBlank() }

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun hideIn(prefs: SharedPreferences): Set<String> =
        prefs.getString(KEY_HIDE_IN, "")!!.split(",").filter { it.isNotBlank() }.toSet()

    fun setHideIn(prefs: SharedPreferences, packages: Collection<String>) {
        prefs.edit().putString(KEY_HIDE_IN, packages.joinToString(",")).apply()
    }
}
