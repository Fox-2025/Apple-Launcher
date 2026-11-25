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
    private var returnedFromApp=false
    private var lastOpenedId:String?=null
    private lateinit var homeHeader:View
    private lateinit var moveBackdrop:ImageView
    private inner class SoftFocusLayout(private val soften:()->Boolean):LinearLayout(this@MainActivity) {
        private var snapshot:Bitmap?=null
        private val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        override fun dispatchDraw(canvas:Canvas) {
            if(!soften() || width<=0 || height<=0) { snapshot=null; super.dispatchDraw(canvas); return }
            val w=maxOf(1,width/2); val h=maxOf(1,height/2)
            val b=snapshot?.takeIf { it.width==w && it.height==h } ?: runCatching {
                Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {
                    val c=Canvas(it); c.scale(w.toFloat()/width,h.toFloat()/height); super.dispatchDraw(c); snapshot=it
                    // A subtle 1-pixel filter at half resolution, preserving artwork geometry.
                    val p=IntArray(w*h); it.getPixels(p,0,w,0,0,w,h)
                    val q=p.copyOf()
                    for(y in 1 until h-1) for(x in 1 until w-1) {
                        val k=y*w+x; val a=p[k-1]; val b=p[k]; val d=p[k+1]; val e=p[k-w]; val g=p[k+w]
                        fun avg(shift:Int)=(((a ushr shift and 255)+(b ushr shift and 255)*4+(d ushr shift and 255)+(e ushr shift and 255)+(g ushr shift and 255))/8)
                        q[k]=(avg(24) shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
                    }
                    it.setPixels(q,0,w,0,0,w,h)
                }
            }.getOrNull()
            if(b==null) super.dispatchDraw(canvas) else canvas.drawBitmap(b,null,android.graphics.Rect(0,0,width,height),paint)
        }
    }
    private fun focusBorder(selected:Boolean):Drawable? {
        if(!selected) return null
        return object:Drawable() {
            private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
            override fun draw(c:Canvas) {
                val rect=RectF(bounds).apply { inset(dp(3).toFloat(),dp(3).toFloat()) }
                p.color=0xff10141c.toInt(); p.strokeWidth=dp(6).toFloat(); c.drawRoundRect(rect,dp(11).toFloat(),dp(11).toFloat(),p)
                p.color=Color.WHITE; p.strokeWidth=dp(2).toFloat(); c.drawRoundRect(rect,dp(11).toFloat(),dp(11).toFloat(),p)
            }
            override fun setAlpha(alpha:Int) { p.alpha=alpha }
            override fun setColorFilter(filter:ColorFilter?) { p.colorFilter=filter }
            override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
    }
    private var shelfKey: String? = null
    private var resumed = false
    private lateinit var weatherText: TextView
    private val weatherHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val weatherWorker=Executors.newSingleThreadExecutor()
    private var weatherTicket=0
    private var weatherBusy=false
    private val weatherRefresh=Runnable { refreshWeather() }
    private fun weatherCity()=prefs.getString("weather_city","İstanbul").orEmpty()
    private fun refreshWeather() {
        weatherHandler.removeCallbacks(weatherRefresh)
        if(!resumed || !::weatherText.isInitialized) return
        weatherHandler.postDelayed(weatherRefresh,30*60*1000L)
        if(weatherBusy) return
        val city=weatherCity()
        val savedLat=prefs.getString("weather_lat",null)?.toDoubleOrNull()
        val savedLon=prefs.getString("weather_lon",null)?.toDoubleOrNull()
        val ticket=++weatherTicket
        weatherBusy=true
        val old=prefs.getString("weather_display",null)
        val age=System.currentTimeMillis()-prefs.getLong("weather_time",0)
        weatherText.text=if(old!=null && prefs.getString("weather_cached_city","")==city && age in 0..3600000L) old else city+"  ·  —°"
        if(old!=null && prefs.getString("weather_cached_city","")==city && age in 0..900000L) { weatherBusy=false; return }
        weatherWorker.execute {
            val data=runCatching {
                val query=java.net.URLEncoder.encode(city,"UTF-8")
                fun json(url:String):JSONObject {
                    val c=URL(url).openConnection().apply { connectTimeout=6000; readTimeout=6000 }
                    return JSONObject(c.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() })
                }
                val location=if(savedLat!=null && savedLon!=null) JSONObject().put("latitude",savedLat).put("longitude",savedLon).put("name",city) else {
                    val geo=json("https://geocoding-api.open-meteo.com/v1/search?name="+query+"&count=10&language=tr&format=json").getJSONArray("results")
                    (0 until geo.length()).map { geo.getJSONObject(it) }.firstOrNull { it.optString("country_code")=="TR" } ?: geo.getJSONObject(0)
                }
                val lat=location.getDouble("latitude"); val lon=location.getDouble("longitude")
                val current=json("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,weather_code,is_day&timezone=auto").getJSONObject("current")
                val code=current.getInt("weather_code")
                val day=current.optInt("is_day",1)==1
                val icon=when(code) {
                    0 -> if(day) "☀" else "☾"
                    1,2 -> if(day) "⛅" else "☁"
                    3,45,48 -> "☁"
                    in 51..67,in 80..82 -> "☂"
                    in 71..77,85,86 -> "❄"
                    in 95..99 -> "ϟ"
                    else -> "☁"
                }
                icon+"  "+current.getDouble("temperature_2m").roundToInt()+"°  "+location.optString("name",city)
            }.getOrNull()
            weatherHandler.post {
                weatherBusy=false
                if(ticket!=weatherTicket || city!=weatherCity()) { if(resumed) refreshWeather(); return@post }
                if(data!=null) {
                    prefs.edit().putString("weather_display",data).putString("weather_cached_city",city).putLong("weather_time",System.currentTimeMillis()).apply()
                    if(::weatherText.isInitialized) weatherText.text=data
                }
            }
        }
    }
    private fun weatherSettings() {
        val (dialog,_,items)=newGlassDialog(t("Hava durumu • Bölge seçimi","Weather • Choose location"))
        dialog.setOnCancelListener { dialog.dismiss(); showSettings() }
        items.addView(text(t("Mevcut bölge: ","Current location: ")+weatherCity(),15f,Color.WHITE))
        val field=EditText(this).apply {
            setText(weatherCity()); setSingleLine(true); setTextColor(Color.WHITE); typeface=uiFont(false)
            hint=t("Şehir veya ilçe yazın","Enter a city or district"); setPadding(dp(12),dp(8),dp(12),dp(8))
            setSelectAllOnFocus(true)
        }
        items.addView(field)
        val resultBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun select(city:String,lat:Double,lon:Double) {
            prefs.edit().putString("weather_city",city).putString("weather_lat",lat.toString())
                .putString("weather_lon",lon.toString()).remove("weather_time").apply()
            weatherTicket++
            weatherText.text=city+"  ·  —°"
            refreshWeather()
            dialog.dismiss(); showSettings()
        }
        val search=menuRow(t("Bölge ara","Search location")) {
            val query=field.text.toString().trim().take(70)
            if(query.length<2) return@menuRow
            resultBox.removeAllViews()
            resultBox.addView(text(t("Aranıyor…","Searching…"),14f,Color.LTGRAY))
            weatherWorker.execute {
                val results=runCatching {
                    val q=java.net.URLEncoder.encode(query,"UTF-8")
                    val c=URL("https://geocoding-api.open-meteo.com/v1/search?name="+q+"&count=15&language=tr&format=json")
                        .openConnection().apply { connectTimeout=6000; readTimeout=6000 }
                    val arr=JSONObject(c.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("results")
                    if(arr==null) emptyList<JSONObject>() else (0 until arr.length()).map { arr.getJSONObject(it) }
                        .sortedBy { if(it.optString("country_code")=="TR") 0 else 1 }
                }
                weatherHandler.post {
                    if(!dialog.isShowing) return@post
                    resultBox.removeAllViews()
                    val locations=results.getOrDefault(emptyList())
                    if(locations.isEmpty()) resultBox.addView(text(if(results.isFailure) t("Bağlantı hatası. Tekrar deneyin.","Connection error. Try again.") else t("Bölge bulunamadı.","No locations found."),14f,Color.LTGRAY))
                    var first:View?=null
                    locations.forEach { place ->
                        val name=place.optString("name")
                        val region=listOf(place.optString("admin1"),place.optString("country")).filter { it.isNotBlank() && it!=name }.distinct().joinToString(", ")
                        val label=if(region.isBlank()) name else name+" · "+region
                        val row=menuRow(label) { select(name,place.getDouble("latitude"),place.getDouble("longitude")) }
                        resultBox.addView(row)
                        if(first==null) first=row
                    }
                    first?.requestFocus()
                }
            }
        }
        items.addView(search)
        items.addView(resultBox)
        items.addView(text(t("Hızlı seçim","Quick selection"),14f,Color.LTGRAY))
        listOf(Triple("İstanbul",41.01,28.95),Triple("Ankara",39.93,32.86),Triple("İzmir",38.42,27.14),
            Triple("Bursa",40.20,29.06),Triple("Antalya",36.90,30.71)).forEach { place ->
            items.addView(menuRow(place.first+(if(weatherCity()==place.first) "  ✓" else "")) { select(place.first,place.second,place.third) })
        }
        items.addView(text("Weather data: Open-Meteo.com · CC BY 4.0",12f,Color.LTGRAY))
        presentGlass(dialog); search.requestFocus()
    }
    private val tvInputs by lazy { getSystemService(android.media.tv.TvInputManager::class.java) }
    private var inputCallbackRegistered=false
    private val inputCallback=object:android.media.tv.TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId:String) { refreshInputs() }
        override fun onInputRemoved(inputId:String) { refreshInputs() }
        override fun onInputUpdated(inputId:String) { refreshInputs() }
        override fun onInputStateChanged(inputId:String,state:Int) { refreshInputs() }
    }
    private fun refreshInputs() {
        if(!resumed || !::rows.isInitialized) return
        val next=installed()
        if(next.map { Triple(it.id,it.title,it.subtitle) } != apps.map { Triple(it.id,it.title,it.subtitle) }) {
            apps=next
            if(movingId==null) refreshCards()
        }
    }
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
    private lateinit var heroArt: ImageView
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
        if(!prefs.getBoolean("preferred_layout_v1",false)) {
            order.clear()
            prefs.edit().putBoolean("preferred_layout_v1",true)
                .putBoolean("custom_app_order",false).putString("tile_order","").apply()
        }
        hidden = (prefs.getStringSet("hidden", emptySet()) ?: emptySet()).toMutableSet()
        light = prefs.getBoolean("light", false)
        english = prefs.getString("language", "tr") == "en"
        storeIcons = prefs.getBoolean("store_icons", true)
        largeIcons = false
        demo = false
        apps = installed()
        selectedId = savedInstanceState?.getString("selected") ?: prefs.getString("last_focused_tile",null)
        lastOpenedId=prefs.getString("last_opened_tile",null)
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
        refreshWeather()
        if(!inputCallbackRegistered) runCatching {
            tvInputs?.let { it.registerCallback(inputCallback,android.os.Handler(android.os.Looper.getMainLooper())); inputCallbackRegistered=true }
        }
        if (::rows.isInitialized) {
            val next = installed()
            if (next.map { it.id to it.title } != apps.map { it.id to it.title }) {
                apps = next
                refreshCards()
                fetchStoreIcons()
            }
        }
        if(returnedFromApp) {
            returnedFromApp=false
            val data=tiles()
            val target=when(prefs.getString("return_focus","opened")) {
                "first" -> data.firstOrNull()?.id
                "last" -> selectedId
                else -> lastOpenedId ?: selectedId
            }
            selectedId=data.firstOrNull { it.id==target }?.id ?: data.firstOrNull()?.id
            selectedId?.let { cardViews[it]?.requestFocus() }
        }
        if(::shelfPlayer.isInitialized && selectedId != null) { shelfKey=null; updateShelf(apps.firstOrNull { it.id==selectedId }) }
    }

    override fun onPause() {
        resumed=false
        returnedFromApp=true
        if(::prefs.isInitialized) prefs.edit().putString("last_focused_tile",selectedId).apply()
        if(expandedShelf && ::gridScroll.isInitialized) {
            expandedShelf=false
            shelf.isFocusable=false
            gridScroll.visibility=View.VISIBLE
        }
        weatherHandler.removeCallbacks(weatherRefresh)
        if(inputCallbackRegistered) {
            runCatching { tvInputs?.unregisterCallback(inputCallback) }
            inputCallbackRegistered=false
        }
        if(::shelfPlayer.isInitialized) shelfPlayer.stop()
        shelfKey=null
        super.onPause()
    }

    override fun onDestroy() {
        if(::shelfPlayer.isInitialized) shelfPlayer.destroy()
        worker.shutdownNow()
        weatherHandler.removeCallbacksAndMessages(null)
        weatherTicket++
        weatherWorker.shutdownNow()
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
        val sourceApps=result.values.filter { defaultRank(it)==6 || it.title.matches(Regex("(?i)HDMI\\s*[0-9]+")) }
        val sourceApp=sourceApps.firstOrNull { defaultRank(it)==6 }
        sourceApps.forEach { result.remove(it.id) }
        result["builtin:source"]=Tile("builtin:source","Source",t("TV girişlerini seç","Choose TV input"),0xff25344e.toInt(),"HDMI",sourceApp?.launch,sourceApp?.icon,sourceApp?.banner)
        return result.values.sortedBy { it.title.lowercase() }
    }

    private fun displayName(tile: Tile): String {
        val custom = prefs.getString("name_" + tile.id, null)
        if (!custom.isNullOrBlank()) return custom
        if(tile.id=="builtin:source") return "Source"
        if(tile.id=="com.tcl.tv") return t("Canlı TV","Live TV")
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
        if(requestCode==232) {
            apps=installed(); refreshCards(); fetchStoreIcons()
            return
        }
        if(requestCode==231) {
            homeSettings()
            return
        }
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

    private fun defaultRank(tile: Tile): Int {
        val id=tile.id.lowercase(java.util.Locale.ROOT)
        if(id=="builtin:source") return 6
        val name=java.text.Normalizer.normalize(tile.title.lowercase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"").replace('ı','i').trim()
            .replace(Regex("[_\\s-]+")," ")
        return when {
            id=="com.tcl.tv" || name in setOf("canli tv","live tv","tv","live television","television","televizyon","tv channels","tv kanallari","channels") ||
                id in setOf("com.android.tv","com.google.android.tv","com.tcl.tv","com.tcl.tvmanager") && name.contains("tv") -> 0
            id in setOf("com.netflix.ninja","com.netflix.mediaclient") || name=="netflix" -> 1
            id=="com.google.android.youtube.tv" || name=="youtube" -> 2
            id in setOf("com.amazon.amazonvideo.livingroom","com.amazon.avod.thirdpartyclient") || name in setOf("prime video","amazon prime video","amazon video","primevideo") -> 3
            name=="tod" || name.startsWith("tod ") || id in setOf("com.beonetv.tod","com.digiturk.tod.mobil","com.digiturk.tod","com.todtv.tod","com.digiturk.iq.mobil") -> 4
            id in setOf("com.spotify.tv.android","com.spotify.music") || name=="spotify" -> 5
            name in setOf("source","sources","input","inputs","tv inputs","girisler","kaynaklar","kaynak","giris") ||
                id in setOf("com.tcl.inputsource","com.tcl.tvinput") && !id.startsWith("hdmi:") -> 6
            id in setOf("com.android.tv.settings","com.android.settings","com.google.android.tv.settings") ||
                name in setOf("settings","setting","tv settings","system settings","android settings","ayarlar","tv ayarlari") -> 7
            id=="com.android.vending" || name in setOf("play store","google play store","google play magaza","google play magazasi") -> 8
            id=="com.disney.disneyplus" || name in setOf("disney","disney+","disney plus") -> 9
            id in setOf("com.wbd.stream","com.hbo.hbonow","com.hbo.hbomax") || name in setOf("hbo max","hbomax","max") -> 10
            id in setOf("com.android.chrome","com.android.browser","com.tcl.browser","com.tcl.tvbrowser","com.seraphic.openinet.pre") ||
                name in setOf("browser","web browser","internet browser","internet tarayicisi","tarayici","chrome","tv browser","browsehere","browse here") -> 11
            else -> 100
        }
    }

    private fun tiles(): List<Tile> {
        val ranks=moveOrder ?: if(prefs.getBoolean("custom_app_order",false)) order else null
        val available=apps.filter { !it.id.startsWith("hdmi:") && (it.id=="builtin:source" || showHidden || it.id !in hidden) }
        if(ranks==null) {
            val picked=mutableSetOf<String>()
            val primary=(0..11).mapNotNull { rank -> available.firstOrNull { defaultRank(it)==rank && picked.add(it.id) } }
            return primary+available.filterNot { it.id in picked }
        }
        val sorted=available.sortedBy {
            ranks.indexOf(it.id).let { rank -> if(rank < 0) Int.MAX_VALUE else rank }
        }.toMutableList()
        if("builtin:source" !in ranks) sorted.firstOrNull { it.id=="builtin:source" }?.let { sorted.remove(it); sorted.add(minOf(columns,sorted.size),it) }
        return sorted
    }

    private fun beginMove(tile: Tile) {
        val snapshot=runCatching { glassSource.frame()?.bitmap?.let { b -> Bitmap.createScaledBitmap(b,maxOf(1,b.width/2),maxOf(1,b.height/2),true) } }.getOrNull()
        moveBackdrop.setImageBitmap(snapshot)
        shelfPlayer.stop(); shelfKey=null
        movingId = tile.id
        moveOrder = tiles().map { it.id }.toMutableList()
        selectedId = tile.id
        refreshCards()
    }

    private fun finishMove(commit: Boolean) {
        if(commit) {
            val next = moveOrder.orEmpty()
            order = (next + order.filterNot { it in next }).toMutableList()
            prefs.edit().putBoolean("custom_app_order",true).apply()
            save()
        }
        movingId = null
        moveOrder = null
        moveBackdrop.setImageDrawable(null)
        shelfKey=null
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
            if(::settings.isInitialized && settings.hasFocus() && (event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode==KeyEvent.KEYCODE_ENTER)) {
                swallowKey=event.keyCode
                if(event.repeatCount==0) showSettings()
                return true
            }
            if(expandedShelf) {
                when(event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> shelfPlayer.next(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> shelfPlayer.next(1)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK -> setExpanded(false)
                    KeyEvent.KEYCODE_DPAD_UP -> { setExpanded(false); settings.requestFocus() }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0 && shelf.hasFocus()) selectedId?.let { id -> apps.firstOrNull { it.id==id }?.let { launchShelfItem(it) } }
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
        backdropImage = ImageView(this).apply { scaleType = ImageView.ScaleType.MATRIX; setBackgroundColor(0xff101823.toInt()); addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> fitArtwork() } }
        frame.addView(backdropImage, FrameLayout.LayoutParams(-1, -1))
        videoSurface = android.view.TextureView(this)
        frame.addView(videoSurface,FrameLayout.LayoutParams(-1,-1))
        backdropTint = View(this)
        frame.addView(backdropTint, FrameLayout.LayoutParams(-1, -1))
        moveBackdrop=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; setBackgroundColor(0x20000000); setColorFilter(0x18000000,PorterDuff.Mode.SRC_ATOP); visibility=View.GONE }
        frame.addView(moveBackdrop,FrameLayout.LayoutParams(-1,-1))
        glassSource=GlassBackdrop(backdropImage,videoSurface,backdropTint)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        shelfPlayer = TvShelf(this,videoSurface) { item, bitmap ->
            if(item != null) {
                heroTitle.text=item.title.ifBlank { apps.firstOrNull { it.id==selectedId }?.let { displayName(it) }.orEmpty() }
                heroSub.text=item.description
                if(item.channelLogo) {
                    heroArt.setImageBitmap(bitmap)
                    heroArt.visibility=if(bitmap!=null) View.VISIBLE else View.INVISIBLE
                    backdropImage.setImageDrawable(null)
                    backdropTint.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff26364d.toInt(),0xff151e2c.toInt(),0xff080b12.toInt()))
                } else {
                    heroArt.visibility=View.INVISIBLE
                    if(bitmap!=null) {
                        backdropImage.animate().cancel(); backdropImage.setImageBitmap(bitmap); fitArtwork()
                        backdropImage.alpha=.72f; backdropImage.animate().alpha(1f).setDuration(240).start()
                    }
                    backdropTint.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x08000000,0x22000000,0xdd080b12.toInt()))
                }
                glassSource.invalidate()
            }
        }
        val header = SoftFocusLayout { movingId!=null }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherText=text(weatherCity()+"  ·  —°",19f,Color.WHITE,true).apply {
            gravity=Gravity.CENTER_VERTICAL
            contentDescription=t("Hava durumu ve bölge • Open-Meteo","Weather and location • Open-Meteo")
            setPadding(dp(4),0,dp(12),0)
            maxLines=1
        }
        header.addView(weatherText,LinearLayout.LayoutParams(-2,-1))
        if(resumed) refreshWeather()
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
        settings = object : TextView(this) {
            private val gear = SettingsGear(Color.WHITE)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val side=minOf(width,height)*0.64f
                gear.setBounds(((width-side)/2).toInt(),((height-side)/2).toInt(),((width+side)/2).toInt(),((height+side)/2).toInt())
                gear.draw(canvas)
            }
        }.apply {
            contentDescription = t("Başlatıcı ayarları", "Launcher settings")
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            isClickable = true
            background = LiquidGlassDrawable(this,glassSource,dp(22).toFloat())
            setOnClickListener { showSettings() }
            setOnFocusChangeListener { v, focused ->
                if(focused && expandedShelf) { expandedShelf=false; gridScroll.visibility=View.VISIBLE; shelfPlayer.expand(false) }
                v.background = LiquidGlassDrawable(v,glassSource,dp(22).toFloat(),focused=focused)
                v.animate().scaleX(if(focused) 1.10f else 1f).scaleY(if(focused) 1.10f else 1f).setDuration(140).start()
            }
        }
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(36)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(36)))
        homeHeader=header

        shelf = SoftFocusLayout { movingId!=null }.apply {
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
        heroArt = ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            setPadding(dp(65),dp(32),dp(65),dp(32))
            visibility=View.INVISIBLE
        }
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
        val oldPositions=if(movingId!=null) cardViews.mapValues { (_,v) -> IntArray(2).also { v.getLocationOnScreen(it) } } else emptyMap()
        rows.removeAllViews()
        cardViews.clear()
        homeHeader.invalidate(); shelf.invalidate(); moveBackdrop.visibility=if(movingId!=null) View.VISIBLE else View.GONE
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
        if(oldPositions.isNotEmpty()) rows.post {
            for((id,v) in cardViews) {
                val old=oldPositions[id] ?: continue
                val loc=IntArray(2); v.getLocationOnScreen(loc)
                v.translationX=(old[0]-loc[0]).toFloat(); v.translationY=(old[1]-loc[1]).toFloat()
                v.animate().translationX(0f).translationY(0f).setDuration(190).start()
            }
        }
    }

    private fun createCard(tile: Tile, height: Int): View {
        val holder = SoftFocusLayout { movingId!=null && movingId!=tile.id }.apply {
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
        val systemSettings = tile.id in setOf("com.android.tv.settings","com.android.settings","com.google.android.tv.settings") ||
            tile.title.lowercase() in setOf("settings","ayarlar","tv settings","tv ayarları")
        if(tile.id=="builtin:source") {
            panel.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xff45617c.toInt(),0xff182735.toInt())).apply { cornerRadius=dp(13).toFloat() }
            panel.addView(ImageView(this).apply { setImageDrawable(SourceGlyph("source")); scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(14),dp(9),dp(14),dp(9)) },FrameLayout.LayoutParams(-1,-1))
        } else if(systemSettings && !asset(tile,"custom").isFile) {
            panel.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xff9da3ae.toInt(),0xff555c67.toInt())).apply { cornerRadius=dp(13).toFloat() }
            val image=ImageView(this).apply { setImageDrawable(SettingsGear(Color.WHITE)); scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(10),dp(8),dp(10),dp(8)) }
            panel.addView(image,FrameLayout.LayoutParams(-1,-1))
        } else if (downloaded != null || tile.banner != null || tile.icon != null) {
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
        holder.setOnClickListener { if(movingId != null) finishMove(true) else { holder.animate().scaleX(1.02f).scaleY(1.02f).setDuration(75).withEndAction { if(holder.hasFocus()) holder.animate().scaleX(1.10f).scaleY(1.10f).setDuration(120).start(); launch(tile) }.start() } }
        holder.setOnLongClickListener { if(movingId == null) edit(tile); true }
        holder.setOnFocusChangeListener { view, focus ->
            val lifted=if(movingId!=null) movingId==tile.id else focus
            val scale=if(lifted) (if(movingId!=null) 1.07f else 1.10f) else 1f
            view.animate().cancel(); view.animate().scaleX(scale).scaleY(scale).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(210).start()
            view.translationZ = if(lifted) dp(10).toFloat() else 0f
            panel.elevation = if(lifted) dp(8).toFloat() else dp(3).toFloat()
            border.background = focusBorder(lifted)
            label.setTextColor(if(lifted) Color.WHITE else Color.TRANSPARENT)
            if (focus) { selectedId = tile.id; updateShelf(tile) }
        }
        return holder
    }

    private fun fitArtwork() {
        if(!::backdropImage.isInitialized) return
        val image=backdropImage.drawable ?: return
        val w=backdropImage.width.toFloat(); val h=backdropImage.height.toFloat()
        val iw=image.intrinsicWidth.toFloat(); val ih=image.intrinsicHeight.toFloat()
        if(w<=0 || h<=0 || iw<=0 || ih<=0) return
        val areaW=if(expandedShelf) w else w*.76f
        val areaH=if(expandedShelf) h else h*.76f
        val factor=minOf(areaW/iw,areaH/ih)
        backdropImage.imageMatrix=Matrix().apply { setScale(factor,factor); postTranslate(if(expandedShelf) (w-iw*factor)/2 else w-iw*factor,(areaH-ih*factor)/2) }
    }

    private fun setExpanded(value: Boolean) {
        expandedShelf=value
        fitArtwork()
        shelf.isFocusable=value
        if(value) shelf.requestFocus()
        gridScroll.visibility=if(value) View.GONE else View.VISIBLE
        shelfPlayer.expand(value)
        if(!value) selectedId?.let { cardViews[it]?.requestFocus() }
    }

    private fun updateShelf(tile: Tile?) {
        if(movingId!=null) return
        val newKey=tile?.id?.takeIf { id -> tiles().take(columns).any { it.id==id } && !id.startsWith("hdmi:") && !id.startsWith("builtin:") }
        if(shelfKey==newKey && newKey!=null && shelfPlayer.available) return
        shelfKey=newKey
        if(::shelfPlayer.isInitialized) shelfPlayer.select(if(resumed && movingId==null) newKey else null,tile?.let { defaultRank(it)==0 }==true)
        val color = tile?.color ?: 0xff25466c.toInt()
        shelf.background = rounded(Color.TRANSPARENT, 0)
        val customBg = tile?.let { asset(it, "backdrop") }?.takeIf { it.isFile }
        backdropImage.setImageDrawable(customBg?.let { Drawable.createFromPath(it.absolutePath) }); fitArtwork()
        backdropTint.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            if (customBg != null) intArrayOf(0x22000000, 0x55000000, 0xee080b12.toInt())
            else intArrayOf(blend(color, if(light) Color.WHITE else Color.BLACK, if(light) .2f else .28f), 0xff171c27.toInt(), 0xff080b12.toInt()))
        heroTitle.text = tile?.let { displayName(it) } ?: t("Ana Ekran", "Home")
        heroSub.text = if(tile == null) t("Uygulama seç", "Choose an app") else tile.subtitle
        heroArt.setImageDrawable(null)
        heroArt.visibility = View.INVISIBLE
        glassSource.invalidate()
    }

    private class SourceGlyph(private val kind:String):Drawable() {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeWidth=2.5f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        override fun draw(c:Canvas) {
            val b=bounds; val size=minOf(b.width(),b.height()).toFloat()
            c.save(); c.translate(b.exactCenterX()-size/2,b.exactCenterY()-size/2); c.scale(size/64,size/64)
            if(kind=="hdmi") {
                val path=Path().apply { moveTo(12f,18f); lineTo(52f,18f); lineTo(52f,36f); lineTo(43f,45f); lineTo(21f,45f); lineTo(12f,36f); close() }; c.drawPath(path,p)
                for(x in 20..44 step 6) c.drawLine(x.toFloat(),25f,x.toFloat(),31f,p)
            } else {
                c.drawRoundRect(8f,12f,56f,48f,4f,4f,p)
                if(kind=="tv") { c.drawLine(24f,55f,40f,55f,p); c.drawLine(32f,48f,32f,55f,p) }
                else { c.drawLine(2f,30f,38f,30f,p); c.drawLine(29f,21f,38f,30f,p); c.drawLine(29f,39f,38f,30f,p) }
            }
            c.restore()
        }
        override fun setAlpha(a:Int) { p.alpha=a }
        override fun setColorFilter(f:ColorFilter?) { p.colorFilter=f }
        override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    private fun sourceMenu(tile:Tile) {
        val inputs=runCatching { tvInputs?.tvInputList.orEmpty().filter { it.isPassthroughInput }.sortedBy { it.loadLabel(this).toString() } }.getOrDefault(emptyList())
        val (dialog,body,items)=newGlassDialog(t("Kaynaklar","Sources"))
        items.addView(text(t("TV girişini seç","Choose a TV input"),14f,0xffc3d0df.toInt()).apply { setPadding(dp(10),0,0,dp(12)) })
        data class Entry(val title:String,val subtitle:String,val kind:String,val action:()->Unit)
        val entries=mutableListOf<Entry>()
        entries.add(Entry(t("Canlı TV","Live TV"),t("Anten / Uydu","Antenna / Satellite"),"tv") { if(LiveTuner.open(this)) apps.firstOrNull { it.id=="com.tcl.tv" }?.let { rememberOpened(it) } else Toast.makeText(this,t("Kanal bilgisi alınamadı","Channel information unavailable"),Toast.LENGTH_SHORT).show() })
        for(input in inputs) {
            val name=runCatching { input.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: input.loadLabel(this).toString() }.getOrDefault(input.id)
            val state=runCatching { tvInputs?.getInputState(input.id) }.getOrNull()
            val status=when(state) {
                android.media.tv.TvInputManager.INPUT_STATE_CONNECTED -> t("● Bağlı","● Connected")
                android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> t("Beklemede","Standby")
                else -> t("Bağlı değil","Disconnected")
            }
            entries.add(Entry(name,status,if(input.type==android.media.tv.TvInputInfo.TYPE_HDMI) "hdmi" else "source") {
                val uri=android.media.tv.TvContract.buildChannelUriForPassthroughInput(input.id)
                val intent=Intent(Intent.ACTION_VIEW,uri)
                val launched=runCatching { startActivity(Intent(intent).setClassName("com.tcl.tv","com.tcl.tv.TVActivity")); true }.getOrElse { runCatching { startActivity(intent); true }.getOrDefault(false) }
                if(launched) rememberOpened(tile) else Toast.makeText(this,t("Giriş açılamadı","Input unavailable"),Toast.LENGTH_SHORT).show()
            })
        }
        entries.add(Entry(t("TV kaynak seçici","TV source selector"),t("Sistem menüsü","System menu"),"source") {
            if(tile.launch==null || !runCatching { startActivity(tile.launch); true }.getOrDefault(false)) Toast.makeText(this,t("Kumandadaki Source tuşunu kullan","Use the remote Source button"),Toast.LENGTH_SHORT).show()
        })
        var first:View?=null
        for(group in entries.chunked(2)) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; clipChildren=false; clipToPadding=false }
            for(entry in group) {
                val card=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; isFocusable=true; isClickable=true
                    setPadding(dp(7),dp(9),dp(7),dp(9)); background=LiquidGlassDrawable(this,glassSource,dp(18).toFloat(),dark=true)
                    addView(ImageView(this@MainActivity).apply { setImageDrawable(SourceGlyph(entry.kind)) },LinearLayout.LayoutParams(dp(44),dp(44)))
                    addView(text(entry.title,16f,Color.WHITE,true).apply { gravity=Gravity.CENTER; maxLines=2 })
                    addView(text(entry.subtitle,11f,0xffc3d0df.toInt()).apply { gravity=Gravity.CENTER; maxLines=2 })
                    setOnFocusChangeListener { v,f -> v.background=LiquidGlassDrawable(v,glassSource,dp(18).toFloat(),focused=f,dark=true); v.foreground=focusBorder(f); v.animate().scaleX(if(f)1.04f else 1f).scaleY(if(f)1.04f else 1f).setDuration(180).start() }
                    setOnClickListener { dialog.dismiss(); entry.action() }
                }
                row.addView(card,LinearLayout.LayoutParams(0,dp(108),1f).apply { setMargins(dp(5),dp(5),dp(5),dp(5)) })
                if(first==null) first=card
            }
            if(group.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            items.addView(row)
        }
        presentGlass(dialog)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.56f).roundToInt(),(resources.displayMetrics.heightPixels*.85f).roundToInt())
        first?.requestFocus()
    }

    private fun rememberOpened(tile:Tile) { lastOpenedId=tile.id; prefs.edit().putString("last_opened_tile",tile.id).apply() }

    private fun launchShelfItem(tile: Tile) {
        val link=shelfPlayer.currentLink(tile.id)
        val netflix=tile.id in setOf("com.netflix.ninja","com.netflix.mediaclient")
        val candidates=mutableListOf<Intent>()
        if(link!=null) {
            if(netflix && link.scheme=="https" && link.host in setOf("netflix.com","www.netflix.com")) {
                val parts=link.pathSegments
                val id=parts.zipWithNext().firstOrNull { (kind,value) -> kind in setOf("title","watch") && value.matches(Regex("[0-9]+")) }?.second
                if(id!=null) {
                    for(scheme in listOf("https","nflx")) {
                        candidates.add(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("$scheme://www.netflix.com/title/$id"))
                            .putExtra("source","30").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                }
            }
            runCatching {
                val parsed=Intent.parseUri(link.toString(),Intent.URI_INTENT_SCHEME or Intent.URI_ANDROID_APP_SCHEME)
                if(parsed.component?.packageName?.let { it!=tile.id }!=true &&
                    parsed.`package`?.let { it!=tile.id }!=true) {
                    parsed.selector=null
                    parsed.clipData=null
                    parsed.flags=0
                    if(netflix) parsed.putExtra("source","30")
                    candidates.add(parsed)
                }
            }
        }
        for(candidate in candidates) {
            candidate.setPackage(tile.id)
            val result=runCatching { startActivity(candidate) }
            android.util.Log.i("TvShelf","OPEN pkg="+tile.id+" scheme="+(candidate.data?.scheme ?: "none")+" accepted="+result.isSuccess+
                (result.exceptionOrNull()?.let { " error="+it.javaClass.simpleName } ?: ""))
            if(result.isSuccess) { rememberOpened(tile); return }
        }
        android.util.Log.i("TvShelf","OPEN pkg="+tile.id+" fallback=app link="+(link!=null))
        Toast.makeText(this,t("İçerik bağlantısı açılamadı; uygulama açılıyor.","Title link unavailable; opening app."),Toast.LENGTH_SHORT).show()
        launch(tile)
    }

    private fun launch(tile: Tile) {
        if(tile.id=="builtin:source") { sourceMenu(tile); return }
        if(tile.id=="com.tcl.tv" && LiveTuner.open(this)) { rememberOpened(tile); return }
        val intent = tile.launch
        if (intent == null) {
            Toast.makeText(this, t("Bu giriş açılamıyor.","This input cannot be opened."), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(intent); rememberOpened(tile) }.onFailure {
            Toast.makeText(this, t("Uygulama açılamadı", "Could not open application"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit(tile: Tile) {
        val actions = mutableListOf(t("Taşı","Move"),
            if(tile.id in hidden) t("Göster","Show") else t("Gizle","Hide"),
            t("Uygulama adını değiştir","Rename application"),
            t("Kendi ikonunu seç","Choose custom icon"),
            t("Arka plan görselini seç","Choose background image"),
            t("Özel görselleri kaldır","Remove custom artwork"),
            t("Orijinal adı geri yükle","Restore original name"))
        if(canUninstall(tile)) actions.add(t("Uygulamayı kaldır","Uninstall application"))
        glassMenu(displayName(tile), actions.toTypedArray()) { which ->
            when(which) {
                0 -> { beginMove(tile); return@glassMenu }
                1 -> if(tile.id in hidden) hidden.remove(tile.id) else hidden.add(tile.id)
                2 -> { rename(tile); return@glassMenu }
                3 -> { chooseArt(tile,false); return@glassMenu }
                4 -> { chooseArt(tile,true); return@glassMenu }
                5 -> { asset(tile,"custom").delete(); asset(tile,"backdrop").delete() }
                6 -> prefs.edit().remove("name_" + tile.id).apply()
                7 -> { uninstall(tile); return@glassMenu }
            }
            save()
            refreshCards()
        }
    }

    private fun canUninstall(tile:Tile):Boolean {
        if(tile.id.startsWith("hdmi:") || tile.id.startsWith("builtin:") || tile.id==packageName) return false
        return runCatching {
            val info=packageManager.getApplicationInfo(tile.id,0)
            (info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0
        }.getOrDefault(false)
    }

    private fun uninstall(tile:Tile) {
        if(!canUninstall(tile)) return
        val intent=Intent(Intent.ACTION_DELETE,android.net.Uri.fromParts("package",tile.id,null))
            .putExtra(Intent.EXTRA_RETURN_RESULT,true)
        runCatching { startActivityForResult(intent,232) }.onFailure {
            Toast.makeText(this,t("Uygulama kaldırma ekranı açılamadı.","Could not open the uninstall screen."),Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings() {
        if(expandedShelf) { expandedShelf=false; shelf.isFocusable=false; gridScroll.visibility=View.VISIBLE; shelfPlayer.expand(false) }
        settings.requestFocus()
        glassMenu(t("Ayarlar","Settings"),arrayOf(t("Genel  ›","General  ›"),t("Görünüm  ›","Appearance  ›"),
            t("Uygulamalar  ›","Applications  ›"),t("Ana ekran  ›","Home screen  ›"),
            t("Top Shelf durumu  ›","Top Shelf status  ›"),t("TV ayarları  ›","TV settings  ›"),t("Hava durumu • ","Weather • ")+weatherCity()+"  ›")) { which ->
            when(which) {
                0 -> generalSettings()
                1 -> appearanceSettings()
                2 -> applicationSettings()
                3 -> homeSettings()
                4 -> shelfDiagnostics()
                5 -> openTvSettings()
                6 -> weatherSettings()
            }
        }
    }
    private fun generalSettings() {
        glassMenu(t("Genel","General"),arrayOf(t("Dil","Language"),t("TV açılışında Canlı TV: ","Live TV on power on: ")+if(prefs.getBoolean("live_on_power",true)) t("Açık","On") else t("Kapalı","Off")), onBack={showSettings()}) { i ->
            if(i==0) languageMenu() else { prefs.edit().putBoolean("live_on_power",!prefs.getBoolean("live_on_power",true)).apply(); generalSettings() }
        }
    }
    private fun appearanceSettings() {
        glassMenu(t("Görünüm","Appearance"),arrayOf(if(light) t("Koyu","Dark") else t("Açık","Light"),t("Görsel kalitesi","Image quality")+" · "+prefs.getString("art_quality","auto"),t("İçerik değişim süresi","Slide interval")+" · "+prefs.getInt("shelf_seconds",9)+" s"),onBack={showSettings()}) { i ->
            when(i) {
                0 -> { light=!light; save(); buildHome(); appearanceSettings() }
                1 -> glassMenu(t("Görsel kalitesi","Image quality"),arrayOf(t("Otomatik","Auto"),"Full HD","4K"),onBack={appearanceSettings()}) { n -> prefs.edit().putString("art_quality",listOf("auto","fhd","4k")[n]).apply(); shelfKey=null; updateShelf(apps.firstOrNull { it.id==selectedId }); appearanceSettings() }
                2 -> glassMenu(t("İçerik değişim süresi","Slide interval"),arrayOf("6 s","9 s","12 s","20 s"),onBack={appearanceSettings()}) { n -> prefs.edit().putInt("shelf_seconds",listOf(6,9,12,20)[n]).apply(); appearanceSettings() }
            }
        }
    }
    private fun applicationSettings() {
        glassMenu(t("Uygulamalar","Applications"),arrayOf(
            if(showHidden) t("Gizli uygulamaları gizle","Hide hidden apps") else t("Gizli uygulamaları göster","Show hidden apps"),
            if(storeIcons) t("Apple TV ikonlarını kapat","Disable Apple TV icons") else t("Apple TV ikonlarını aç","Enable Apple TV icons"),
            t("Uygulamaları yenile","Refresh apps"),t("Varsayılan sıralamayı geri yükle","Restore default layout"),t("Netflix katalog ayarları","Netflix catalog settings")),onBack={showSettings()}) { choice ->
            when(choice) {
                0 -> { showHidden=!showHidden; refreshCards() }
                1 -> { storeIcons=!storeIcons; save(); refreshCards(); fetchStoreIcons() }
                2 -> { apps=installed(); refreshCards(); fetchStoreIcons() }
                3 -> { order.clear(); prefs.edit().putBoolean("custom_app_order",false).apply(); save(); refreshCards() }
                4 -> { netflixSettings(); return@glassMenu }
            }
            applicationSettings()
        }
    }
    private fun netflixSettings() {
        val (dialog,_,items)=newGlassDialog(t("Netflix katalog ayarları","Netflix catalog settings"))
        val explanation=text(t("Netflix Türkiye önerileri otomatik olarak JustWatch üzerinden alınır. API anahtarı gerekmez. JustWatch yanıt vermezse isteğe bağlı TMDB v3 anahtarı kullanılır. Katalog önerileri kişisel izleme geçmişi değildir.","Netflix Turkey recommendations use JustWatch automatically, without an API key. An optional TMDB v3 key is used if JustWatch returns no data. Catalogue recommendations are not your viewing history."),16f,Color.WHITE,false)
        items.addView(explanation)
        val field=EditText(this).apply {
            hint="TMDB API key (v3)"
            setText(prefs.getString("tmdb_api_key",""))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xffbbbbbb.toInt())
            background=glass(false,15)
        }
        items.addView(field,LinearLayout.LayoutParams(-1,-2))
        items.addView(menuRow(t("Kaydet","Save")) {
            val key=field.text.toString().trim()
            if(key.isEmpty()) prefs.edit().remove("tmdb_api_key").apply()
            else prefs.edit().putString("tmdb_api_key",key).apply()
            dialog.dismiss(); shelfKey=null; updateShelf(apps.firstOrNull { it.id==selectedId }); applicationSettings()
        })
        items.addView(menuRow(t("Anahtarı kaldır","Remove key")) {
            prefs.edit().remove("tmdb_api_key").apply()
            dialog.dismiss(); shelfKey=null; updateShelf(apps.firstOrNull { it.id==selectedId }); applicationSettings()
        })
        items.addView(text("This product uses the TMDB API but is not endorsed or certified by TMDB. Watch availability: JustWatch.",13f,Color.WHITE,false))
        dialog.setOnCancelListener { dialog.dismiss(); applicationSettings() }
        presentGlass(dialog)
    }
    private fun homeSettings() {
        val home=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current=packageManager.resolveActivity(home,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val resolved=current?.activityInfo?.packageName.orEmpty()
        val isDefault=resolved==packageName
        val roles=if(android.os.Build.VERSION.SDK_INT>=29) getSystemService(android.app.role.RoleManager::class.java) else null
        val roleHeld=if(android.os.Build.VERSION.SDK_INT>=29) runCatching { roles?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)==true }.getOrDefault(false) else false
        val helper=runCatching {
            getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo.serviceInfo.packageName==packageName }==true
        }.getOrDefault(false)
        val status=if(isDefault) "TVOS Launcher" else resolved.ifBlank { "—" }
        glassMenu(t("Ana ekran","Home screen"),arrayOf(
            t("Varsayılan: ","Default: ")+status,
            "HOME role: "+if(roleHeld) t("verildi","granted") else t("verilmedi","not granted"),
            t("Varsayılan başlatıcı seç","Choose default launcher"),
            t("Yardımcı: ","Helper: ")+when { HomeRedirectService.connected -> t("bağlı","connected"); helper -> t("etkin, bağlantı yok","enabled, not connected"); else -> t("kapalı","disabled") },
            t("Erişilebilirlik / ADB kurulum rehberi","Accessibility / ADB setup guide"),
            "Android "+android.os.Build.VERSION.RELEASE+" • SDK "+android.os.Build.VERSION.SDK_INT,
            t("Home dönüş odağı","Home return focus")),
            onBack={showSettings()}) { choice ->
            when(choice) {
                2 -> {
                    val launched=runCatching {
                        if(android.os.Build.VERSION.SDK_INT>=29 && roles!=null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !roleHeld) {
                            startActivityForResult(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME),231)
                            true
                        } else false
                    }.getOrDefault(false)
                    if(!launched) {
                        homeSettings()
                        openSystemSettings(android.provider.Settings.ACTION_HOME_SETTINGS)
                    }
                }
                4 -> {
                    accessibilitySettingsGuide()
                }
                6 -> glassMenu(t("Home dönüş odağı","Home return focus"),arrayOf(t("İlk kart","First card"),t("Son açılan uygulama","Last opened app"),t("Son odaklanan kart","Last focused card")),onBack={homeSettings()}) { n -> prefs.edit().putString("return_focus",listOf("first","opened","last")[n]).apply(); homeSettings() }
                else -> homeSettings()
            }
        }
    }
    private fun accessibilitySettingsGuide() {
        glassMenu(t("Erişilebilirlik","Accessibility"),arrayOf(
            t("Sistem ayarını aç","Open system settings"),
            t("ADB ile etkinleştirme komutları","ADB setup commands"),
            t("Servis durumu (ADB)","Service status (ADB)")),onBack={homeSettings()}) { choice ->
            when(choice) {
                0 -> if(!openSystemSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) accessibilitySettingsGuide()
                1 -> android.app.AlertDialog.Builder(this).setTitle(t("ADB kurulum","ADB setup")).setMessage(
                    "adb shell am start -n com.tvos.launcher/.MainActivity\n\nadb shell appops set com.tvos.launcher APP_AUTO_START allow\n\nadb shell settings put secure enabled_accessibility_services com.tvos.launcher/com.tvos.launcher.HomeRedirectService\n\nadb shell settings put secure accessibility_enabled 1\n\n"+
                    t("Başka erişilebilirlik servisleri varsa mevcut listeyi koruyarak ekleyin.","Preserve any other enabled accessibility services."))
                    .setPositiveButton("OK") { _,_ -> accessibilitySettingsGuide() }.show()
                else -> android.app.AlertDialog.Builder(this).setTitle("ADB").setMessage("adb shell dumpsys accessibility\n\nadb shell cmd appops get com.tvos.launcher")
                    .setPositiveButton("OK") { _,_ -> accessibilitySettingsGuide() }.show()
            }
        }
    }

    private fun openSystemSettings(action:String):Boolean {
        val intent=Intent(action)
        val candidates=packageManager.queryIntentActivities(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val chosen=candidates.firstOrNull {
            val a=it.activityInfo
            a.packageName in setOf("com.android.tv.settings","com.android.settings","com.google.android.tv.settings","com.tcl.settings") ||
                a.packageName.contains("settings",true)
        }
        if(chosen!=null) intent.setClassName(chosen.activityInfo.packageName,chosen.activityInfo.name)
        else {
            val resolved=intent.resolveActivity(packageManager)
            if(resolved==null || resolved.packageName in setOf("com.google.android.apps.tv.launcherx","com.google.android.tvlauncher")) {
                Toast.makeText(this,t("Bu sistem ayarı TV yazılımında doğrudan açılamıyor.","This system setting is not directly available on this TV."),Toast.LENGTH_LONG).show()
                return false
            }
        }
        return runCatching { startActivity(intent); true }.getOrElse {
            Toast.makeText(this,t("Sistem ayarı açılamadı.","Could not open system settings."),Toast.LENGTH_LONG).show()
            false
        }
    }
    private fun openTvSettings() {
        glassMenu(t("TV ayarları","TV settings"),arrayOf(t("Cihaz ayarlarını aç","Open device settings"),
            t("Ağ ve internet","Network and internet"),t("Erişilebilirlik","Accessibility"),t("Cihaz bilgileri","Device information")),onBack={showSettings()}) { choice ->
            if(choice==2) { accessibilitySettingsGuide(); return@glassMenu }
            openTvSettings()
            openSystemSettings(when(choice) {
                1 -> android.provider.Settings.ACTION_WIFI_SETTINGS
                2 -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                3 -> android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
                else -> android.provider.Settings.ACTION_SETTINGS
            })
        }
    }
    private fun shelfDiagnostics() {
        val granted=checkSelfPermission("android.permission.READ_TV_LISTINGS")==android.content.pm.PackageManager.PERMISSION_GRANTED
        val permission=t("TV veri erişimi: ","TV data access: ")+if(granted) t("İzin verildi","Granted") else t("İzin gerekli","Permission required")
        val entries=tiles().take(columns).map { tile ->
            displayName(tile)+"  ·  "+if(tile.id.startsWith("hdmi:")) tile.subtitle else shelfPlayer.status(tile.id)
        }.ifEmpty { listOf(t("Uygulama yok","No apps")) }
        glassMenu(t("Top Shelf • IMG: görsel / VID: video","Top Shelf • IMG: image / VID: video"),(listOf(permission)+entries).toTypedArray(),onBack={showSettings()}) { choice ->
            if(choice==0 && !granted) {
                runCatching { requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"),230) }
                android.app.AlertDialog.Builder(this).setTitle(t("TV veri erişimi","TV data access"))
                    .setMessage(t("TV izin ekranı açılmazsa bilgisayarda şu komutu çalıştırın:","If the permission screen is unavailable, run this command on your computer:")+"\n\nadb shell pm grant com.tvos.launcher android.permission.READ_TV_LISTINGS")
                    .setPositiveButton("OK") { _,_ -> shelfDiagnostics() }.show()
            } else shelfDiagnostics()
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

    private fun glassMenu(title: String, actions: Array<String>, onBack: (() -> Unit)? = null, action: (Int) -> Unit) {
        val (dialog, _, items) = newGlassDialog(title)
        if(onBack!=null) dialog.setOnCancelListener { dialog.dismiss(); onBack() }
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
        ),onBack={generalSettings()}) { choice ->
            english = choice == 1
            save()
            apps = installed()
            buildHome()
            generalSettings()
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

    private class SettingsGear(private val tintColor:Int):Drawable() {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas) {
            val w=bounds.width().toFloat(); val h=bounds.height().toFloat()
            if(w<=0 || h<=0) return
            val size=minOf(w,h)
            canvas.save()
            canvas.translate(bounds.exactCenterX(),bounds.exactCenterY())
            p.color=tintColor
            val gear=Path().apply { fillType=Path.FillType.EVEN_ODD }
            val teeth=14
            for(i in 0 until teeth*4) {
                val ang=2.0*Math.PI*i/(teeth*4)-Math.PI/2
                val r=size*(if(i%4==1 || i%4==2) .48f else .40f)
                val x=(kotlin.math.cos(ang)*r).toFloat()
                val y=(kotlin.math.sin(ang)*r).toFloat()
                if(i==0) gear.moveTo(x,y) else gear.lineTo(x,y)
            }
            gear.close()
            gear.addCircle(0f,0f,size*.23f,Path.Direction.CW)
            canvas.drawPath(gear,p)
            canvas.drawCircle(0f,0f,size*.13f,p)
            canvas.restore()
        }
        override fun setAlpha(alpha:Int) { p.alpha=alpha }
        override fun setColorFilter(filter:ColorFilter?) { p.colorFilter=filter }
        @Suppress("DEPRECATION")
        override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()=96
        override fun getIntrinsicHeight()=96
    }
}
