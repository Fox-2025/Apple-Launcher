package com.tvos.launcher

import android.app.Activity
import android.app.AlertDialog
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
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private data class Tile(
        val id: String, val title: String, val subtitle: String,
        val color: Int, val symbol: String, val launch: Intent? = null,
        val icon: Drawable? = null, val banner: Drawable? = null
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
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
        demo = prefs.getBoolean("demo", false)
        apps = installed()
        if (!prefs.contains("demo") && apps.size < 5) demo = true
        selectedId = savedInstanceState?.getString("selected")
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (::rows.isInitialized) {
            val next = installed()
            if (next.map { it.id to it.title } != apps.map { it.id to it.title }) {
                apps = next
                refreshCards()
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
            .putBoolean("light", light).apply()
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
                result[pkg] = Tile(pkg, title, "Uygulamayı aç", palette[(pkg.hashCode() and Int.MAX_VALUE) % palette.size],
                    title.take(1).uppercase(), launch, icon, banner)
            }
        }
        return result.values.sortedBy { it.title.lowercase() }
    }

    private fun samples() = listOf(
        Tile("demo:tv", "TV", "Film ve dizileri keşfet", 0xff213d7a.toInt(), "▶"),
        Tile("demo:music", "Müzik", "Sevdiğin müzikler", 0xffce285b.toInt(), "♫"),
        Tile("demo:movies", "Sinema", "Büyük ekran deneyimi", 0xff5142aa.toInt(), "▣"),
        Tile("demo:photos", "Fotoğraflar", "Anılarını büyük ekranda gör", 0xff178b9a.toInt(), "✿"),
        Tile("demo:games", "Oyunlar", "Eğlenceye katıl", 0xffe07831.toInt(), "✚"),
        Tile("demo:video", "Video", "Video dünyasını keşfet", 0xffa52636.toInt(), "▷"),
        Tile("demo:store", "Mağaza", "Yeni uygulamalar keşfet", 0xff2473d9.toInt(), "↓"),
        Tile("demo:radio", "Radyo", "Canlı yayınlar ve ses", 0xff843ca7.toInt(), "◉"),
        Tile("demo:sports", "Spor", "Karşılaşmalar ve daha fazlası", 0xff217a48.toInt(), "★"),
        Tile("demo:explore", "Keşfet", "Bir sonraki favorini bul", 0xff40556f.toInt(), "◇")
    )

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
            setBackgroundColor(bg())
            setPadding(dp(40), dp(18), dp(40), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("tv  •  HOME", 18f, fg(), true))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        modeLabel = text("", 11f, muted(), true)
        header.addView(modeLabel)
        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            textSize = 20f
            setTextColor(fg())
            setPadding(dp(22), 0, dp(20), 0)
        }
        header.addView(clock)
        settings = text("⚙  Ayarlar", 14f, fg(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            isClickable = true
            background = rounded(if(light) Color.WHITE else 0xff303b50.toInt(), 12)
            setOnClickListener { showSettings() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if(light) Color.WHITE else 0xff303b50.toInt(), 12,
                    if(focused) 0xff53baff.toInt() else Color.TRANSPARENT, 2)
            }
        }
        header.addView(settings)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(36)))

        shelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(0xff203c65.toInt(), 20)
            clipToOutline = true
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(10), dp(8), dp(10))
        }
        copy.addView(text("TOP SHELF   /   ŞİMDİ KEŞFET", 11f, 0xffd1e2ff.toInt(), true))
        heroTitle = text("Ana Ekran", 34f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(7))
        }
        copy.addView(heroTitle)
        heroSub = text("Uygulamalarını seç", 15f, 0xffe0e9f8.toInt()).apply { maxLines = 2 }
        copy.addView(heroSub)
        shelf.addView(copy, LinearLayout.LayoutParams(0, -1, 1f))
        heroArt = ShelfArt()
        shelf.addView(heroArt, LinearLayout.LayoutParams(0, -1, 1.35f))
        root.addView(shelf, LinearLayout.LayoutParams(-1, dp((screenH * 0.32f).roundToInt().coerceIn(130, 330))).apply {
            topMargin = dp(8); bottomMargin = dp(12)
        })

        section = text("Uygulamalar", 17f, fg(), true)
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
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
        scroll.addView(rows, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val hint = text("↑ ↓ ← →  Gezin   •   OK  Aç   •   OK uzun bas  Düzenle", 11f, muted())
        root.addView(hint, LinearLayout.LayoutParams(-1, -2))
        refreshCards()
    }

    private fun refreshCards() {
        rows.removeAllViews()
        cardViews.clear()
        val data = tiles()
        modeLabel.text = if (demo) "DEMO  •  ÖRNEK KARTLAR" else "ANDROID TV"
        section.text = if (showHidden) "Tüm uygulamalar" else if (favorites.isNotEmpty()) "Favoriler ve uygulamalar" else "Uygulamalar"
        if (data.isEmpty()) {
            rows.addView(text("Uygulama bulunamadı. Ayarlar menüsünden demo modunu açabilirsin.", 17f, muted()))
            updateShelf(null)
            settings.requestFocus()
            return
        }
        val screenW = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val height = ((screenW - 120) / 5f / 1.78f).roundToInt().coerceIn(58, 170)
        for (group in data.chunked(5)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            for (tile in group) {
                val card = createCard(tile, height)
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(5); marginEnd = dp(5)
                })
                cardViews[tile.id] = card
            }
            repeat(5-group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
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
            contentDescription = tile.title
        }
        val panel = FrameLayout(this).apply {
            background = gradient(tile.color, 13)
            clipToOutline = true
            elevation = dp(3).toFloat()
        }
        if (tile.banner != null || tile.icon != null) {
            val image = ImageView(this).apply {
                setImageDrawable(tile.banner ?: tile.icon)
                scaleType = if (tile.banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                if (tile.banner == null) setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            panel.addView(image, FrameLayout.LayoutParams(-1, -1))
        } else {
            val symbol = text(tile.symbol, (height*0.47f).coerceIn(25f, 63f), Color.WHITE, true).apply {
                gravity = Gravity.CENTER
            }
            panel.addView(symbol, FrameLayout.LayoutParams(-1, -1))
        }
        if (tile.id in favorites || tile.id in hidden) {
            val mark = text(if(tile.id in hidden) "GİZLİ" else "★", 11f, Color.WHITE, true).apply {
                setPadding(dp(6),dp(3),dp(6),dp(3)); background = rounded(0x99000000.toInt(), 6)
            }
            panel.addView(mark, FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply {
                topMargin=dp(4); marginEnd=dp(4)
            })
        }
        val border = View(this)
        panel.addView(border, FrameLayout.LayoutParams(-1,-1))
        holder.addView(panel, LinearLayout.LayoutParams(-1, dp(height)))
        val label = text(tile.title, 13f, muted(), true).apply {
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(7),0,dp(2))
        }
        holder.addView(label, LinearLayout.LayoutParams(-1,-2))
        holder.setOnClickListener { launch(tile) }
        holder.setOnLongClickListener { edit(tile); true }
        holder.setOnFocusChangeListener { view, focus ->
            view.animate().scaleX(if(focus) 1.09f else 1f).scaleY(if(focus) 1.09f else 1f).setDuration(150).start()
            view.translationZ = if(focus) dp(14).toFloat() else 0f
            panel.elevation = if(focus) dp(12).toFloat() else dp(3).toFloat()
            border.background = rounded(Color.TRANSPARENT, 13, if(focus) Color.WHITE else Color.TRANSPARENT, 2)
            label.setTextColor(if(focus) fg() else muted())
            if (focus) { selectedId = tile.id; updateShelf(tile) }
        }
        return holder
    }

    private fun updateShelf(tile: Tile?) {
        val color = tile?.color ?: 0xff25466c.toInt()
        shelf.background = gradient(blend(color, 0xff182238.toInt(), 0.32f), 20)
        heroTitle.text = tile?.title ?: "Ana Ekran"
        heroSub.text = if(tile == null) "Ayarlar üzerinden demo modunu aç"
            else if(tile.launch == null) tile.subtitle + "  •  Demo" else tile.subtitle
        heroArt.tile = tile
        heroArt.invalidate()
    }

    private fun launch(tile: Tile) {
        val intent = tile.launch
        if (intent == null) {
            Toast.makeText(this, tile.title + " — demo kartı, gerçek uygulama değil.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Uygulama açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit(tile: Tile) {
        val list = tiles().map { it.id }.toMutableList()
        val actions = arrayOf(if(tile.id in favorites) "Favorilerden çıkar" else "Favorilere ekle",
            if(tile.id in hidden) "Göster" else "Gizle", "Bir sıra sola taşı", "Bir sıra sağa taşı")
        AlertDialog.Builder(this).setTitle(tile.title).setItems(actions) { _, which ->
            when(which) {
                0 -> if(tile.id in favorites) favorites.remove(tile.id) else favorites.add(tile.id)
                1 -> if(tile.id in hidden) hidden.remove(tile.id) else hidden.add(tile.id)
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
        }.show()
    }

    private fun showSettings() {
        val actions = arrayOf(if(demo) "Demo modunu kapat" else "Demo modunu aç",
            if(light) "Koyu temaya geç" else "Açık temaya geç",
            if(showHidden) "Gizli uygulamaları gizle" else "Gizli uygulamaları göster",
            "Uygulamaları yenile", "Android ayarları")
        AlertDialog.Builder(this).setTitle("Ana ekran ayarları").setItems(actions) { _, which ->
            when(which) {
                0 -> { demo = !demo; save(); refreshCards() }
                1 -> { light = !light; save(); buildHome() }
                2 -> { showHidden = !showHidden; refreshCards() }
                3 -> { apps = installed(); refreshCards() }
                4 -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                    .onFailure { Toast.makeText(this, "Ayarlar açılamadı", Toast.LENGTH_SHORT).show() }
            }
        }.show()
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
            val image = tile?.banner ?: tile?.icon
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
                paint.textSize = side*.48f
                paint.typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(tile?.symbol ?: "▶",cx,cy-(paint.ascent()+paint.descent())/2,paint)
            }
        }
    }
}
