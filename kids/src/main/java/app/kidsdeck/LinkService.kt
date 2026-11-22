package app.kidsdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.decklink.Link
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Listens for DriveDeck on the TBox hotspot and does what it says: play a YouTube video in
 * step with the front or on its own, open Netflix or Prime (or a title in them), play,
 * pause, stop, and set the volume. Tells the front what it's doing every few seconds.
 */
class LinkService : Service() {

    /** The latest YouTube state from the front, and when it arrived (elapsedRealtime). */
    data class YtState(val videoId: String, val positionMs: Long, val playing: Boolean, val receivedAt: Long) {
        /** Where the front is now, allowing for the time since this arrived. */
        fun positionNow(): Long =
            if (playing) positionMs + (SystemClock.elapsedRealtime() - receivedAt) + NETWORK_DELAY_MS else positionMs
    }

    companion object {
        private const val CHANNEL = "link"
        private const val ANNOUNCE_MS = 3000L
        private const val NETWORK_DELAY_MS = 30L
        const val FRONT_TIMEOUT_MS = 10_000L

        @Volatile var frontSeenAt = 0L
            private set
        @Volatile var yt: YtState? = null
            private set

        /** A video the kids closed with ✕ (or that ended): don't reopen it until the front plays another. */
        @Volatile var dismissedVideo: String? = null
        /** A video that can't play embedded, so it was opened in the YouTube app instead. */
        @Volatile var inAppVideo: String? = null

        private var instance: LinkService? = null
        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }

        fun frontConnected(): Boolean =
            frontSeenAt != 0L && SystemClock.elapsedRealtime() - frontSeenAt < FRONT_TIMEOUT_MS

        /** Tell the front right away that something changed here. */
        fun announceNow() { instance?.let { s -> s.main.post { s.announceOnce() } } }

        fun start(context: Context) {
            val i = Intent(context, LinkService::class.java)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                try { context.startService(i) } catch (e2: Exception) {}
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val seen = LinkedHashSet<Long>()
    /** The app opened from the front (Netflix, Prime…), for play and pause. */
    private var openedPkg: String? = null
    private var lastInAppPlaying: Boolean? = null

    private val announce = object : Runnable {
        override fun run() {
            announceOnce()
            notifyListeners()
            main.postDelayed(this, ANNOUNCE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, buildNotification())
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("kidsdeck").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
        }
        running = true
        Thread({ receiveLoop() }, "kidsdeck-link").start()
        main.post(announce)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        main.removeCallbacks(announce)
        try { socket?.close() } catch (e: Exception) {}
        try { multicastLock?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- status for the front

    private fun announceOnce() {
        val am = getSystemService(AudioManager::class.java)
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
        val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val muted = am?.isStreamMute(AudioManager.STREAM_MUSIC) ?: false
        val player = PlayerActivity.current
        val what = when {
            player != null && player.solo -> "yt-solo"
            player != null -> "yt-sync"
            inAppVideo != null -> "yt-sync"
            else -> openedPkg ?: ""
        }
        val playing = when {
            player != null -> player.isPlaying
            else -> am?.isMusicActive ?: false
        }
        val msg = Link.msg("here", Link.ROLE_BACK)
            .put("name", Prefs.screenName(this))
            .put("vol", (cur * 100f / max).roundToInt())
            .put("muted", muted)
            .put("what", what)
            .put("playing", playing)
            .put("follow", Prefs.follow(this))
        val s = socket
        Thread { Link.broadcast(s, msg) }.start()
    }

    // ---------------------------------------------------------------- network

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running) {
            val s = try {
                socket ?: Link.openSocket().also { socket = it }
            } catch (e: Exception) {
                SystemClock.sleep(2000)
                continue
            }
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val msg = Link.parse(packet) ?: continue
                if (msg.optString("role") != Link.ROLE_FRONT) continue
                main.post { handle(msg) }
            } catch (e: Exception) {
                if (!running) break
                try { s.close() } catch (e2: Exception) {}
                socket = null
                SystemClock.sleep(1000)
            }
        }
    }

    /** One-off commands are sent a few times; act on each only once. */
    private fun firstTime(msg: JSONObject): Boolean {
        val n = msg.optLong("n", 0L)
        if (n == 0L) return true
        if (!seen.add(n)) return false
        while (seen.size > 50) seen.remove(seen.first())
        return true
    }

