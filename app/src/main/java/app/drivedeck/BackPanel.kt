package app.drivedeck

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.decklink.Link
import java.util.Locale
import kotlin.math.roundToInt

/** Things the back-screen panel, the share menu and the long-press all do. */
object BackActions {

    fun appName(pkg: String?): String = when (pkg) {
        Link.NETFLIX -> "Netflix"
        Link.PRIME, Link.PRIME_TV -> "Prime Video"
        Link.YOUTUBE, Link.YOUTUBE_KIDS -> "YouTube"
        null, "" -> ""
        else -> pkg
    }

    fun niceName(name: String): String =
        name.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun clipboardText(context: Context): String? = try {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
    } catch (e: Exception) {
        null
    }

    /**
     * Sends a YouTube, Netflix or Prime link (from the clipboard or the share menu) to the
     * back, the way the panel's mode says. Returns false if there was no usable link.
     * [backToYouTube]: in Together mode, go back to YouTube at the front afterwards.
     */
    fun sendLink(context: Context, text: String?, backToYouTube: Boolean): Boolean {
        RearLink.init(context)
        val yt = Link.youtubeId(text)
        val url = Link.firstUrl(text)
        val where = RearLink.targetedBacks().joinToString { niceName(it.name) }
        val sent = if (where.isBlank()) context.getString(R.string.rear_sent_searching)
        else context.getString(R.string.rear_sent, where)
        when {
            yt != null && RearLink.mode == RearLink.MODE_TOGETHER -> {
                RearLink.playTogether(context, yt, Link.youtubeStartMs(text))
                toast(context, sent)
                if (backToYouTube) Apps.launch(context, Link.YOUTUBE)
            }
            yt != null -> {
                RearLink.playSolo(context, yt, Link.youtubeStartMs(text))
                toast(context, sent)
            }
            url != null -> {
                RearLink.openUrl(context, url, Link.appForUrl(url))
                toast(context, sent)
            }
            else -> {
                toast(context, context.getString(R.string.rear_no_copied_link))
                return false
            }
        }
        return true
    }

    /** Play or pause. Together: the front's player leads and the back follows. */
    fun playPause(context: Context, play: Boolean) {
        RearLink.init(context)
        val pkg = if (RearLink.mode == RearLink.MODE_TOGETHER) {
            if (RearLink.ytVideo != null) Link.YOUTUBE else RearLink.followPkg
        } else null
        val c = pkg?.let { RearLink.controllerFor(it) }
        if (c != null) {
            if (play) c.transportControls.play() else c.transportControls.pause()
            if (pkg != Link.YOUTUBE) RearLink.playPause(context, play)
        } else {
            RearLink.playPause(context, play)
        }
    }

    fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * One window with everything for the back screens: which screen, together or back only,
 * play / pause / stop, volume and mute, start a copied link, open Netflix, Prime or YouTube.
 */
class BackPanel(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private val dialog = Dialog(activity)
    private val density = activity.resources.displayMetrics.density

    private lateinit var status: TextView
    private lateinit var screenRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var muteBtn: TextView
    private lateinit var volume: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var delaySection: LinearLayout
    private lateinit var delayLabel: TextView
    private var screenKey: String? = null
    private var modeKey: String? = null
    private var dragging = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    fun show() {
        RearLink.init(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(build())
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = activity.resources.displayMetrics
            w.setLayout(minOf((dm.widthPixels * 0.94f).roundToInt(), dp(980)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { handler.removeCallbacks(refresh) }
        dialog.show()
        handler.post(refresh)
    }

    // ---------------------------------------------------------------- layout

    private fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.card), 26)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }

        // Header: title, status, close.
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(activity).apply {
            setText(R.string.rear_title)
            setTextColor(color(R.color.text))
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        status = TextView(activity).apply {
            setTextColor(color(R.color.text2))
            textSize = 14f
        }
        titles.addView(status)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(pill("✕", false) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val scroll = ScrollView(activity)
        val cols = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(14) })
        cols.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        scroll.addView(cols)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Left: screen, mode, playback, volume.
        left.addView(label(R.string.panel_screen))
        screenRow = row()
        left.addView(screenRow)

        left.addView(label(R.string.panel_mode))
        modeRow = row()
        left.addView(modeRow)

        left.addView(label(R.string.panel_playback))
        val transport = row()
        transport.addView(iconButton(R.drawable.ic_play, R.string.cd_play) { BackActions.playPause(activity, true) })
        transport.addView(iconButton(R.drawable.ic_pause, R.string.cd_play) { BackActions.playPause(activity, false) })
        transport.addView(pill(activity.getString(R.string.panel_stop), false) {
            RearLink.stop(activity)
            BackActions.toast(activity, activity.getString(R.string.rear_stopped))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginStart = dp(10) })
        left.addView(transport)

        left.addView(label(R.string.panel_volume))
        val volRow = row()
        muteBtn = pill(activity.getString(R.string.panel_mute), false) {
            val muted = RearLink.targetedBacks().any { it.muted }
            RearLink.setMuted(activity, !muted)
        }
        volRow.addView(muteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        volume = SeekBar(activity).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent))
            thumbTintList = android.content.res.ColorStateList.valueOf(color(R.color.text))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) volumeLabel.text = "$p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    RearLink.setVolume(activity, sb.progress)
                }
            })
        }
        volRow.addView(volume, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        volumeLabel = TextView(activity).apply {
            setTextColor(color(R.color.text2))
            textSize = 14f
            minWidth = dp(44)
            gravity = Gravity.END
        }
        volRow.addView(volumeLabel)
        left.addView(volRow)

        // Right: start something, apps, Bluetooth delay.
        right.addView(label(R.string.panel_start))
        right.addView(pill(activity.getString(R.string.panel_play_copied), true) {
            if (BackActions.sendLink(activity, BackActions.clipboardText(activity), backToYouTube = true) &&
                RearLink.mode == RearLink.MODE_TOGETHER) dialog.dismiss()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(4) })
        right.addView(TextView(activity).apply {
            setText(R.string.panel_copy_hint)
            setTextColor(color(R.color.text2))
            textSize = 13f
            setPadding(dp(4), dp(6), dp(4), 0)
        })

        right.addView(label(R.string.panel_open))
        val appsRow = row()
        listOf(Link.NETFLIX, Link.PRIME, Link.YOUTUBE).forEachIndexed { i, pkg ->
            appsRow.addView(pill(BackActions.appName(pkg), false) {
                RearLink.openApp(activity, pkg)
                BackActions.toast(activity, activity.getString(R.string.rear_opened, BackActions.appName(pkg)))
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        right.addView(appsRow)

        delaySection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        delaySection.addView(label(R.string.panel_delay))
        val delayRow = row()
        delayRow.addView(pill("−", false) { RearLink.delayMs = RearLink.delayMs - 50; render() },
            LinearLayout.LayoutParams(dp(56), dp(48)))
        delayLabel = TextView(activity).apply {
            setTextColor(color(R.color.text))
            textSize = 17f
            gravity = Gravity.CENTER
        }
        delayRow.addView(delayLabel, LinearLayout.LayoutParams(dp(96), dp(48)))
        delayRow.addView(pill("+", false) { RearLink.delayMs = RearLink.delayMs + 50; render() },
            LinearLayout.LayoutParams(dp(56), dp(48)))
        delaySection.addView(delayRow)
        delaySection.addView(TextView(activity).apply {
            setText(R.string.panel_delay_hint)
            setTextColor(color(R.color.text2))
            textSize = 13f
            setPadding(dp(4), dp(6), dp(4), 0)
        })
        right.addView(delaySection)

        return root
    }

    // ---------------------------------------------------------------- state

    private fun render() {
        val backs = RearLink.connectedBacks()
        val names = backs.map { it.name }

        // Screen choice: each back screen, plus "Both" when there are two or more.
        // Only rebuilt when something changed, so taps never land on a vanishing button.
        val current = RearLink.target.takeIf { t -> t == RearLink.TARGET_ALL || names.any { it.equals(t, true) } }
            ?: names.firstOrNull { it.contains("RECHTS", true) || it.contains("RIGHT", true) } ?: names.firstOrNull()
        if (current != null && RearLink.target != current) RearLink.target = current
        val sKey = names.joinToString("|") + ">" + current
        if (sKey != screenKey) {
            screenKey = sKey
            screenRow.removeAllViews()
            if (names.isEmpty()) {
                screenRow.addView(TextView(activity).apply {
                    setText(R.string.rear_missing)
                    setTextColor(color(R.color.text2))
                    textSize = 14f
                })
            } else {
                val options = names + if (names.size > 1) listOf(RearLink.TARGET_ALL) else emptyList()
                options.forEachIndexed { i, opt ->
                    val text = if (opt == RearLink.TARGET_ALL) activity.getString(R.string.panel_both) else shortName(opt)
                    screenRow.addView(pill(text, opt.equals(current, true)) {
                        RearLink.target = opt
                        render()
                    }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) })
                }
            }
        }

        // Mode.
        val together = RearLink.mode == RearLink.MODE_TOGETHER
        val mKey = together.toString()
        if (mKey != modeKey) {
            modeKey = mKey
            modeRow.removeAllViews()
            modeRow.addView(pill(activity.getString(R.string.panel_together), together) {
                RearLink.mode = RearLink.MODE_TOGETHER
                render()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            modeRow.addView(pill(activity.getString(R.string.panel_solo), !together) {
                RearLink.mode = RearLink.MODE_SOLO
                render()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }
        delaySection.visibility = if (together) View.VISIBLE else View.GONE
        delayLabel.text = String.format(Locale.getDefault(), "%.2f s", RearLink.delayMs / 1000f)

        // Status and volume of the chosen screens.
        val chosen = RearLink.targetedBacks()
        status.text = if (chosen.isEmpty()) activity.getString(R.string.panel_none)
        else chosen.joinToString("   ·   ") { b -> "${shortName(b.name)}: ${describe(b)}" }
        val vol = chosen.map { it.volume }.filter { it >= 0 }.maxOrNull()
        if (!dragging && vol != null) {
            volume.progress = vol
            volumeLabel.text = "$vol%"
        }
        val muted = chosen.isNotEmpty() && chosen.all { it.muted }
        muteBtn.text = activity.getString(if (muted) R.string.panel_unmute else R.string.panel_mute)
        muteBtn.background = pillBg(muted)
        muteBtn.setTextColor(if (muted) Color.WHITE else color(R.color.text))
    }

    private fun describe(b: RearLink.Back): String {
        if (!b.follow) return activity.getString(R.string.panel_follow_off)
        val what = when (b.what) {
            "yt-sync" -> activity.getString(R.string.panel_what_together)
            "yt-solo" -> "YouTube"
            "" -> activity.getString(R.string.panel_what_home)
            else -> BackActions.appName(b.what)
        }
        val state = if (b.playing) "▶" else "❚❚"
        val vol = if (b.muted) activity.getString(R.string.panel_muted) else if (b.volume >= 0) "${b.volume}%" else ""
        return listOf(what, if (b.what.isNotEmpty()) state else "", vol).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** "ACHTERIN RECHTS" → "Rechts". */
    private fun shortName(name: String): String {
        val n = BackActions.niceName(name)
        val last = n.substringAfterLast(' ', n)
        return if (n.contains(' ')) last.replaceFirstChar { it.titlecase(Locale.getDefault()) } else n
    }

    // ---------------------------------------------------------------- small views

    private fun label(res: Int) = TextView(activity).apply {
        setText(res)
        setTextColor(color(R.color.text2))
        textSize = 12f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(2), dp(16), 0, dp(8))
    }

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun pill(text: String, selected: Boolean, onClick: () -> Unit) = TextView(activity).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (selected) Color.WHITE else color(R.color.text))
        setPadding(dp(16), 0, dp(16), 0)
        background = pillBg(selected)
        isClickable = true
        setOnClickListener {
            it.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }.start()
            onClick()
        }
    }

    private fun iconButton(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(desc)
        background = activity.getDrawable(R.drawable.bg_circle)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun pillBg(selected: Boolean) = rounded(if (selected) color(R.color.accent) else color(R.color.card2), 100)

    private fun rounded(c: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(c)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun color(res: Int) = activity.getColor(res)
    private fun dp(v: Int): Int = (v * density).roundToInt()
}
