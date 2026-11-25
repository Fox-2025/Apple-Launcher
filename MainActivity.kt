package com.tvos.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private data class Tile(
        val id: String, val title: String, val subtitle: String,
        val color: Int, val symbol: String, val launch: Intent? = null,
        val icon: Drawable? = null, val banner: Drawable? = null
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var backdropImage: ImageView
    private lateinit var backdropTint: View
    private lateinit var glassSource: GlassBackdrop
    private lateinit var shelfPlayer: TvShelf
    private lateinit var videoSurface: android.view.TextureView
    private lateinit var gridScroll: ScrollView
    private var expandedShelf = false
    private var shelfKey: String? = null
    private var resumed = false
    private val regularFont by lazy { runCatching { Typeface.createFromAsset(assets,"fonts/Inter-Regular.ttf") }.getOrElse { Typeface.create("sans-serif",Typeface.NORMAL) } }
    private val mediumFont by lazy { runCatching { Typeface.createFromAsset(assets,"fonts/Inter-Medium.ttf") }.getOrElse { Typeface.create("sans-serif-medium",Typeface.NORMAL) } }
    private fun uiFont(bold: Boolean) = if(bold) mediumFont else regularFont
    private val worker = Executors.newSingleThreadExecutor()
    private var importing: Pair<String, Boolean>? = null
    private var pendingFetch = false
    private var storeIcons = true
    private var largeIcons = false
    private var movingId: String? = null
    private var moveOrder: MutableList<String>? = null
    private lateinit var moveHint: TextView
    private val columns = 6
    private var swallowKey = -1
    private var english = false
    private fun t(tr: String, en: String) = if (english) en else tr
    private var activeMenu: Dialog? = null
    private val knownIds = mapOf("Netflix" to 363590051L, "YouTube" to 544007664L, "Prime Video" to 545519333L, "Disney+" to 1446075923L, "Apple TV" to 1174078549L, "Apple Music" to 1108187390L, "Max" to 1666653815L, "Spotify" to 324684580L, "Plex" to 383457673L, "VLC" to 650377962L)
    private lateinit var shelf: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroArt: ShelfArt
    private lateinit var section: TextView
    private lateinit var rows: LinearLayout
    private lateinit var settings: TextView
    private lateinit var modeLabel: TextView
    private var apps = emptyList<Tile>()
    private var favorites = mutableListOf<String>()
    private var hidden = mutableSetOf<String>()
    private var order = mutableListOf<String>()
    private var demo = false
    private var light = false
    private var showHidden = false
    private var selectedId: String? = null
    private val cardViews = linkedMapOf<String, View>()
    private val palette = listOf(0xff2449ac.toInt(), 0xffa82754.toInt(), 0xff156c6b.toInt(),
        0xff7239a7.toInt(), 0xffb34f25.toInt(), 0xff3c4e76.toInt())
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun fg() = if (light) 0xff141827.toInt() else Color.WHITE
    private fun muted() = if (light) 0xff505a70.toInt() else 0xffbac5d9.toInt()
    private fun bg() = if (light) 0xffe7ecf5.toInt() else 0xff111723.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        favorites = readList("favorites")
        order = readList("tile_order")
        hidden = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()
        light = prefs.getBoolean("light", false)
        english = prefs.getString("language", "tr") == "en"
        storeIcons = prefs.getBoolean("store_icons", true)
        largeIcons = false
        demo = false
        apps = installed()
        selectedId = savedInstanceState?.getString("selected")
        buildHome()
        fetchStoreIcons()
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("tv_listing_permission_requested", false)) {
            prefs.edit().putBoolean("tv_listing_permission_requested", true).apply()
            runCatching { requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), 230) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 230 && ::shelfPlayer.isInitialized) {
            shelfKey = null
            updateShelf(apps.firstOrNull { it.id == selectedId })
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        immersive()
        if (::rows.isInitialized) {
            val next = installed()
            if (next.map { it.id to it.title } != apps.map { it.id to it.title }) {
                apps = next
                refreshCards()
                fetchStoreIcons()
            }
        }
        if(::shelfPlayer.isInitialized && selectedId != null) { shelfKey=null; updateShelf(apps.firstOrNull { it.id==selectedId }) }
    }

    override fun onPause() {
        resumed=false
        if(::shelfPlayer.isInitialized) shelfPlayer.stop()
        shelfKey=null
        super.onPause()
    }

    override fun onDestroy() {
        if(::shelfPlayer.isInitialized) shelfPlayer.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId)
        super.onSaveInstanceState(outState)
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun readList(key: String): MutableList<String> =
        prefs.getString(key, "").orEmpty().split("|").filter { it.isNotBlank() }.toMutableList()

    private fun save() {
        prefs.edit().putString("favorites", favorites.joinToString("|"))
            .putString("tile_order", order.joinToString("|"))
            .putStringSet("hidden", hidden.toSet()).putBoolean("demo", demo)
            .putBoolean("light", light).putBoolean("store_icons", storeIcons)
            .putBoolean("large_icons", largeIcons).putString("language", if (english) "en" else "tr").apply()
    }

    private fun installed(): List<Tile> {
        val pm = packageManager
        val result = linkedMapOf<String, Tile>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || result.containsKey(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(pkg, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val title = info.loadLabel(pm).toString()
                val icon = runCatching { info.loadIcon(pm) }.getOrNull()
                val banner = runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
                result[pkg] = Tile(pkg, title, t("Uygulamayı aç", "Open application"), palette[(pkg.hashCode() and Int.MAX_VALUE) % palette.size],
                    title.take(1).uppercase(), launch, icon, banner)
            }
        }
        return result.values.sortedBy { it.title.lowercase() }
    }

    private fun displayName(tile: Tile): String {
        val custom = prefs.getString("name_" + tile.id, null)
        if (!custom.isNullOrBlank()) return custom
        val name = tile.title.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[_\\s-]+"), " ")
        return when {
            tile.id in setOf("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings") ||
                name in setOf("settings", "setting", "tv settings", "system settings", "android settings", "ayarlar", "tv ayarları") -> t("Ayarlar", "Settings")
            tile.id in setOf("com.android.tv", "com.google.android.tv", "com.tcl.tv", "com.tcl.tvmanager") && name in setOf("tv", "live tv", "canlı tv") ||
                name in setOf("live tv", "live television", "tv channels", "channels", "canlı tv", "televizyon", "tv kanalları") -> t("Canlı TV", "Live TV")
            name in setOf("inputs", "input", "source", "sources", "tv inputs", "girişler", "kaynaklar") -> t("Girişler", "Inputs")
            name in setOf("media player", "multimedia player", "medya oynatıcı") -> t("Medya Oynatıcı", "Media Player")
            name in setOf("file manager", "file browser", "files", "dosyalar", "dosya yöneticisi") -> t("Dosya Yöneticisi", "File Manager")
            name in setOf("help", "support", "user guide", "yardım", "destek") -> t("Yardım", "Help")
            name in setOf("app store", "apps", "applications", "uygulamalar") -> t("Uygulamalar", "Apps")
            name in setOf("play store", "google play store") -> t("Google Play Mağaza", "Google Play Store")
            name in setOf("screen mirroring", "cast", "miracast", "ekran yansıtma") -> t("Ekran Yansıtma", "Screen Mirroring")
            name in setOf("gallery", "photos", "photo gallery", "galeri", "fotoğraflar") -> t("Fotoğraflar", "Photos")
            name in setOf("browser", "web browser", "internet browser", "tarayıcı") -> t("İnternet Tarayıcısı", "Browser")
            name in setOf("downloads", "download", "indirilenler") -> t("İndirilenler", "Downloads")
            name in setOf("music", "müzik") -> t("Müzik", "Music")
            name in setOf("clock", "saat") -> t("Saat", "Clock")
            name in setOf("weather", "hava durumu") -> t("Hava Durumu", "Weather")
            name in setOf("notifications", "bildirimler") -> t("Bildirimler", "Notifications")
            name in setOf("system update", "software update", "yazılım güncelleme") -> t("Yazılım Güncelleme", "Software Update")
            name in setOf("remote control", "remote", "kumanda") -> t("Kumanda", "Remote Control")
            name in setOf("device preferences", "device settings", "cihaz tercihleri") -> t("Cihaz Tercihleri", "Device Preferences")
            else -> tile.title
        }
    }

    private fun samples() = listOf(
        Tile("demo:apple", "Apple TV", t("Film ve diziler", "Movies and series"), 0xff141414.toInt(), "tv"),
        Tile("demo:netflix", "Netflix", t("Diziler ve filmler", "Series and movies"), 0xff141414.toInt(), "NETFLIX"),
        Tile("demo:youtube", "YouTube", t("Videolar", "Videos"), 0xfff5f5f5.toInt(), "YouTube"),
        Tile("demo:prime", "Prime Video", t("Film ve diziler", "Movies and series"), 0xff0c407a.toInt(), "prime video"),
        Tile("demo:disney", "Disney+", t("Hikâyeler", "Stories"), 0xff101c46.toInt(), "Disney+"),
        Tile("demo:music", "Apple Music", t("Müzik", "Music"), 0xffcf234b.toInt(), "Music"),
        Tile("demo:max", "Max", t("Film ve diziler", "Movies and series"), 0xff142bc7.toInt(), "max"),
        Tile("demo:spotify", "Spotify", t("Müzik ve podcast", "Music and podcasts"), 0xff171b19.toInt(), "Spotify"),
        Tile("demo:plex", "Plex", t("Medya kütüphanesi", "Media library"), 0xff262626.toInt(), "Plex"),
        Tile("demo:vlc", "VLC", t("Medya oynatıcı", "Media player"), 0xffd86b17.toInt(), "VLC")
    )

    private fun asset(tile: Tile, kind: String): File =
        File(filesDir, "art_" + tile.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_" + kind + ".png")

    private fun artwork(tile: Tile): Drawable? {
        val custom = asset(tile, "custom")
        val store = asset(tile, "store")
        return when {
            custom.isFile -> Drawable.createFromPath(custom.absolutePath)
            storeIcons && store.isFile -> Drawable.createFromPath(store.absolutePath)
            else -> null
        }
    }

    private fun requestBytes(url: String, limit: Int): ByteArray {
        val conn = URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 9000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15")
        try {
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > limit) throw java.io.IOException("Asset too large")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    private fun appleId(tile: Tile): Long? {
        val direct = when {
            tile.id == "com.netflix.ninja" -> 363590051L
            tile.id == "com.google.android.youtube.tv" -> 544007664L
            tile.id == "com.amazon.amazonvideo.livingroom" -> 545519333L
            tile.id == "com.disney.disneyplus" -> 1446075923L
            tile.id == "com.apple.atve.androidtv.appletv" -> 1174078549L
            else -> knownIds[tile.title]
        }
        return direct
    }

    private fun fetchStoreIcons() {
        if (!storeIcons || pendingFetch) return
        val batch = tiles().filter { appleId(it) != null && !asset(it, "store").isFile }
        if (batch.isEmpty()) return
        pendingFetch = true
        worker.execute {
            var changed = false
            for (tile in batch) {
                val id = appleId(tile) ?: continue
                runCatching {
                    val page = requestBytes("https://apps.apple.com/us/app/id" + id + "?platform=tv", 12000000).toString(Charsets.UTF_8)
                    val marker = Regex("\"adamId\"\\s*:\\s*\"" + id + "\"")
                    val icon = Regex("\"icon\"\\s*:\\s*\\{[^{}]{0,350}?\"template\"\\s*:\\s*\"([^\"]+?\\.lsr)/\\{w\\}x\\{h\\}\\{c\\}\\.\\{f\\}\"")
                    var url: String? = null
                    for (match in marker.findAll(page)) {
                        val from = match.range.last + 1
                        val next = Regex("\"adamId\"\\s*:").find(page, from)?.range?.first ?: page.length
                        val section = page.substring(from, minOf(next, from + 10000))
                        val found = icon.find(section)?.groupValues?.get(1)
                        if (found != null && found.startsWith("https://") && URL(found).host.endsWith(".mzstatic.com")) {
                            url = found + "/500x300bb.png"
                            break
                        }
                    }
                    val source = url ?: return@runCatching
                    val bytes = requestBytes(source, 4000000)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                    if (bitmap.width.toFloat() / bitmap.height < 1.45f) { bitmap.recycle(); return@runCatching }
                    asset(tile, "store").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    changed = true
                }
            }
            runOnUiThread {
                pendingFetch = false
                if (changed && !isFinishing && !isDestroyed && ::rows.isInitialized) refreshCards()
            }
        }
    }

    private fun chooseArt(tile: Tile, background: Boolean) {
        importing = tile.id to background
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(pick, 701) } catch (e: Exception) {
            importing = null
            Toast.makeText(this, t("Dosya seçici bulunamadı", "File picker unavailable"), Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val request = importing
        importing = null
        if (requestCode != 701 || resultCode != RESULT_OK || request == null) return
        val uri = data?.data ?: return
        val tile = (samples() + apps).firstOrNull { it.id == request.first } ?: return
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val target = if (request.second) 1920 else 800
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > target * 2) sample *= 2
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
                ?: throw java.io.IOException("No bitmap")
            asset(tile, if (request.second) "backdrop" else "custom").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            refreshCards()
        }.onFailure { Toast.makeText(this, t("Görsel yüklenemedi", "Could not load artwork"), Toast.LENGTH_SHORT).show() }
    }

    private fun tiles(): List<Tile> {
        val ranks = moveOrder ?: order
        return apps.filter { showHidden || it.id !in hidden }.sortedBy {
            ranks.indexOf(it.id).let { rank -> if(rank < 0) Int.MAX_VALUE else rank }
        }
    }

    private fun beginMove(tile: Tile) {
        movingId = tile.id
        moveOrder = tiles().map { it.id }.toMutableList()
        selectedId = tile.id
        refreshCards()
    }

    private fun finishMove(commit: Boolean) {
        if(commit) {
            val next = moveOrder.orEmpty()
            order = (next + order.filterNot { it in next }).toMutableList()
            save()
        }
        movingId = null
        moveOrder = null
        refreshCards()
    }

    @Deprecated("Android legacy back callback")
    override fun onBackPressed() {
        if(movingId != null) finishMove(false)
        else if(expandedShelf) setExpanded(false)
        else {
            selectedId = tiles().firstOrNull()?.id
            selectedId?.let { cardViews[it]?.requestFocus() } ?: settings.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.keyCode == swallowKey) {
            if(event.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }
        if(movingId != null) {
            val keys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
            if(event.keyCode in keys) {
                if(event.action == KeyEvent.ACTION_DOWN) {
                    val list = moveOrder ?: return true
                    val i = list.indexOf(movingId)
                    val target = when(event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> if(i % columns > 0) i-1 else i
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(i % columns < columns-1) i+1 else i
                        KeyEvent.KEYCODE_DPAD_UP -> i-columns
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(i/columns < list.lastIndex/columns) minOf(i+columns,list.lastIndex) else i
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { swallowKey=event.keyCode; finishMove(true); return true }
                        KeyEvent.KEYCODE_BACK -> { swallowKey=event.keyCode; finishMove(false); return true }
                        else -> i
                    }
                    if(i >= 0 && target in list.indices && target != i) {
                        val id = list.removeAt(i)
                        list.add(target,id)
                        selectedId=id
                        refreshCards()
                    }
                }
                return true
            }
        }
        if(event.action == KeyEvent.ACTION_DOWN && activeMenu == null && ::shelfPlayer.isInitialized) {
            if(expandedShelf) {
                when(event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> shelfPlayer.next(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> shelfPlayer.next(1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> setExpanded(false)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> selectedId?.let { id -> apps.firstOrNull { it.id==id }?.let { launch(it) } }
                    else -> return super.dispatchKeyEvent(event)
                }
                swallowKey=event.keyCode
                return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP && shelfPlayer.available && tiles().take(columns).any { it.id==selectedId && cardViews[it.id]?.hasFocus()==true }) {
                setExpanded(true)
                swallowKey=event.keyCode
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = uiFont(bold)
            letterSpacing = -0.015f
            includeFontPadding = false
        }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT, width: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (width > 0) setStroke(dp(width), stroke)
        }

    private fun gradient(color: Int, radius: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(color, blend(color, Color.BLACK, 0.4f))).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        fun mix(x: Int, y: Int) = (x * (1f-ratio) + y * ratio).roundToInt().coerceIn(0,255)
        return Color.rgb(mix(Color.red(a),Color.red(b)), mix(Color.green(a),Color.green(b)), mix(Color.blue(a),Color.blue(b)))
    }

    private fun buildHome() {
        if(::shelfPlayer.isInitialized) shelfPlayer.destroy()
        shelfKey=null
        expandedShelf=false
        cardViews.clear()
        val metrics = resources.displayMetrics
        val screenH = (metrics.heightPixels / metrics.density).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(14), dp(36), dp(8))
            clipChildren = false
            clipToPadding = false
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(bg()) }
        backdropImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(backdropImage, FrameLayout.LayoutParams(-1, -1))
        videoSurface = android.view.TextureView(this)
        frame.addView(videoSurface,FrameLayout.LayoutParams(-1,-1))
        backdropTint = View(this)
        frame.addView(backdropTint, FrameLayout.LayoutParams(-1, -1))
        glassSource=GlassBackdrop(backdropImage,videoSurface,backdropTint)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        shelfPlayer = TvShelf(this,videoSurface) { item, bitmap ->
            if(item != null) {
                heroTitle.text=item.title.ifBlank { apps.firstOrNull { it.id==selectedId }?.let { displayName(it) }.orEmpty() }
                heroSub.text=item.description
                heroArt.visibility=View.INVISIBLE
                if(bitmap!=null) backdropImage.setImageBitmap(bitmap)
                backdropTint.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x08000000,0x22000000,0xdd080b12.toInt()))
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this), LinearLayout.LayoutParams(1, 1))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        modeLabel = text("", 10f, Color.WHITE, true)
        header.addView(modeLabel)
        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(22), 0, dp(20), 0)
        }
        clock.typeface=uiFont(false)
        header.addView(clock)
        settings = text("⚙", 24f, Color.WHITE, true).apply {
            contentDescription = t("Başlatıcı ayarları", "Launcher settings")
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            isClickable = true
            background = LiquidGlassDrawable(this,glassSource,dp(22).toFloat())
            setOnClickListener { showSettings() }
            setOnFocusChangeListener { v, focused ->
                v.background = LiquidGlassDrawable(v,glassSource,dp(22).toFloat(),focused=focused)
                v.animate().scaleX(if(focused) 1.10f else 1f).scaleY(if(focused) 1.10f else 1f).setDuration(140).start()
            }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(36)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(36)))

        shelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.TRANSPARENT, 0)
            clipToOutline = true
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(10), dp(8), dp(10))
        }

        heroTitle = text(t("Ana Ekran", "Home"), 42f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(7))
        }
        copy.addView(heroTitle)
        heroSub = text(t("Uygulamalarını seç", "Choose an app"), 15f, 0xffe0e9f8.toInt()).apply { maxLines = 2 }
        copy.addView(heroSub)
        shelf.addView(copy, LinearLayout.LayoutParams(0, -1, 1f))
        heroArt = ShelfArt()
        shelf.addView(heroArt, LinearLayout.LayoutParams(0, -1, 1.15f))
        root.addView(shelf, LinearLayout.LayoutParams(-1, dp((screenH * 0.46f).roundToInt().coerceIn(150, 480))).apply {
            topMargin = dp(8); bottomMargin = dp(12)
        })

        section = text(t("Uygulamalar", "Applications"), 17f, fg(), true)
        section.visibility = View.GONE
        root.addView(section, LinearLayout.LayoutParams(-1, 0))
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
        }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(3), dp(8), dp(3), dp(14))
        }
        scroll.addView(rows, FrameLayout.LayoutParams(-1, -2))
        gridScroll=scroll
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        moveHint = text(t("Yön tuşları: Taşı    •    OK: Kaydet    •    Geri: İptal", "Arrows: Move    •    OK: Save    •    Back: Cancel"), 15f, Color.WHITE, true)
        moveHint.gravity = Gravity.CENTER
        moveHint.background = glass(false)
        moveHint.setPadding(dp(10),dp(6),dp(10),dp(6))
        moveHint.visibility = View.GONE
        root.addView(moveHint, LinearLayout.LayoutParams(-1,-2))
        refreshCards()
    }

    private fun refreshCards() {
        rows.removeAllViews()
        cardViews.clear()
        val data = tiles()
        modeLabel.text = ""
        moveHint.visibility = if(movingId != null) View.VISIBLE else View.GONE
        section.text = if (showHidden) t("Tüm uygulamalar", "All applications") else if (favorites.isNotEmpty()) t("Favoriler ve uygulamalar", "Favorites and applications") else t("Uygulamalar", "Applications")
        if (data.isEmpty()) {
            rows.addView(text(t("Uygulama bulunamadı.", "No applications found."), 17f, muted()))
            updateShelf(null)
            settings.requestFocus()
            return
        }
        val screenW = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val height = ((screenW - 130) / columns.toFloat() / (5f/3f)).roundToInt().coerceIn(48, 190)
        for ((rowIndex, group) in data.chunked(columns).withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(7), dp(10), dp(7), dp(5))
                if (rowIndex == 0) background = LiquidGlassDrawable(this,glassSource,dp(24).toFloat(),dark=true)
            }
            for (tile in group) {
                val card = createCard(tile, height)
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(5); marginEnd = dp(5)
                })
                cardViews[tile.id] = card
            }
            repeat(columns-group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                marginStart=dp(5); marginEnd=dp(5)
            }) }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        val selected = data.firstOrNull { it.id == selectedId } ?: data.first()
        updateShelf(selected)
        cardViews[selected.id]?.post { cardViews[selected.id]?.requestFocus() }
    }

    private fun createCard(tile: Tile, height: Int): View {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = displayName(tile)
        }
        val panel = FrameLayout(this).apply {
            background = gradient(tile.color, 13)
            clipToOutline = true
            elevation = dp(3).toFloat()
        }
        val downloaded = artwork(tile)
        if (downloaded != null || tile.banner != null || tile.icon != null) {
            val image = ImageView(this).apply {
                setImageDrawable(downloaded ?: tile.banner ?: tile.icon)
                scaleType = if (downloaded != null || tile.banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                if (downloaded == null && tile.banner == null) setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            panel.addView(image, FrameLayout.LayoutParams(-1, -1))
        } else {
            val symbol = text(tile.symbol, (height*0.22f).coerceIn(13f, 32f), if(tile.title == "Netflix") 0xffe50914.toInt() else if(tile.title == "YouTube") Color.BLACK else if(tile.title == "Spotify") 0xff1ed760.toInt() else Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            }
            panel.addView(symbol, FrameLayout.LayoutParams(-1, -1))
        }
        if (tile.id in favorites || tile.id in hidden) {
            val mark = text(if(tile.id in hidden) t("GİZLİ", "HIDDEN") else "★", 11f, Color.WHITE, true).apply {
                setPadding(dp(6),dp(3),dp(6),dp(3)); background = rounded(0x99000000.toInt(), 6)
            }
            panel.addView(mark, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply {
                topMargin=dp(4); marginEnd=dp(4)
            })
        }
        val border = View(this)
        panel.addView(border, FrameLayout.LayoutParams(-1,-1))
        holder.addView(panel, LinearLayout.LayoutParams(-1, dp(height)))
        val label = text(displayName(tile), 13f, Color.TRANSPARENT, true).apply {
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(7),0,dp(2))
        }
        holder.addView(label, LinearLayout.LayoutParams(-1,-2))
        holder.setOnClickListener { if(movingId != null) finishMove(true) else launch(tile) }
        holder.setOnLongClickListener { if(movingId == null) edit(tile); true }
        holder.setOnFocusChangeListener { view, focus ->
            view.animate().scaleX(if(focus) 1.12f else 1f).scaleY(if(focus) 1.12f else 1f).setDuration(150).start()
            view.translationZ = if(focus) dp(14).toFloat() else 0f
            panel.elevation = if(focus) dp(12).toFloat() else dp(3).toFloat()
            border.background = rounded(Color.TRANSPARENT, 13, if(movingId == tile.id) 0xff9edaff.toInt() else if(focus) Color.WHITE else Color.TRANSPARENT, if(movingId == tile.id) 3 else 2)
            label.setTextColor(if(focus) Color.WHITE else Color.TRANSPARENT)
            if (focus) { selectedId = tile.id; updateShelf(tile) }
        }
        return holder
    }

    private fun setExpanded(value: Boolean) {
        expandedShelf=value
        gridScroll.visibility=if(value) View.GONE else View.VISIBLE
        shelfPlayer.expand(value)
        if(!value) selectedId?.let { cardViews[it]?.requestFocus() }
    }

    private fun updateShelf(tile: Tile?) {
        val newKey=tile?.id?.takeIf { id -> tiles().take(columns).any { it.id==id } }
        if(shelfKey==newKey && newKey!=null && shelfPlayer.available) return
        shelfKey=newKey
        if(::shelfPlayer.isInitialized) shelfPlayer.select(if(resumed && movingId==null) newKey else null)
        val color = tile?.color ?: 0xff25466c.toInt()
        shelf.background = rounded(Color.TRANSPARENT, 0)
        val customBg = tile?.let { asset(it, "backdrop") }?.takeIf { it.isFile }
        backdropImage.setImageDrawable(customBg?.let { Drawable.createFromPath(it.absolutePath) })
        backdropTint.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (customBg != null) intArrayOf(0x22000000, 0x55000000, 0xee080b12.toInt())
            else intArrayOf(blend(color, if(light) Color.WHITE else Color.BLACK, if(light) .2f else .28f), 0xff171c27.toInt(), 0xff080b12.toInt()))
        heroTitle.text = tile?.let { displayName(it) } ?: t("Ana Ekran", "Home")
        heroSub.text = if(tile == null) t("Uygulama seç", "Choose an app")
            else if(tile.launch == null) tile.subtitle + "  •  Demo" else tile.subtitle
        heroArt.tile = tile
        heroArt.visibility = if (customBg != null) View.INVISIBLE else View.VISIBLE
        heroArt.invalidate()
        glassSource.invalidate()
    }

    private fun launch(tile: Tile) {
        val intent = tile.launch
        if (intent == null) {
            Toast.makeText(this, displayName(tile) + t(" — demo kartı, gerçek uygulama değil.", " — demo tile, not an installed application."), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, t("Uygulama açılamadı", "Could not open application"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit(tile: Tile) {
        val actions = arrayOf(t("Taşı","Move"),
            if(tile.id in hidden) t("Göster","Show") else t("Gizle","Hide"),
            t("Uygulama adını değiştir","Rename application"),
            t("Kendi ikonunu seç","Choose custom icon"),
            t("Arka plan görselini seç","Choose background image"),
            t("Özel görselleri kaldır","Remove custom artwork"),
            t("Orijinal adı geri yükle","Restore original name"))
        glassMenu(displayName(tile), actions) { which ->
            when(which) {
                0 -> { beginMove(tile); return@glassMenu }
                1 -> if(tile.id in hidden) hidden.remove(tile.id) else hidden.add(tile.id)
                2 -> { rename(tile); return@glassMenu }
                3 -> { chooseArt(tile,false); return@glassMenu }
                4 -> { chooseArt(tile,true); return@glassMenu }
                5 -> { asset(tile,"custom").delete(); asset(tile,"backdrop").delete() }
                6 -> prefs.edit().remove("name_" + tile.id).apply()
            }
            save()
            refreshCards()
        }
    }

    private fun showSettings() {
        val actions = arrayOf(t("Genel  ›","General  ›"),t("Görünüm  ›","Appearance  ›"),
            t("Uygulamalar  ›","Applications  ›"),t("Varsayılan ana ekran","Default home screen"),t("TV ayarları","TV settings"))
        glassMenu(t("Ayarlar","Settings"),actions) { which ->
            when(which) {
                0 -> glassMenu(t("Genel","General"),arrayOf(t("Dil  ·  Türkçe","Language  ·  English"))) { languageMenu() }
                1 -> glassMenu(t("Görünüm","Appearance"),arrayOf(if(light) t("Koyu","Dark") else t("Açık","Light"))) { light=!light; save(); buildHome() }
                2 -> glassMenu(t("Uygulamalar","Applications"),arrayOf(
                    if(showHidden) t("Gizli uygulamaları gizle","Hide hidden apps") else t("Gizli uygulamaları göster","Show hidden apps"),
                    if(storeIcons) t("Apple TV ikonlarını kapat","Disable Apple TV icons") else t("Apple TV ikonlarını aç","Enable Apple TV icons"),
                    t("Uygulamaları yenile","Refresh apps"))) { choice ->
                    when(choice) {
                        0 -> { showHidden=!showHidden; refreshCards() }
                        1 -> { storeIcons=!storeIcons; save(); refreshCards(); fetchStoreIcons() }
                        2 -> { apps=installed(); refreshCards(); fetchStoreIcons() }
                    }
                }
                3 -> homeSettings()
                4 -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    .onFailure { Toast.makeText(this,t("Ayarlar açılamadı","Could not open settings"),Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun homeSettings() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = packageManager.resolveActivity(home,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val isDefault = current?.activityInfo?.packageName == packageName
        val status = if(isDefault) t("Bu başlatıcı varsayılan","This launcher is default") else t("Henüz varsayılan değil","Not yet default")
        glassMenu(status,arrayOf(t("Sistem ana ekran seçimi","System home selection"),t("ADB kurulum bilgisi","ADB setup information"))) { choice ->
            if(choice==0) runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                .onFailure { Toast.makeText(this,t("TV yazılımı seçimi engelliyor. ADB bilgisine bak.","Firmware blocks selection. See ADB information."),Toast.LENGTH_LONG).show() }
            else glassMenu(t("Bilgisayardan ADB","ADB from computer"),arrayOf("adb shell cmd package set-home-activity com.tvos.launcher/.MainActivity")) { }
        }
    }

    private fun glass(focused: Boolean, radius: Int = 22): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (focused) intArrayOf(0xd9ffffff.toInt(), 0x66ffffff, 0x45ffffff)
            else intArrayOf(0x56ffffff, 0x28ffffff, 0x15ffffff)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) 0xeeffffff.toInt() else 0x55ffffff)
        }

    private fun newGlassDialog(title: String): Triple<Dialog, LinearLayout, LinearLayout> {
        activeMenu?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(16),dp(18),dp(16))
            background = LiquidGlassDrawable(this,glassSource,dp(30).toFloat(),dark=true)
        }
        val heading = text(title, 24f, Color.WHITE, true).apply {
            setPadding(dp(10),dp(3),dp(10),dp(15))
        }
        body.addView(heading)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(items, FrameLayout.LayoutParams(-1,-2))
        body.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(body)
        dialog.setOnDismissListener { if (activeMenu === dialog) activeMenu = null }
        activeMenu = dialog
        return Triple(dialog,body,items)
    }

    private fun presentGlass(dialog: Dialog) {
        dialog.show()
        val win = dialog.window ?: return
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * .43f).roundToInt().coerceAtLeast(dp(240)).coerceAtMost(dp(470))
        val height = (metrics.heightPixels * .86f).roundToInt()
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.setDimAmount(.15f)
        if(android.os.Build.VERSION.SDK_INT >= 31) {
            win.setBackgroundBlurRadius(dp(26))
            win.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val blurAttrs = win.attributes
            blurAttrs.blurBehindRadius = dp(12)
            win.attributes = blurAttrs
        }
        win.setLayout(width,height)
        win.setGravity(Gravity.TOP or Gravity.END)
        val attrs = win.attributes
        attrs.x = dp(18)
        attrs.y = dp(18)
        win.attributes = attrs
    }

    private fun menuRow(label: String, onClick: () -> Unit): TextView =
        text(label, 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14),dp(9),dp(14),dp(9))
            minHeight = dp(40)
            background = rounded(Color.TRANSPARENT, 15)
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) glass(true, 15) else rounded(Color.TRANSPARENT,15)
                setTextColor(if (focused) 0xff101b29.toInt() else Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

    private fun glassMenu(title: String, actions: Array<String>, action: (Int) -> Unit) {
        val (dialog, _, items) = newGlassDialog(title)
        var first: View? = null
        actions.forEachIndexed { index, label ->
            val row = menuRow(label) { dialog.dismiss(); action(index) }
            items.addView(row, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(5) })
            if (first == null) first = row
        }
        presentGlass(dialog)
        first?.requestFocus()
    }

    private fun languageMenu() {
        glassMenu(t("Dil", "Language"), arrayOf(
            (if (!english) "✓  " else "") + "Türkçe",
            (if (english) "✓  " else "") + "English"
        )) { choice ->
            english = choice == 1
            save()
            apps = installed()
            buildHome()
        }
    }

    private fun rename(tile: Tile) {
        val (dialog, _, items) = newGlassDialog(t("Uygulama adını değiştir", "Rename application"))
        val field = EditText(this).apply {
            setText(displayName(tile))
            typeface = uiFont(false)
            letterSpacing = -0.015f
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setPadding(dp(12),dp(12),dp(12),dp(12))
            background = glass(false, 15)
        }
        items.addView(field, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })
        items.addView(menuRow(t("Kaydet", "Save")) {
            val value = field.text.toString().trim().take(60)
            if(value.isBlank()) prefs.edit().remove("name_" + tile.id).apply()
            else prefs.edit().putString("name_" + tile.id, value).apply()
            dialog.dismiss()
            refreshCards()
        })
        items.addView(menuRow(t("İptal", "Cancel")) { dialog.dismiss() })
        presentGlass(dialog)
        field.requestFocus()
    }

    private inner class ShelfArt : View(this@MainActivity) {
        var tile: Tile? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if(w <= 0 || h <= 0) return
            val color = tile?.color ?: 0xff245487.toInt()
            paint.shader = RadialGradient(w*0.65f,h*0.48f,h*1.1f,
                intArrayOf(blend(color, Color.WHITE, .42f),color, Color.TRANSPARENT),
                floatArrayOf(0f,.42f,1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint)
            paint.shader = null
            for(i in 0..4) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = h*.065f
                paint.color = Color.argb(25+i*7,255,255,255)
                val offset = i*h*.14f
                canvas.drawCircle(w*.76f,h*.56f,h*.36f+offset,paint)
            }
            paint.style = Paint.Style.FILL
            val side = h*.61f
            val cx = w*.62f
            val cy = h*.49f
            val rect = RectF(cx-side/2,cy-side/2,cx+side/2,cy+side/2)
            paint.color = blend(color,Color.BLACK,.27f)
            canvas.drawRoundRect(rect,h*.12f,h*.12f,paint)
            val image = tile?.let { artwork(it) } ?: tile?.banner ?: tile?.icon
            if(image != null) {
                val pad = side*.12f
                val available = side-2*pad
                val iw = image.intrinsicWidth.coerceAtLeast(1).toFloat()
                val ih = image.intrinsicHeight.coerceAtLeast(1).toFloat()
                val scale = minOf(available/iw,available/ih)
                val dw = iw*scale; val dh = ih*scale
                val original = Rect(image.bounds)
                image.setBounds((cx-dw/2).toInt(),(cy-dh/2).toInt(),(cx+dw/2).toInt(),(cy+dh/2).toInt())
                image.draw(canvas)
                image.bounds = original
            } else {
                paint.color = Color.WHITE
                paint.textSize = side*.18f
                paint.typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(tile?.symbol ?: "▶",cx,cy-(paint.ascent()+paint.descent())/2,paint)
            }
        }
    }
}
