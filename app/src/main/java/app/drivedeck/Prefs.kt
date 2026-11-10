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

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun hideIn(prefs: SharedPreferences): Set<String> =
        prefs.getString(KEY_HIDE_IN, "")!!.split(",").filter { it.isNotBlank() }.toSet()

    fun setHideIn(prefs: SharedPreferences, packages: Collection<String>) {
        prefs.edit().putString(KEY_HIDE_IN, packages.joinToString(",")).apply()
    }
}
