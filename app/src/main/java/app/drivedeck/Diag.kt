package app.drivedeck

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny in-memory log of what the home controls are doing, shown under
 * Settings → Home button and swipe up → Status. Nothing is written to disk.
 */
object Diag {
    private const val MAX = 40
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun recent(n: Int = 15): List<String> = lines.toList().takeLast(n)
}
