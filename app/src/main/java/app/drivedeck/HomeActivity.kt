package app.drivedeck

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class HomeActivity : Activity() {

    companion object {
        private const val KEY_DOCK = Prefs.KEY_DOCK
        private const val KEY_NAV = Prefs.KEY_NAV
        private const val MAX_DOCK = 4
        private const val REQ_LOCATION = 1

        private val NAV_CANDIDATES = listOf(
            "com.waze",
            "com.google.android.apps.maps",
            "app.organicmaps",
            "net.osmand.plus",
            "net.osmand",
            "com.here.app.maps",
            "com.sygic.aura",
            "com.tomtom.gplay.navapp",
        )
        private val DOCK_CANDIDATES = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.android.chrome",
        )
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var content: LinearLayout
    private lateinit var mapCard: FrameLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var map: MapView
    private lateinit var navLabel: TextView
    private lateinit var clock: TextView
    private lateinit var dock: LinearLayout
    private lateinit var drawer: View
    private lateinit var grid: GridView
    private lateinit var art: ImageView
    private lateinit var artBlur: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var source: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnPlay: ImageButton
    private lateinit var speed: TextView
    private lateinit var weekday: TextView
    private lateinit var day: TextView
    private lateinit var month: TextView

    // Apps
    private var apps: List<AppEntry> = emptyList()
    private val adapter = AppAdapter()

    // Location
    private var myLocation: MyLocationNewOverlay? = null
    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { loc -> renderSpeed(loc) }
    private val staleSpeed = Runnable { speed.text = "--" }

    // Media
    private var sessionManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val listenerComponent by lazy { ComponentName(this, MediaListener::class.java) }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderMedia()
        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            refreshSessions()
        }
    }
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadAppsAsync()
            renderDock()
            renderNavLabel()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)

        // osmdroid needs its config before the MapView is inflated.
        OsmConfig.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_home)
        bindViews()
        setupMap()
        setupMedia()
        setupApps()

        updateClock()
        renderDock()
        renderNavLabel()
        renderMedia()
        applyOrientation(resources.configuration)
        loadAppsAsync()

        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        registerSafely(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        registerSafely(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        updateClock()
        startMedia()
        handler.post(progressTick)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startLocation()
        renderDock()
        renderNavLabel()
        hideSystemBars()
    }

    override fun onPause() {
        stopLocation()
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(progressTick)
        stopMedia()
        try { unregisterReceiver(timeReceiver) } catch (e:Exception) {}
        try { unregisterReceiver(packageReceiver) } catch (e:Exception) {}
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already on the home screen closes the drawer.
        closeDrawer()
    }

    @Deprecated("A launcher handles Back itself")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer()
        // Otherwise do nothing: Back should never leave the home screen.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && hasLocationPermission()) startLocation()
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        content = findViewById(R.id.content)
        mapCard = findViewById(R.id.mapCard)
        rightCol = findViewById(R.id.rightCol)
        map = findViewById(R.id.map)
        navLabel = findViewById(R.id.navLabel)
        clock = findViewById(R.id.clock)
        dock = findViewById(R.id.dock)
        drawer = findViewById(R.id.drawer)
        grid = findViewById(R.id.grid)
        art = findViewById(R.id.art)
        artBlur = findViewById(R.id.artBlur)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        source = findViewById(R.id.source)
        progress = findViewById(R.id.progress)
        btnPlay = findViewById(R.id.btnPlay)
        speed = findViewById(R.id.speed)
        weekday = findViewById(R.id.weekday)
        day = findViewById(R.id.day)
        month = findViewById(R.id.month)

        // Rounded corners actually clip the map and album art.
        mapCard.clipToOutline = true
        findViewById<View>(R.id.mediaCard).clipToOutline = true
        art.clipToOutline = true

        clock.setOnLongClickListener { showSettings(); true }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(false)
        map.setTilesScaledToDpi(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(16.0)

        val tiles = map.overlayManager.tilesOverlay
        tiles.setLoadingBackgroundColor(getColor(R.color.card))
        tiles.setLoadingLineColor(getColor(R.color.card))
        tiles.setColorFilter(darkMapFilter())

        val touch = findViewById<View>(R.id.mapTouch)
        touch.setOnClickListener { openNavigation() }
        touch.setOnLongClickListener { chooseNavApp(); true }
    }

    /** Turns the light OpenStreetMap tiles into a calm dark map. */
    private fun darkMapFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val desaturate = ColorMatrix().apply { setSaturation(0.2f) }
        val tint = ColorMatrix(
            floatArrayOf(
                0.82f, 0f, 0f, 0f, 0f,
                0f, 0.85f, 0f, 0f, 2f,
                0f, 0f, 0.95f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        invert.postConcat(desaturate)
        invert.postConcat(tint)
        return ColorMatrixColorFilter(invert)
    }

    private fun setupMedia() {
        if (Build.VERSION.SDK_INT >= 31) {
            artBlur.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
        }
        findViewById<View>(R.id.btnPrev).setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        findViewById<View>(R.id.btnNext).setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT) }
        btnPlay.setOnClickListener { mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        findViewById<View>(R.id.mediaInfo).setOnClickListener { openMediaApp() }
    }

    private fun setupApps() {
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            if (!Apps.launch(this, app)) toast(getString(R.string.open_failed))
            closeDrawer()
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            togglePin(apps[position].packageName)
            true
        }
        val btnApps = findViewById<View>(R.id.btnApps)
        btnApps.setOnClickListener { openDrawer() }
        btnApps.setOnLongClickListener { showSettings(); true }
        findViewById<View>(R.id.btnCloseDrawer).setOnClickListener { closeDrawer() }
    }

    // ---------------------------------------------------------------- layout

    private fun applyOrientation(cfg: Configuration) {
        val portrait = cfg.orientation == Configuration.ORIENTATION_PORTRAIT
        content.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        (mapCard.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            weight = 1.25f
        }
        (rightCol.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            weight = if (portrait) 1.4f else 1f
        }
        mapCard.requestLayout()
        rightCol.requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------------------------------------------------------- clock

    private fun updateClock() {
        val now = Date()
        val loc = Locale.getDefault()
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clock.text = SimpleDateFormat(pattern, loc).format(now)
        weekday.text = SimpleDateFormat("EEEE", loc).format(now).uppercase(loc)
        day.text = SimpleDateFormat("d", loc).format(now)
        month.text = SimpleDateFormat("MMMM", loc).format(now)
    }

    // ---------------------------------------------------------------- dock & drawer

    private fun pinned(): MutableList<String> {
        val saved = prefs.getString(KEY_DOCK, null)
        if (saved != null) return saved.split(",").filter { it.isNotBlank() }.toMutableList()
        val defaults = mutableListOf<String>()
        navPackage()?.let { defaults.add(it) }
        DOCK_CANDIDATES.filter { Apps.isInstalled(this, it) }.forEach {
            if (defaults.size < MAX_DOCK && it !in defaults) defaults.add(it)
        }
        return defaults
    }

    private fun savePinned(list: List<String>) {
        prefs.edit().putString(KEY_DOCK, list.joinToString(",")).apply()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val size = dp(54)
        pinned().filter { Apps.isInstalled(this, it) }.take(MAX_DOCK).forEach { pkg ->
            val icon = ImageView(this)
            icon.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            icon.setImageDrawable(Apps.iconFor(this, pkg, size))
            icon.contentDescription = Apps.label(this, pkg)
            icon.setOnClickListener {
                pressBounce(it)
                if (!Apps.launch(this, pkg)) toast(getString(R.string.open_failed))
            }
            icon.setOnLongClickListener { togglePin(pkg); true }
            dock.addView(icon)
        }
    }

    private fun togglePin(pkg: String) {
        val list = pinned()
        if (pkg in list) {
            list.remove(pkg)
            toast(getString(R.string.unpinned))
        } else {
            if (list.count { Apps.isInstalled(this, it) } >= MAX_DOCK) {
                toast(getString(R.string.dock_full))
                return
            }
            list.add(pkg)
            toast(getString(R.string.pinned))
        }
        savePinned(list)
        renderDock()
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        drawer.alpha = 0f
        drawer.translationY = dp(40).toFloat()
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        drawer.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(180)
            .withEndAction { drawer.visibility = View.GONE }
            .start()
    }

    private fun loadAppsAsync() {
        val ctx = applicationContext
        Thread {
            val list = Apps.load(ctx)
            handler.post {
                apps = list
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: buildCell()
            val app = apps[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
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
            label.setTextColor(Color.WHITE)
            label.textSize = 13f
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

    // ---------------------------------------------------------------- navigation & location

    private fun navPackage(): String? {
        val chosen = prefs.getString(KEY_NAV, null)
        if (chosen != null && Apps.isInstalled(this, chosen)) return chosen
        return NAV_CANDIDATES.firstOrNull { Apps.isInstalled(this, it) }
    }

    private fun renderNavLabel() {
        navLabel.text = navPackage()?.let { Apps.label(this, it) } ?: getString(R.string.navigate)
    }

    private fun openNavigation() {
        val pkg = navPackage()
        if (pkg == null) {
            toast(getString(R.string.no_nav))
            return
        }
        if (!Apps.launch(this, pkg)) toast(getString(R.string.open_failed))
    }

    private fun chooseNavApp() {
        val list = apps.ifEmpty { Apps.load(this) }
        if (list.isEmpty()) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.choose_nav)
            .setItems(list.map { it.label }.toTypedArray()) { _, which ->
                prefs.edit().putString(KEY_NAV, list[which].packageName).apply()
                renderNavLabel()
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: getSystemService(LocationManager::class.java)?.also { locationManager = it } ?: return

        if (myLocation == null) {
            myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { map.overlays.add(it) }
        }
        myLocation?.enableMyLocation()
        myLocation?.enableFollowLocation()

        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
        } catch (e:SecurityException) {
        } catch (e:IllegalArgumentException) {
        }

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                }
            } catch (e:SecurityException) {
            } catch (e:IllegalArgumentException) {
            }
        }
    }

    private fun stopLocation() {
        myLocation?.disableMyLocation()
        try { locationManager?.removeUpdates(locationListener) } catch (e:Exception) {}
        handler.removeCallbacks(staleSpeed)
    }

    private fun renderSpeed(loc: Location) {
        // Only trust real GPS speed; network fixes don't carry a useful one.
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        speed.text = if (kmh < 2) "0" else kmh.toString()
        handler.removeCallbacks(staleSpeed)
        handler.postDelayed(staleSpeed, 8000)
    }

    // ---------------------------------------------------------------- media

    private fun hasMediaAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun startMedia() {
        if (!hasMediaAccess()) {
            renderMedia()
            return
        }
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            sessionManager = msm
        } catch (e:SecurityException) {
            sessionManager = null
        }
        refreshSessions()
    }

    private fun stopMedia() {
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e:Exception) {}
        controller?.unregisterCallback(controllerCallback)
        controller = null
        sessionManager = null
    }

    private fun refreshSessions() {
        val msm = sessionManager
        if (msm == null) {
            renderMedia()
            return
        }
        try {
            pickController(msm.getActiveSessions(listenerComponent))
        } catch (e:SecurityException) {
            pickController(null)
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, handler)
        }
        renderMedia()
    }

    private fun renderMedia() {
        val c = controller
        if (c == null) {
            title.text = getString(if (hasMediaAccess()) R.string.not_playing else R.string.allow_media)
            artist.text = ""
            source.text = ""
            showPlaceholderArt()
            btnPlay.setImageResource(R.drawable.ic_play)
            progress.progress = 0
            return
        }
        val md = c.metadata
        title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: getString(R.string.not_playing)
        artist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        source.text = Apps.label(this, c.packageName)

        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            art.setPadding(0, 0, 0, 0)
            art.setImageBitmap(bmp)
            artBlur.setImageBitmap(bmp)
        } else {
            showPlaceholderArt()
        }

        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateProgress()
    }

    private fun showPlaceholderArt() {
        val p = dp(20)
        art.setPadding(p, p, p, p)
        art.setImageResource(R.drawable.ic_music)
        artBlur.setImageDrawable(null)
    }

    private fun updateProgress() {
        val c = controller
        val duration = c?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = c?.playbackState
        if (c == null || duration <= 0L || state == null) {
            progress.progress = 0
            return
        }
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (elapsed * state.playbackSpeed).toLong()
        }
        progress.progress = ((position.coerceIn(0L, duration) * 1000L) / duration).toInt()
    }

    private fun mediaAction(keyCode: Int) {
        val c = controller
        if (c != null) {
            val tc = c.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tc.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> tc.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            }
        } else {
            // No session access yet: fall back to plain media keys, which most players obey.
            val am = getSystemService(AudioManager::class.java) ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun openMediaApp() {
        if (!hasMediaAccess()) {
            explainThenOpen(
                R.string.media_off_title,
                getString(R.string.listener_label),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            return
        }
        val c = controller ?: return
        try {
            val pi = c.sessionActivity
            if (pi != null) {
                pi.send()
                return
            }
        } catch (e:Exception) {
        }
        Apps.launch(this, c.packageName)
    }

    // ---------------------------------------------------------------- settings

    private fun showSettings() {
        val items = arrayOf(
            getString(R.string.choose_nav),
            getString(R.string.home_controls),
            getString(R.string.media_access),
            getString(R.string.switch_launcher),
            getString(R.string.android_settings),
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseNavApp()
                    1 -> showHomeControls()
                    2 -> explainThenOpen(
                        R.string.media_off_title,
                        getString(R.string.listener_label),
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    )
                    3 -> if (!safeStart(Intent(Settings.ACTION_HOME_SETTINGS))) safeStart(Intent(Settings.ACTION_SETTINGS))
                    4 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun hasGestureAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    /** Floating home button + swipe-up: turn the service on, toggle each part, pick apps to hide in. */
    private fun showHomeControls() {
        if (!hasGestureAccess()) {
            explainThenOpen(
                R.string.home_controls_off_title,
                getString(R.string.gesture_service_label),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            )
            return
        }
        val checked = booleanArrayOf(
            prefs.getBoolean(Prefs.KEY_HOME_BUTTON, true),
            prefs.getBoolean(Prefs.KEY_SWIPE_HOME, true),
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.home_controls)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.opt_home_button), getString(R.string.opt_swipe_home)),
                checked,
            ) { _, which, isChecked ->
                val key = if (which == 0) Prefs.KEY_HOME_BUTTON else Prefs.KEY_SWIPE_HOME
                prefs.edit().putBoolean(key, isChecked).apply()
            }
            .setNeutralButton(R.string.hide_in_apps) { _, _ -> chooseHiddenApps() }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun chooseHiddenApps() {
        val list = apps.ifEmpty { Apps.load(this) }
        if (list.isEmpty()) return
        val hidden = Prefs.hideIn(prefs).toMutableSet()
        val checked = BooleanArray(list.size) { list[it].packageName in hidden }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.hide_in_title)
            .setMultiChoiceItems(list.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                val pkg = list[which].packageName
                if (isChecked) hidden.add(pkg) else hidden.remove(pkg)
                Prefs.setHideIn(prefs, hidden)
            }
            .setPositiveButton(R.string.done, null)
            .show()
    }

    /** Explain what to switch on (and the Android 13 "restricted setting" step), then open that settings page. */
    private fun explainThenOpen(titleRes: Int, serviceLabel: String, settingsIntent: Intent) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titleRes)
            .setMessage(getString(R.string.enable_msg, serviceLabel))
            .setPositiveButton(R.string.open_settings) { _, _ ->
                if (!safeStart(settingsIntent)) safeStart(Intent(Settings.ACTION_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    private fun registerSafely(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e:Exception) {
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e:Exception) {
        false
    }

    private fun pressBounce(v: View) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
