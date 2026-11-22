package app.drivedeck

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import app.decklink.Link
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks to KidsDeck on the back screen(s) over the TBox hotspot.
 *
 * Two modes:
 *  - Together: the back plays the same YouTube video as the YouTube app at the front and
 *    follows play, pause and seeking, a little later to make up for the car's Bluetooth
 *    delay. Netflix and Prime in the back follow the front's play and pause.
 *  - Back only: the back plays on its own; the front is free (Spotify…). Play, pause,
 *    stop and volume still work from the front.
 *
 * Every command goes to the back screens picked in the panel (right, left or both).
 */
object RearLink {

    const val MODE_TOGETHER = "together"
    const val MODE_SOLO = "solo"
    const val TARGET_ALL = "*"

    private const val KEY_MODE = "back_mode"
    private const val KEY_TARGET = "back_target"
    private const val KEY_DELAY = "back_bt_delay_ms"
    const val DEFAULT_DELAY_MS = 200

    private const val TICK_MS = 1000L
    private const val BACK_TIMEOUT_MS = 10_000L
    private const val TITLE_GRACE_MS = 4000L
    private const val NO_SESSION_GIVE_UP_MS = 60_000L

    /** What a back screen last told us about itself. */
    data class Back(
        val name: String,
        val seenAt: Long,
        val volume: Int,
        val muted: Boolean,
        val what: String,
        val playing: Boolean,
        val follow: Boolean,
    )

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var started = false
    @Volatile private var socket: DatagramSocket? = null
    private val backs = ConcurrentHashMap<String, Back>()
    private val thread = HandlerThread("rearlink").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var seq = SystemClock.elapsedRealtime()

    // Together: YouTube
    @Volatile var ytVideo: String? = null
        private set
    private var ytTo: Any = TARGET_ALL
    private var ytStartedAt = 0L
    private var ytTitle: String? = null
    private var ytLastSession = 0L

    // Together: Netflix / Prime follow play and pause
    @Volatile var followPkg: String? = null
        private set
    private var followTo: Any = TARGET_ALL
    private var lastFollowPlaying: Boolean? = null

