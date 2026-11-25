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
    private val worker = Executors.newSingleThreadExecutor()
    private var importing: Pair<String, Boolean>? = null
    private var pendingFetch = false
    private var storeIcons = true
    private var largeIcons = false
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
        largeIcons = prefs.getBoolean("large_icons", false)
        demo = prefs.getBoolean("demo", false)
        apps = installed()
        if (!prefs.contains("demo") && apps.size < 5) demo = true
        selectedId = savedInstanceState?.getString("selected")
        buildHome()
        fetchStoreIcons()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (::rows.isInitialized) {
            val next = installed()
            if (next.map { it.id to it.title } != apps.map { it.id to it.title }) {
                apps = next
                refreshCards()
                fetchStoreIcons()
            }
        }
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
        val source = if (demo) samples() + apps else apps
        val available = source.filter { showHidden || it.id !in hidden }
        return available.sortedWith(compareBy<Tile> { if (it.id in favorites) 0 else 1 }
            .thenBy { if (it.id in order) order.indexOf(it.id) else Int.MAX_VALUE }
            .thenBy { source.indexOf(it) })
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        backdropTint = View(this)
        frame.addView(backdropTint, FrameLayout.LayoutParams(-1, -1))
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
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
        header.addView(clock)
        settings = text("⚙", 24f, Color.WHITE, true).apply {
            contentDescription = t("Başlatıcı ayarları", "Launcher settings")
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            isClickable = true
            background = glass(false)
            setOnClickListener { showSettings() }
            setOnFocusChangeListener { v, focused ->
                v.background = glass(focused)
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
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val hint = text(t("↑ ↓ ← →  Gezin   •   OK  Aç   •   OK uzun bas  Düzenle", "↑ ↓ ← →  Navigate   •   OK  Open   •   Hold OK  Edit"), 11f, muted())
        hint.visibility = View.GONE
        refreshCards()
    }

    private fun refreshCards() {
        rows.removeAllViews()
        cardViews.clear()
        val data = tiles()
        modeLabel.text = if (demo) "DEMO" else ""
        section.text = if (showHidden) t("Tüm uygulamalar", "All applications") else if (favorites.isNotEmpty()) t("Favoriler ve uygulamalar", "Favorites and applications") else t("Uygulamalar", "Applications")
        if (data.isEmpty()) {
            rows.addView(text(t("Uygulama bulunamadı. Ayarlar menüsünden demo modunu açabilirsin.", "No applications found. Enable demo mode in settings."), 17f, muted()))
            updateShelf(null)
            settings.requestFocus()
            return
        }
        val screenW = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val columns = if (largeIcons) 5 else 6
        val height = ((screenW - 130) / columns.toFloat() / (5f/3f)).roundToInt().coerceIn(48, 190)
        for ((rowIndex, group) in data.chunked(columns).withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(7), dp(10), dp(7), dp(5))
                if (rowIndex == 0) background = rounded(0x38ffffff, 24, 0x55ffffff, 1)
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
        holder.setOnClickListener { launch(tile) }
        holder.setOnLongClickListener { edit(tile); true }
        holder.setOnFocusChangeListener { view, focus ->
            view.animate().scaleX(if(focus) 1.12f else 1f).scaleY(if(focus) 1.12f else 1f).setDuration(150).start()
            view.translationZ = if(focus) dp(14).toFloat() else 0f
            panel.elevation = if(focus) dp(12).toFloat() else dp(3).toFloat()
            border.background = rounded(Color.TRANSPARENT, 13, if(focus) Color.WHITE else Color.TRANSPARENT, 2)
            label.setTextColor(if(focus) Color.WHITE else Color.TRANSPARENT)
            if (focus) { selectedId = tile.id; updateShelf(tile) }
        }
        return holder
    }

    private fun updateShelf(tile: Tile?) {
        val color = tile?.color ?: 0xff25466c.toInt()
        shelf.background = rounded(Color.TRANSPARENT, 0)
        val customBg = tile?.let { asset(it, "backdrop") }?.takeIf { it.isFile }
        backdropImage.setImageDrawable(customBg?.let { Drawable.createFromPath(it.absolutePath) })
        backdropTint.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (customBg != null) intArrayOf(0x22000000, 0x55000000, 0xee080b12.toInt())
            else intArrayOf(blend(color, if(light) Color.WHITE else Color.BLACK, if(light) .2f else .28f), 0xff171c27.toInt(), 0xff080b12.toInt()))
        heroTitle.text = tile?.let { displayName(it) } ?: t("Ana Ekran", "Home")
        heroSub.text = if(tile == null) t("Ayarlar üzerinden demo modunu aç", "Enable demo mode in settings")
            else if(tile.launch == null) tile.subtitle + "  •  Demo" else tile.subtitle
        heroArt.tile = tile
        heroArt.visibility = if (customBg != null) View.INVISIBLE else View.VISIBLE
        heroArt.invalidate()
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
        val list = tiles().map { it.id }.toMutableList()
        val actions = arrayOf(if(tile.id in favorites) t("Favorilerden çıkar", "Remove from favorites") else t("Favorilere ekle", "Add to favorites"),
            if(tile.id in hidden) t("Göster", "Show") else t("Gizle", "Hide"), t("Bir sıra sola taşı", "Move left"), t("Bir sıra sağa taşı", "Move right"), t("Kendi ikonunu seç", "Choose custom icon"), t("Arka plan görselini seç", "Choose background image"), t("Özel görselleri kaldır", "Remove custom artwork"), t("Uygulama adını değiştir", "Rename application"), t("Orijinal adı geri yükle", "Restore original name"))
        glassMenu(displayName(tile), actions) { which ->
            when(which) {
                0 -> if(tile.id in favorites) favorites.remove(tile.id) else favorites.add(tile.id)
                1 -> if(tile.id in hidden) hidden.remove(tile.id) else hidden.add(tile.id)
                4 -> { chooseArt(tile, false); return@glassMenu }
                5 -> { chooseArt(tile, true); return@glassMenu }
                6 -> { asset(tile, "custom").delete(); asset(tile, "backdrop").delete() }
                7 -> { rename(tile); return@glassMenu }
                8 -> prefs.edit().remove("name_" + tile.id).apply()
                2,3 -> {
                    val i = list.indexOf(tile.id)
                    val j = i + if(which == 2) -1 else 1
                    if(i >= 0 && j in list.indices && (list[j] in favorites) == (tile.id in favorites)) {
                        java.util.Collections.swap(list, i, j)
                        order = (list + order.filterNot { it in list }).toMutableList()
                    }
                }
            }
            save()
            refreshCards()
        }
    }

    private fun showSettings() {
        val actions = arrayOf(if(demo) t("Demo modunu kapat", "Disable demo mode") else t("Demo modunu aç", "Enable demo mode"),
            if(light) t("Koyu temaya geç", "Switch to dark theme") else t("Açık temaya geç", "Switch to light theme"),
            if(showHidden) t("Gizli uygulamaları gizle", "Hide hidden applications") else t("Gizli uygulamaları göster", "Show hidden applications"),
            t("Uygulamaları yenile", "Refresh applications"), t("Android ayarları", "Android settings"),
            if (storeIcons) t("Apple TV mağaza ikonlarını kapat", "Disable Apple TV store icons") else t("Apple TV mağaza ikonlarını aç", "Enable Apple TV store icons"),
            if (largeIcons) t("6 sütun", "6 columns") else t("5 büyük sütun", "5 large columns"), t("Varsayılan ana ekran seçimi", "Default home screen"), t("Dil  ·  Türkçe", "Language  ·  English"))
        glassMenu(t("Başlatıcı ayarları", "Launcher settings"), actions) { which ->
            when(which) {
                0 -> { demo = !demo; save(); refreshCards(); fetchStoreIcons() }
                1 -> { light = !light; save(); buildHome() }
                2 -> { showHidden = !showHidden; refreshCards() }
                3 -> { apps = installed(); refreshCards(); fetchStoreIcons() }
                5 -> { storeIcons = !storeIcons; save(); refreshCards(); fetchStoreIcons() }
                6 -> { largeIcons = !largeIcons; save(); refreshCards() }
                8 -> languageMenu()
                7 -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
                    .onFailure { Toast.makeText(this, t("TV yazılımı ana ekran seçimine izin vermiyor olabilir.", "TV firmware may not allow home screen selection."), Toast.LENGTH_LONG).show() }
                4 -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    .onFailure { Toast.makeText(this, t("Ayarlar açılamadı", "Could not open settings"), Toast.LENGTH_SHORT).show() }
            }
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
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xeb536477.toInt(),0xeb273444.toInt(),0xf0182332.toInt())).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1),0x88ffffff.toInt())
            }
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
        win.setDimAmount(.25f)
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
            background = rounded(0x18ffffff, 15)
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) glass(true, 15) else rounded(0x18ffffff,15)
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
