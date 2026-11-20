package app.drivedeck

import android.content.ComponentName
import android.content.Context
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
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks to KidsDeck on the back screen(s) over the TBox hotspot.
 *
 *  - YouTube: after you share a video from the YouTube app to "Play in the back", the back
 *    screen plays the same video and follows play, pause and seeking about once a second.
 *  - Netflix, Prime and others: "Open … in the back" opens the app there; after that,
 *    playing or pausing at the front plays or pauses the back too.
 */
object RearLink {

    private const val TICK_MS = 1000L
    private const val BACK_TIMEOUT_MS = 10_000L
    private const val TITLE_GRACE_MS = 4000L
    private const val NO_SESSION_GIVE_UP_MS = 60_000L

    data class Back(val name: String, val seenAt: Long)

    private lateinit var app: Context
    private var started = false
    @Volatile private var socket: DatagramSocket? = null
    private val backs = ConcurrentHashMap<String, Back>()
    private val thread = HandlerThread("rearlink").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var seq = SystemClock.elapsedRealtime()

    // YouTube along
    @Volatile var ytVideo: String? = null
        private set
    private var ytStartedAt = 0L
    private var ytTitle: String? = null
    private var ytLastSession = 0L

    // Netflix / Prime along
    @Volatile var followPkg: String? = null
        private set
    private var lastFollowPlaying: Boolean? = null

    fun init(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            app = context.applicationContext
        }
        Thread({ receiveLoop() }, "rearlink-rx").start()
        worker.post(tick)
    }

    /** Back screens heard from in the last few seconds, by name. */
    fun connectedBacks(): List<String> {
        val now = SystemClock.elapsedRealtime()
        return backs.values.filter { now - it.seenAt < BACK_TIMEOUT_MS }.map { it.name }.sorted()
    }

    // ---------------------------------------------------------------- commands

    fun startYouTube(context: Context, videoId: String, startMs: Long) {
        init(context)
        worker.post {
            followPkg = null
            ytVideo = videoId
            ytTitle = null
            ytStartedAt = SystemClock.elapsedRealtime()
            ytLastSession = ytStartedAt
            send(Link.msg("yt", Link.ROLE_FRONT).put("v", videoId).put("p", startMs).put("s", 1))
        }
    }

    fun openInBack(context: Context, pkg: String) {
        init(context)
        worker.post {
            ytVideo = null
            followPkg = pkg
            lastFollowPlaying = null
            sendOnce(Link.msg("open", Link.ROLE_FRONT).put("pkg", pkg))
        }
    }

    fun playPauseBack(context: Context, play: Boolean) {
        init(context)
        worker.post { sendOnce(Link.msg("ps", Link.ROLE_FRONT).put("a", if (play) "play" else "pause")) }
    }

    fun stop(context: Context) {
        init(context)
        worker.post {
            ytVideo = null
            followPkg = null
            sendOnce(Link.msg("stop", Link.ROLE_FRONT))
        }
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
        val video = ytVideo
        if (video != null) {
            stepYouTube(video)
        } else {
            followPkg?.let { stepFollow(it) }
        }
        // Say hello now and then, so back screens show "connected".
        if ((SystemClock.elapsedRealtime() / TICK_MS) % 3L == 0L) send(Link.msg("here", Link.ROLE_FRONT))
    }

    private fun stepYouTube(video: String) {
        val now = SystemClock.elapsedRealtime()
        val c = controllerFor(Link.YOUTUBE)
        if (c == null) {
            if (now - ytLastSession > NO_SESSION_GIVE_UP_MS) {
                ytVideo = null
                sendOnce(Link.msg("stop", Link.ROLE_FRONT))
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
                sendOnce(Link.msg("stop", Link.ROLE_FRONT))
                main.post {
                    Toast.makeText(app, R.string.rear_new_video, Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        val state = c.playbackState ?: return
        val playing = state.state == PlaybackState.STATE_PLAYING
        send(
            Link.msg("yt", Link.ROLE_FRONT)
                .put("v", video)
                .put("p", positionOf(state))
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
            sendOnce(Link.msg("ps", Link.ROLE_FRONT).put("a", if (playing) "play" else "pause"))
        }
    }

    private fun positionOf(state: PlaybackState): Long {
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0L)
    }

    private fun controllerFor(pkg: String): MediaController? = try {
        val msm = app.getSystemService(MediaSessionManager::class.java)
        msm?.getActiveSessions(ComponentName(app, MediaListener::class.java))
            ?.firstOrNull { it.packageName == pkg }
    } catch (e: SecurityException) {
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
        val buf = ByteArray(2048)
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
                backs[packet.address.hostAddress ?: name] = Back(name, SystemClock.elapsedRealtime())
            } catch (e: Exception) {
                try { s.close() } catch (e2: Exception) {}
                socket = null
                SystemClock.sleep(1000)
            }
        }
    }
}