    fun init(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            app = context.applicationContext
            prefs = Prefs.get(app)
        }
        Thread({ receiveLoop() }, "rearlink-rx").start()
        worker.post(tick)
    }

    // ---------------------------------------------------------------- settings

    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_TOGETHER) ?: MODE_TOGETHER
        set(v) { prefs.edit().putString(KEY_MODE, v).apply() }

    /** A back screen's name, or [TARGET_ALL]. */
    var target: String
        get() = prefs.getString(KEY_TARGET, TARGET_ALL) ?: TARGET_ALL
        set(v) { prefs.edit().putString(KEY_TARGET, v).apply() }

    /** How much later the back plays, to match the front's Bluetooth sound (ms). */
    var delayMs: Int
        get() = prefs.getInt(KEY_DELAY, DEFAULT_DELAY_MS)
        set(v) { prefs.edit().putInt(KEY_DELAY, v.coerceIn(-1000, 2000)).apply() }

    /** Back screens heard from in the last few seconds, sorted by name. */
    fun connectedBacks(): List<Back> {
        val now = SystemClock.elapsedRealtime()
        return backs.values.filter { now - it.seenAt < BACK_TIMEOUT_MS }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /** The screens the panel's choice points at right now. */
    fun targetedBacks(): List<Back> {
        val all = connectedBacks()
        val t = target
        return if (t == TARGET_ALL) all else all.filter { it.name.equals(t, true) }
    }

    private fun to(): Any = target.let { if (it == TARGET_ALL) TARGET_ALL else JSONArray().put(it) }

    private fun cmd(type: String): JSONObject = Link.msg(type, Link.ROLE_FRONT).put("to", to())

    // ---------------------------------------------------------------- commands

    /** Together: the back plays [videoId] along with the YouTube app at the front. */
    fun playTogether(context: Context, videoId: String, startMs: Long) {
        init(context)
        worker.post {
            followPkg = null
            ytVideo = videoId
            ytTo = to()
            ytTitle = null
            ytStartedAt = SystemClock.elapsedRealtime()
            ytLastSession = ytStartedAt
            send(Link.msg("yt", Link.ROLE_FRONT).put("to", ytTo).put("v", videoId)
                .put("p", (startMs - delayMs).coerceAtLeast(0L)).put("s", 1))
        }
    }

    /** Back only: the back plays [videoId] on its own. */
    fun playSolo(context: Context, videoId: String, startMs: Long) {
        init(context)
        worker.post {
            stopFollowingFor(to())
            sendOnce(cmd("play").put("v", videoId).put("p", startMs))
        }
    }

    /** Open a Netflix / Prime (or other) link in the back. */
    fun openUrl(context: Context, url: String, pkg: String?) {
        init(context)
        worker.post {
            stopFollowingFor(to())
            sendOnce(cmd("url").put("url", url).put("pkg", pkg ?: ""))
            startFollowing(pkg)
        }
    }

    fun openApp(context: Context, pkg: String) {
        init(context)
        worker.post {
            stopFollowingFor(to())
            sendOnce(cmd("open").put("pkg", pkg))
            startFollowing(pkg)
        }
    }

    fun playPause(context: Context, play: Boolean) {
        init(context)
        worker.post { sendOnce(cmd("ps").put("a", if (play) "play" else "pause")) }
    }

    fun setVolume(context: Context, percent: Int) {
        init(context)
        worker.post { sendOnce(cmd("vol").put("v", percent.coerceIn(0, 100))) }
    }

    fun setMuted(context: Context, muted: Boolean) {
        init(context)
        worker.post { sendOnce(cmd("vol").put("mute", muted)) }
    }

    fun stop(context: Context) {
        init(context)
        worker.post {
            stopFollowingFor(to())
            sendOnce(cmd("stop"))
        }
    }

    /** Together mode: the back's Netflix / Prime follows the front's play and pause. */
    private fun startFollowing(pkg: String?) {
        if (mode != MODE_TOGETHER || pkg == null || pkg == Link.YOUTUBE) return
        followPkg = pkg
        followTo = to()
        lastFollowPlaying = null
    }

    /** A new command for these screens replaces whatever they were following. */
    private fun stopFollowingFor(t: Any) {
        if (ytVideo != null && (t == TARGET_ALL || ytTo.toString() == t.toString() || ytTo == TARGET_ALL)) ytVideo = null
        if (followPkg != null && (t == TARGET_ALL || followTo.toString() == t.toString() || followTo == TARGET_ALL)) followPkg = null
    }

    // ---------------------------------------------------------------- the 1-second loop

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (e: Exception) {
            }
            worker.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        ytVideo?.let { stepYouTube(it) }
        followPkg?.let { stepFollow(it) }
        // Say hello now and then, so back screens show "connected".
        if ((SystemClock.elapsedRealtime() / TICK_MS) % 3L == 0L) send(Link.msg("here", Link.ROLE_FRONT))
    }

    private fun stepYouTube(video: String) {
        val now = SystemClock.elapsedRealtime()
        val c = controllerFor(Link.YOUTUBE)
        if (c == null) {
            if (now - ytLastSession > NO_SESSION_GIVE_UP_MS) {
                ytVideo = null
                sendOnce(Link.msg("stop", Link.ROLE_FRONT).put("to", ytTo))
            }
            return
        }
        ytLastSession = now

        // When YouTube moves on to another video, stop: we don't know the new video's id.
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (now - ytStartedAt > TITLE_GRACE_MS) {
            if (ytTitle == null) {
                ytTitle = title
            } else if (title != null && title != ytTitle) {
                ytVideo = null
                sendOnce(Link.msg("stop", Link.ROLE_FRONT).put("to", ytTo))
                main.post { Toast.makeText(app, R.string.rear_new_video, Toast.LENGTH_LONG).show() }
                return
            }
        }

        val state = c.playbackState ?: return
        val playing = state.state == PlaybackState.STATE_PLAYING
        // The front's sound comes through the car a little late, so the back plays a little behind.
        val pos = positionOf(state) - (if (playing) delayMs else 0)
        send(
            Link.msg("yt", Link.ROLE_FRONT)
                .put("to", ytTo)
                .put("v", video)
                .put("p", pos.coerceAtLeast(0L))
                .put("s", if (playing) 1 else 0)
        )
    }

    private fun stepFollow(pkg: String) {
        val c = controllerFor(pkg) ?: return
        val st = c.playbackState?.state ?: return
        val playing = when (st) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> false
            else -> return
        }
        val before = lastFollowPlaying
        lastFollowPlaying = playing
        if (before != null && before != playing) {
            sendOnce(Link.msg("ps", Link.ROLE_FRONT).put("to", followTo).put("a", if (playing) "play" else "pause"))
        }
    }

    private fun positionOf(state: PlaybackState): Long {
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0L)
    }

    /** The front's player for [pkg], or null. */
    fun controllerFor(pkg: String): MediaController? = try {
        val msm = app.getSystemService(MediaSessionManager::class.java)
        msm?.getActiveSessions(ComponentName(app, MediaListener::class.java))
            ?.firstOrNull { it.packageName == pkg }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------- network

    private fun send(msg: JSONObject) {
        Link.broadcast(socket, msg)
    }

    /** One-off commands go out three times with the same number; the back acts once. */
    private fun sendOnce(msg: JSONObject) {
        msg.put("n", ++seq)
        send(msg)
        worker.postDelayed({ send(msg) }, 150)
        worker.postDelayed({ send(msg) }, 400)
    }

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (true) {
            val s = try {
                socket ?: Link.openSocket().also { socket = it }
            } catch (e: Exception) {
                SystemClock.sleep(3000)
                continue
            }
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val msg = Link.parse(packet) ?: continue
                if (msg.optString("role") != Link.ROLE_BACK || msg.optString("t") != "here") continue
                val name = msg.optString("name").ifBlank { "BACK" }
                backs[packet.address.hostAddress ?: name] = Back(
                    name = name,
                    seenAt = SystemClock.elapsedRealtime(),
                    volume = msg.optInt("vol", -1),
                    muted = msg.optBoolean("muted"),
                    what = msg.optString("what"),
                    playing = msg.optBoolean("playing"),
                    follow = msg.optBoolean("follow", true),
                )
            } catch (e: Exception) {
                try { s.close() } catch (e2: Exception) {}
                socket = null
                SystemClock.sleep(1000)
            }
        }
    }
}
