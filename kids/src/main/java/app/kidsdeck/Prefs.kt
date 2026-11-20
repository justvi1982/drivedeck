package app.kidsdeck

import android.content.Context
import android.content.SharedPreferences
import app.decklink.Link

object Prefs {
    private const val NAME = "kidsdeck"
    const val KEY_APPS = "apps"
    const val KEY_NAME = "screen_name"
    const val KEY_FOLLOW = "follow_front"

    val NAMES = listOf("ACHTERIN RECHTS", "ACHTERIN LINKS", "ACHTERIN")

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The apps on the home screen, in order. Defaults to YouTube, Netflix and Prime Video. */
    fun apps(context: Context): List<String> {
        val saved = get(context).getString(KEY_APPS, null)
        if (saved != null) return saved.split(",").filter { it.isNotBlank() }
        val prime = if (Apps.isInstalled(context, Link.PRIME)) Link.PRIME else Link.PRIME_TV
        return listOf(Link.YOUTUBE, Link.NETFLIX, prime).filter { Apps.isInstalled(context, it) }
    }

    fun setApps(context: Context, list: List<String>) {
        get(context).edit().putString(KEY_APPS, list.joinToString(",")).apply()
    }

    fun screenName(context: Context): String = get(context).getString(KEY_NAME, NAMES[0]) ?: NAMES[0]

    fun follow(context: Context): Boolean = get(context).getBoolean(KEY_FOLLOW, true)
}
