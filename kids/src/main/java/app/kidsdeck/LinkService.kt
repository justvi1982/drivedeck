package app.kidsdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * Listens for DriveDeck on the TBox hotspot and does what it says: play a YouTube video in
 * step with the front, open Netflix or Prime, play, pause or stop.
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
        private const val NETWORK_DELAY_MS = 40L
        const val FRONT_TIMEOUT_MS = 10_000L

        @Volatile var frontSeenAt = 0L
            private set
        @Volatile var yt: YtState? = null
            private set

        /** A video the kids closed with ✕: don't reopen it until the front plays another one. */
        @Volatile var dismissedVideo: String? = null
        /** A video that can't play embedded, so it was opened in the YouTube app instead. */
        @Volatile var inAppVideo: String? = null

        private val listeners = CopyOnWriteArraySet<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }

        fun frontConnected(): Boolean =
            frontSeenAt != 0L && SystemClock.elapsedRealtime() - frontSeenAt < FRONT_TIMEOUT_MS

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
    private var followPkg: String? = null
    private var lastInAppPlaying: Boolean? = null

    private val announce = object : Runnable {
        override fun run() {
            val s = socket
            val name = Prefs.screenName(this@LinkService)
            Thread { Link.broadcast(s, Link.msg("here", Link.ROLE_BACK).put("name", name)) }.start()
            notifyListeners()
            main.postDelayed(this, ANNOUNCE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        if (!Prefs.follow(this)) return

        when (msg.optString("t")) {
            "yt" -> onYouTube(msg)
            "open" -> if (firstTime(msg)) {
                val pkg = msg.optString("pkg")
                followPkg = pkg
                closePlayer()
                val target = if (pkg == Link.PRIME && !Apps.isInstalled(this, pkg)) Link.PRIME_TV else pkg
                if (target.isNotBlank() && !Apps.launch(this, target)) followPkg = null
            }
            "ps" -> if (firstTime(msg)) {
                MediaAccess.control(this, msg.optString("a") == "play", followPkg)
            }
            "stop" -> if (firstTime(msg)) {
                yt = null
                closePlayer()
                if (inAppVideo != null) MediaAccess.control(this, false, Link.YOUTUBE)
                inAppVideo = null
            }
        }
    }

    private fun onYouTube(msg: JSONObject) {
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
        if (player != null && player.videoId == id) {
            player.sync(state)
        } else {
            followPkg = Link.YOUTUBE
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_VIDEO, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private fun closePlayer() {
        PlayerActivity.current?.finish()
    }

    private fun notifyListeners() { listeners.forEach { it() } }
}