    private fun handle(msg: JSONObject) {
        val wasConnected = frontConnected()
        frontSeenAt = SystemClock.elapsedRealtime()
        if (!wasConnected) notifyListeners()
        val type = msg.optString("t")
        if (type == "here") return
        if (!Prefs.follow(this)) return
        if (!Link.isFor(msg, Prefs.screenName(this))) return

        if (type == "yt") {
            onYouTubeAlong(msg)
            return
        }
        if (!firstTime(msg)) return
        when (type) {
            "play" -> playOnOwn(msg.optString("v"), msg.optLong("p"))
            "url" -> openUrl(msg.optString("url"), msg.optString("pkg"))
            "open" -> {
                val pkg = installedVariant(msg.optString("pkg"))
                stopVideo()
                openedPkg = pkg
                if (pkg.isNotBlank() && !Apps.launch(this, pkg)) openedPkg = null
            }
            "ps" -> playPause(msg.optString("a") == "play")
            "vol" -> setVolume(msg)
            "stop" -> {
                stopVideo()
                MediaAccess.control(this, false, openedPkg)
            }
        }
        main.postDelayed({ announceOnce() }, 600)
    }

    private fun installedVariant(pkg: String): String =
        if (pkg == Link.PRIME && !Apps.isInstalled(this, pkg) && Apps.isInstalled(this, Link.PRIME_TV)) Link.PRIME_TV else pkg

    // ---------------------------------------------------------------- YouTube

    private fun onYouTubeAlong(msg: JSONObject) {
        val id = msg.optString("v")
        if (id.length != 11) return
        val state = YtState(id, msg.optLong("p"), msg.optInt("s") == 1, SystemClock.elapsedRealtime())
        val previous = yt
        yt = state
        if (previous?.videoId != id) {
            // A new video at the front: forget an old ✕ and start following again.
            if (dismissedVideo != id) dismissedVideo = null
            inAppVideo = null
            lastInAppPlaying = null
        }
        if (dismissedVideo == id) return

        if (inAppVideo == id) {
            // Playing in the YouTube app: only play and pause can follow.
            if (lastInAppPlaying != state.playing) {
                lastInAppPlaying = state.playing
                MediaAccess.control(this, state.playing, Link.YOUTUBE)
            }
            return
        }

        val player = PlayerActivity.current
        if (player != null && player.videoId == id && !player.solo) {
            player.sync(state)
        } else {
            openedPkg = Link.YOUTUBE
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO, id)
                    .putExtra(PlayerActivity.EXTRA_SOLO, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    /** Play a video here on its own; the front is free to do something else. */
    private fun playOnOwn(id: String, startMs: Long) {
        if (id.length != 11) return
        yt = null
        dismissedVideo = null
        inAppVideo = null
        openedPkg = Link.YOUTUBE
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_VIDEO, id)
                .putExtra(PlayerActivity.EXTRA_SOLO, true)
                .putExtra(PlayerActivity.EXTRA_START_MS, startMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun stopVideo() {
        yt = null
        PlayerActivity.current?.let {
            dismissedVideo = it.videoId
            it.finish()
        }
        if (inAppVideo != null) MediaAccess.control(this, false, Link.YOUTUBE)
        inAppVideo = null
    }

    // ---------------------------------------------------------------- other apps

    /** Open a link (a Netflix or Prime title) in its app, so it starts there. */
    private fun openUrl(url: String, pkgHint: String) {
        if (!url.startsWith("http")) return
        stopVideo()
        val pkg = installedVariant(pkgHint.ifBlank { Link.appForUrl(url) ?: "" })
        val target = if (pkg == Link.NETFLIX) Link.netflixWatchUrl(url) else url
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotBlank() && Apps.isInstalled(this, pkg)) intent.setPackage(pkg)
        openedPkg = pkg.ifBlank { null }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // The app didn't take the link: open it the plain way instead.
            if (pkg.isNotBlank()) Apps.launch(this, pkg)
        }
        // Some apps open the title's page: press play a moment later.
        if (pkg == Link.NETFLIX || pkg == Link.PRIME || pkg == Link.PRIME_TV) {
            main.postDelayed({ MediaAccess.control(this, true, pkg) }, 6000)
        }
    }

    private fun playPause(play: Boolean) {
        val player = PlayerActivity.current
        if (player != null) player.setPlaying(play) else MediaAccess.control(this, play, openedPkg)
    }

    private fun setVolume(msg: JSONObject) {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (msg.has("mute")) {
                val mute = msg.optBoolean("mute")
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0,
                )
            }
            if (msg.has("v")) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (msg.optInt("v").coerceIn(0, 100) * max / 100f).roundToInt()
                if (am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            }
        } catch (e: Exception) {
        }
    }

    private fun notifyListeners() { listeners.forEach { it() } }
}
