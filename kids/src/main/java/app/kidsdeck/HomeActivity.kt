package app.kidsdeck

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The kids' home screen: a clock, a few big app tiles, and nothing to get lost in. */
class HomeActivity : Activity() {

    companion object {
        private const val PARENT_HOLD_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var linkDot: View
    private lateinit var linkText: TextView
    private lateinit var screenName: TextView
    private var renderedApps: List<String> = emptyList()
    private lateinit var drawer: View
    private lateinit var grid: GridView
    private var allApps: List<Pair<AppEntry, Drawable?>> = emptyList()
    private val adapter = AllAppsAdapter()

    private val nl = Locale("nl", "NL")
    private val linkListener: () -> Unit = { handler.post { renderLink() } }
    private val openParent = Runnable { showParentMenu() }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderTiles(force = true)
            loadAllApps()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<View>(R.id.root).background = OemBackground(resources.displayMetrics.density)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        tiles = findViewById(R.id.tiles)
        linkDot = findViewById(R.id.linkDot)
        linkText = findViewById(R.id.linkText)
        screenName = findViewById(R.id.screenName)
        setupParentHold(findViewById(R.id.clockBox))
        setupDrawer()
        LinkService.start(this)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        LinkService.addListener(linkListener)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateClock()
        renderLink()
        renderTiles()
        screenName.text = Prefs.screenName(this)
    }

    override fun onStop() {
        LinkService.removeListener(linkListener)
        try { unregisterReceiver(timeReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(packageReceiver) } catch (e: Exception) {}
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while on the home screen closes the app list.
        closeDrawer()
    }

    @Deprecated("A launcher handles Back itself")
    override fun onBackPressed() {
        // Back closes the app list, but never leaves the home screen.
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- top bar

    private fun updateClock() {
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        val now = Date()
        clock.text = SimpleDateFormat(pattern, nl).format(now)
        date.text = SimpleDateFormat("EEEE d MMMM", nl).format(now).uppercase(nl)
    }

    private fun renderLink() {
        val on = LinkService.frontConnected()
        linkText.setText(if (on) R.string.front_connected else R.string.front_missing)
        linkText.setTextColor(getColor(if (on) R.color.text else R.color.text2))
        linkDot.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.ok else R.color.text2))
    }

    // ---------------------------------------------------------------- tiles

