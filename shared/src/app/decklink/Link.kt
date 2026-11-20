package app.decklink

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * The tiny protocol between DriveDeck (front, on the TBox) and KidsDeck (back screen).
 *
 * Both sides sit on the TBox's Wi-Fi hotspot. Messages are small JSON objects sent as
 * UDP packets on [PORT]. Each side broadcasts a "here" message every few seconds so the
 * other can find it, then talks to it directly.
 *
 * Messages (field "t"):
 *  - here    {role, name}                     either side: "I'm here"
 *  - yt      {v: videoId, p: positionMs, s: 1 playing / 0 paused}   front → back, about once a second
 *  - open    {pkg}                            front → back: open this app (Netflix, Prime…)
 *  - ps      {a: "play" | "pause"}            front → back: play or pause what's playing
 *  - stop    {}                               front → back: stop playing along
 */
object Link {
    const val PORT = 47811
    const val ROLE_FRONT = "front"
    const val ROLE_BACK = "back"

    const val YOUTUBE = "com.google.android.youtube"
    const val YOUTUBE_KIDS = "com.google.android.apps.youtube.kids"
    const val NETFLIX = "com.netflix.mediaclient"
    const val PRIME = "com.amazon.avod.thirdpartyclient"
    const val PRIME_TV = "com.amazon.amazonvideo.livingroom"

    fun openSocket(): DatagramSocket = DatagramSocket(null).apply {
        reuseAddress = true
        broadcast = true
        bind(InetSocketAddress(PORT))
    }

    fun msg(type: String, role: String): JSONObject = JSONObject().put("t", type).put("role", role)

    /** Broadcast addresses of every active IPv4 network (e.g. 192.168.43.255 on the hotspot). */
    fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
        }
        if (out.isEmpty()) {
            try { out.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) {}
        }
        return out.distinct()
    }

    fun send(socket: DatagramSocket?, msg: JSONObject, to: InetAddress) {
        if (socket == null) return
        val data = msg.toString().toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(data, data.size, to, PORT))
        } catch (e: Exception) {
        }
    }

    fun broadcast(socket: DatagramSocket?, msg: JSONObject) {
        for (addr in broadcastAddresses()) send(socket, msg, addr)
    }

    fun parse(packet: DatagramPacket): JSONObject? = try {
        JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /** Video id from a YouTube link (watch?v=, youtu.be/, shorts/, live/, embed/), or null. */
    fun youtubeId(text: String?): String? {
        if (text == null) return null
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})"""),
        )
        for (p in patterns) p.find(text)?.let { return it.groupValues[1] }
        return null
    }

    /** Start time in ms from a link's t= parameter ("t=95", "t=1m35s"), or 0. */
    fun youtubeStartMs(text: String?): Long {
        val raw = text?.let { Regex("""[?&]t=([0-9hms]+)""").find(it)?.groupValues?.get(1) } ?: return 0L
        raw.toLongOrNull()?.let { return it * 1000L }
        var total = 0L
        Regex("""(\d+)([hms])""").findAll(raw).forEach {
            val n = it.groupValues[1].toLong()
            total += when (it.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }
        return total * 1000L
    }
}