    private fun renderTiles(force: Boolean = false) {
        val list = Prefs.apps(this).filter { Apps.isInstalled(this, it) }
        if (!force && list == renderedApps && tiles.childCount > 0) return
        renderedApps = list
        tiles.removeAllViews()

        if (list.isEmpty()) {
            tiles.addView(TextView(this).apply {
                setText(R.string.no_apps)
                setTextColor(getColor(R.color.text2))
                textSize = 18f
                gravity = Gravity.CENTER
            })
            return
        }

        // Up to 4 in one row; more go on two rows.
        val rows = if (list.size <= 4) 1 else 2
        val perRow = ceil(list.size / rows.toDouble()).toInt()
        list.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            chunk.forEach { pkg -> row.addView(buildTile(pkg, rows), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                val m = dp(10)
                setMargins(m, m, m, m)
            }) }
            // Keep tile widths equal when the last row is shorter.
            repeat(perRow - chunk.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
            }
            tiles.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun buildTile(pkg: String, rows: Int): View {
        val iconSize = dp(if (rows == 1) 112 else 76)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_tile)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageDrawable(Apps.icon(this@HomeActivity, pkg, iconSize))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))

        val label = TextView(this).apply {
            text = Apps.label(this@HomeActivity, pkg)
            setTextColor(getColor(R.color.text))
            textSize = if (rows == 1) 22f else 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        tile.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (rows == 1) 20 else 12)
        })

        // Audi-style red bar that lights up when the tile is pressed.
        val bar = View(this).apply {
            setBackgroundResource(R.drawable.bg_accent_bar)
            alpha = 0f
        }
        tile.addView(bar, LinearLayout.LayoutParams(dp(34), dp(3)).apply { topMargin = dp(14) })

        tile.setOnClickListener {
            bar.animate().alpha(1f).setDuration(90).start()
            tile.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).withEndAction {
                tile.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if (!Apps.launch(this, pkg)) toast(getString(R.string.open_failed))
                handler.postDelayed({ bar.alpha = 0f }, 600)
            }.start()
        }
        return tile
    }

    // ---------------------------------------------------------------- all apps

    private fun setupDrawer() {
        drawer = findViewById(R.id.drawer)
        grid = findViewById(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val pkg = allApps[position].first.packageName
            if (!Apps.launch(this, pkg)) toast(getString(R.string.open_failed))
            closeDrawer()
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            toggleOnHome(allApps[position].first.packageName)
            true
        }
        findViewById<View>(R.id.btnApps).setOnClickListener { openDrawer() }
        findViewById<View>(R.id.btnCloseDrawer).setOnClickListener { closeDrawer() }
        loadAllApps()
    }

    private fun openDrawer() {
        if (allApps.isEmpty()) loadAllApps()
        grid.setSelection(0)
        drawer.alpha = 0f
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).setDuration(160).start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawer.animate().alpha(0f).setDuration(140).withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun loadAllApps() {
        val size = dp(64)
        Thread {
            val list = Apps.load(this).map { it to Apps.icon(this, it.packageName, size) }
            handler.post {
                allApps = list
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun toggleOnHome(pkg: String) {
        val list = Prefs.apps(this).toMutableList()
        when {
            pkg in list -> { list.remove(pkg); toast(getString(R.string.removed_home)) }
            list.size >= 8 -> { toast(getString(R.string.home_full)); return }
            else -> { list.add(pkg); toast(getString(R.string.added_home)) }
        }
        Prefs.setApps(this, list)
        renderTiles(force = true)
    }

    private inner class AllAppsAdapter : BaseAdapter() {
        override fun getCount(): Int = allApps.size
        override fun getItem(position: Int): Any = allApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: buildCell()
            val (app, icon) = allApps[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun buildCell(): LinearLayout {
            val cell = LinearLayout(this@HomeActivity)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(0, dp(6), 0, dp(6))
            cell.addView(ImageView(this@HomeActivity), LinearLayout.LayoutParams(dp(64), dp(64)))
            val label = TextView(this@HomeActivity)
            label.setTextColor(getColor(R.color.text))
            label.textSize = 14f
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.gravity = Gravity.CENTER
            cell.addView(
                label,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
            return cell
        }
    }

    // ---------------------------------------------------------------- parent menu

    /** Hold the clock for 3 seconds: short enough for a parent, long enough for small kids. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupParentHold(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().alpha(0.5f).setDuration(PARENT_HOLD_MS).start()
                    handler.postDelayed(openParent, PARENT_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(openParent)
                    view.animate().cancel()
                    view.alpha = 1f
                }
            }
            true
        }
    }

    private fun showParentMenu() {
        val follow = Prefs.follow(this)
        val items = arrayOf(
            getString(R.string.parent_apps),
            getString(R.string.parent_name) + " · " + Prefs.screenName(this).lowercase(nl),
            if (follow) "Bediening vanaf voorin: aan" else "Bediening vanaf voorin: uit",
            getString(R.string.parent_media),
            getString(R.string.parent_all_apps),
            getString(R.string.parent_wifi),
            getString(R.string.parent_launcher),
            getString(R.string.parent_settings),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseApps()
                    1 -> chooseName()
                    2 -> {
                        Prefs.get(this).edit().putBoolean(Prefs.KEY_FOLLOW, !follow).apply()
                        toast(if (!follow) "Voorin kan dit scherm nu bedienen" else "Voorin kan dit scherm niet meer bedienen")
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle(R.string.parent_media)
                        .setMessage(R.string.parent_media_msg)
                        .setPositiveButton(R.string.open_settings) { _, _ ->
                            safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    4 -> openAnyApp()
                    5 -> safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
                    6 -> if (!safeStart(Intent(Settings.ACTION_HOME_SETTINGS))) safeStart(Intent(Settings.ACTION_SETTINGS))
                    7 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun chooseApps() {
        val all = Apps.load(this)
        val current = Prefs.apps(this)
        // Chosen apps first, in their order, then the rest.
        val ordered = current.mapNotNull { pkg -> all.firstOrNull { it.packageName == pkg } } +
            all.filter { it.packageName !in current }
        val checked = BooleanArray(ordered.size) { ordered[it].packageName in current }
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_apps)
            .setMultiChoiceItems(ordered.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                val keep = current.filter { pkg -> ordered.indices.any { ordered[it].packageName == pkg && checked[it] } }
                val added = ordered.indices.filter { checked[it] && ordered[it].packageName !in current }
                    .map { ordered[it].packageName }
                Prefs.setApps(this, (keep + added).take(8))
                renderTiles(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseName() {
        val names = Prefs.NAMES
        val current = names.indexOf(Prefs.screenName(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_name)
            .setSingleChoiceItems(names.map { it.lowercase(nl).replaceFirstChar { c -> c.titlecase(nl) } }.toTypedArray(), current) { d, which ->
                Prefs.get(this).edit().putString(Prefs.KEY_NAME, names[which]).apply()
                screenName.text = names[which]
                d.dismiss()
            }
            .show()
    }

    private fun openAnyApp() {
        val all = Apps.load(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_all_apps)
            .setItems(all.map { it.label }.toTypedArray()) { _, i ->
                if (!Apps.launch(this, all[i].packageName)) toast(getString(R.string.open_failed))
            }
            .show()
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
