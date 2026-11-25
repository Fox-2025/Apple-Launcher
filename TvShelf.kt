package com.tvos.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.net.URL
import java.util.concurrent.Executors

internal class TvShelf(private val context: Context, private val texture: TextureView,
    private val onItem: (Item?, Bitmap?) -> Unit) {
    data class Item(val title: String, val description: String, val image: Uri?, val video: Uri?,
        val channelLogo:Boolean=false, val fallbackLogo:Uri?=null, val catalogLogo:Uri?=null, val link:Uri?=null)
    private val worker = Executors.newSingleThreadExecutor()
    private val liveWorker = Executors.newSingleThreadExecutor()
    private val channelListener:()->Unit = { if(active && selectedPackage=="com.tcl.tv") select("com.tcl.tv",true) }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private val nativeCache=mutableMapOf<String,Pair<Long,List<Item>>>()
    private val catalogueByProvider=mutableMapOf<String,Pair<Long,List<Item>>>()
    private var selectedPackage: String? = null
    private val statuses = mutableMapOf<String,String>()
    fun status(pkg:String):String = statuses[pkg] ?: "—"
    private fun record(value:String) { selectedPackage?.let { statuses[it]=value } }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var video: Uri? = null
    private var items = emptyList<Item>()
    private var index = 0
    private var active = false
    private var expanded = false
    private var ready = false
    private var vw = 0
    private var vh = 0
    private var previewBounds:android.graphics.Rect?=null
    fun setPreviewBounds(bounds:android.graphics.Rect) {
        previewBounds=android.graphics.Rect(bounds)
        texture.clipBounds=bounds
        fit()
    }
    var available = false
        private set

    init {
        ChannelLogoService.addListener(channelListener)
        texture.alpha = 0f
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                startVideo()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                return true
            }
        }
        if (texture.isAvailable) texture.surfaceTexture?.let { surface = Surface(it) }
    }

    private fun uri(value: String?): Uri? = value?.takeIf { it.isNotBlank() }?.let {
        Uri.parse(it).takeIf { u -> u.scheme in setOf("https", "content", "android.resource") }
    }

    private fun query(pkg: String): List<Item> {
        val result = linkedSetOf<Item>()
        for (path in listOf("watch_next_program", "preview_program")) {
            runCatching {
                context.contentResolver.query(Uri.parse("content://android.media.tv/" + path),
                    null, null, null, null)?.use { c ->
                    fun value(column: String): String? {
                        val i = c.getColumnIndex(column)
                        return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                    }
                    var matching = 0
                    var hidden = 0
                    var usable = 0
                    var links = 0
                    val origins = linkedSetOf<String>()
                    while (c.moveToNext()) {
                        if (value("package_name") != pkg) continue
                        matching++
                        if (value("browsable") == "0") { hidden++; continue }
                        val art = uri(value("poster_art_uri")) ?: uri(value("thumbnail_uri"))
                        val clip = uri(value("preview_video_uri"))
                        if (art != null || clip != null) {
                            usable++
                            listOfNotNull(art,clip).forEach { source ->
                                origins.add((source.scheme ?: "?") + "://" + (source.host ?: "?"))
                            }
                            val link=value("intent_uri")?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                            if(link!=null) links++
                            val description=value("short_description").orEmpty()
                            val resume=path=="watch_next_program" && value("watch_next_type")=="0"
                            val en=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getString("language","tr")=="en"
                            if (result.size < 16) result.add(Item(value("title").orEmpty(), if(resume) (if(en) "Continue watching" else "İzlemeye devam et")+" · "+description else description, art, clip,link=link))
                        }
                    }
                    android.util.Log.i("TvShelf", "SOURCE pkg=$pkg table=$path matches=$matching hidden=$hidden usable=$usable links=$links origins=${origins.take(8).joinToString(",")}")
                } ?: android.util.Log.i("TvShelf", "SOURCE pkg=$pkg table=$path cursor=null")
            }.onFailure { android.util.Log.w("TvShelf", "SOURCE pkg=$pkg table=$path error=${it.javaClass.simpleName}") }
        }
        return result.distinctBy { it.title to it.link }
    }

    private fun normalizeChannel(value:String):String = java.text.Normalizer.normalize(
        value.lowercase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").replace('ı','i').trim()
        .replace(Regex("(?i)(?:\\s|[-_])+(?:fhd|uhd|hd|sd|4k)$"),"")
        .replace(Regex("[^a-z0-9+]"),"")

    private data class ChannelLogo(val name:String,val uri:Uri,val online:Uri?=null)
    private val channelCatalog by lazy {
        val entries=linkedMapOf<String,ChannelLogo>()
        for(file in listOf("user_channel_logos.json","turkish_channel_logos.json")) runCatching {
            val array=org.json.JSONArray(context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() })
            for(i in 0 until array.length()) {
                val row=array.getJSONObject(i)
                val name=row.getString("name")
                val online=uri(row.optString("url"))
                val asset=row.optString("asset").takeIf { it.matches(Regex("channel_logos/[0-9]{4}\\.png")) }
                val image=asset?.let { Uri.parse("asset:///"+it) } ?: online ?: continue
                val aliases=row.optJSONArray("aliases")
                val names=mutableListOf(name)
                if(aliases!=null) for(j in 0 until aliases.length()) names.add(aliases.optString(j))
                for(alias in names) {
                    val key=normalizeChannel(alias)
                    if(key.length>=2) entries.putIfAbsent(key,ChannelLogo(name,image,online))
                }
            }
        }
        entries
    }

    private fun liveChannel(pkg:String):Item? {
        val en=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getString("language","tr")=="en"
        val actual=if(pkg=="com.tcl.tv") ChannelLogoService.current() else null
        if(actual==null) return Item(if(en) "Live TV" else "Canlı TV",if(en) "Open the last channel in the TV app" else "Son izlenen kanalı TV uygulamasında aç",null,null,true)
        val key=normalizeChannel(actual.name)
        val candidate=channelCatalog[key]
        var providerLogo:Uri?=null
        // Require both the displayed name and number. Never choose a previous or first channel.
        runCatching {
            context.contentResolver.query(android.media.tv.TvContract.Channels.CONTENT_URI,
                arrayOf("_id","display_name","display_number","input_id","type"),null,null,null)?.use { c ->
                var match:Long?=null
                var ambiguous=false
                while(c.moveToNext()) {
                    if(c.getString(3)?.startsWith("com.tcl.tvinput/")!=true || c.getString(4)=="TYPE_PREVIEW") continue
                    if(normalizeChannel(c.getString(1).orEmpty())!=key || c.getString(2)?.trim()!=actual.number) continue
                    if(match!=null) { ambiguous=true; break }
                    match=c.getLong(0)
                }
                if(!ambiguous) match?.let { providerLogo=android.media.tv.TvContract.buildChannelLogoUri(it) }
            }
        }
        android.util.Log.i("TvShelf","LIVE channel="+actual.number+" "+actual.name+" catalog="+(candidate!=null)+" provider="+(providerLogo!=null))
        val local=candidate?.uri?.takeIf { it.scheme=="asset" }
        return Item(actual.name,(if(en) "Live TV" else "Canlı TV")+" · "+actual.number,local ?: providerLogo,null,true,if(local!=null) providerLogo else candidate?.uri,candidate?.online)
    }

    // Catalogue fallback inspired by Hume Launcher (Apache-2.0).
    // https://github.com/petrobot-admin/hume-launcher
    // JustWatch's unofficial GraphQL interface may change without notice.
    private var catalogueCache: List<Item> = emptyList()
    private var catalogueAt = 0L
    private var catalogueLanguage = ""
    private var catalogueProvider=""
    private var todProviderCode:String?=null
    private fun todProvider():String? = todProviderCode ?: runCatching {
        val conn=URL("https://apis.justwatch.com/content/providers/locale/tr_TR").openConnection().apply { connectTimeout=5000; readTimeout=6000 }
        val arr=org.json.JSONArray(conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() })
        for(i in 0 until arr.length()) {
            val obj=arr.getJSONObject(i)
            if(obj.optString("clear_name").equals("TOD TV",true)) return@runCatching obj.optString("short_name").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,24}")) }?.also { todProviderCode=it }
        }
        null
    }.getOrNull()
    private fun justWatchNetflix(tod:Boolean=false): List<Item> {
        val provider=if(tod) todProvider() ?: run { android.util.Log.i("TvShelf","CATALOG TOD provider unavailable"); return emptyList() } else "nfx"
        val prefs=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE)
        val lang=if(prefs.getString("language","tr")=="en") "en" else "tr"
        val now=android.os.SystemClock.elapsedRealtime()
        val cacheKey=provider+":"+lang
        catalogueByProvider[cacheKey]?.let { if(now-it.first<30*60*1000L) return it.second }
        val queryText="query NetflixTR(\$country: Country!, \$language: Language!, \$first: Int!, \$filter: TitleFilter) { popularTitles(country: \$country, filter: \$filter, first: \$first, sortBy: POPULAR, sortRandomSeed: 0) { edges { node { content(country: \$country, language: \$language) { title shortDescription backdrops { backdropUrl } } offers(country: \$country, platform: WEB) { standardWebURL package { technicalName } } } } } }"
        val groups=mutableListOf<List<Item>>()
        for(kind in listOf("MOVIE","SHOW")) {
            val group=mutableListOf<Item>()
            runCatching {
                val filter=org.json.JSONObject()
                    .put("packages",org.json.JSONArray().put(provider))
                    .put("objectTypes",org.json.JSONArray().put(kind))
                    .put("monetizationTypes",org.json.JSONArray().put("FLATRATE"))
                val vars=org.json.JSONObject().put("country","TR").put("language",lang).put("first",12).put("filter",filter)
                val request=org.json.JSONObject().put("query",queryText).put("variables",vars).toString()
                val conn=URL("https://apis.justwatch.com/graphql").openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod="POST"
                    conn.connectTimeout=5000
                    conn.readTimeout=6000
                    conn.instanceFollowRedirects=false
                    conn.doOutput=true
                    conn.setRequestProperty("Content-Type","application/json")
                    conn.setRequestProperty("Accept","application/json")
                    conn.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
                    val code=conn.responseCode
                    if(code!=200) throw java.io.IOException("HTTP $code")
                    val root=org.json.JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    val edges=root.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")
                        ?: throw java.io.IOException("No catalogue response")
                    for(i in 0 until minOf(edges.length(),12)) {
                        val node=edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                        val content=node.optJSONObject("content") ?: continue
                        val title=content.optString("title").takeIf { it.isNotBlank() } ?: continue
                        val path=content.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl").orEmpty()
                            .replace("{profile}","s1440").replace("{format}","jpg")
                        if(!path.startsWith("/")) continue
                        val art=Uri.parse("https://images.justwatch.com"+path)
                        var link:Uri?=null
                        val offers=node.optJSONArray("offers")
                        if(offers!=null) for(j in 0 until offers.length()) {
                            val offer=offers.optJSONObject(j) ?: continue
                            val candidate=Uri.parse(offer.optString("standardWebURL"))
                            val host=candidate.host?.lowercase(java.util.Locale.ROOT)
                            val technical=offer.optJSONObject("package")?.optString("technicalName").orEmpty()
                            val trusted=!tod && technical=="netflix" && host in setOf("www.netflix.com","netflix.com")
                            if(candidate.scheme=="https" && trusted && !candidate.path.isNullOrBlank() && candidate.path!="/") { link=candidate; break }
                        }
                        val credit=if(tod) "TOD TV · JustWatch" else if(lang=="tr") "Netflix Türkiye · JustWatch" else "Netflix Turkey · JustWatch"
                        val desc=content.optString("shortDescription").takeUnless { it=="null" }.orEmpty()
                        group.add(Item(title,if(desc.isBlank()) credit else "$credit • $desc",art,null,link=link))
                    }
                    android.util.Log.i("TvShelf","CATALOG source=JustWatch country=TR type=$kind items="+group.size+" links="+group.count { it.link!=null })
                } finally { conn.disconnect() }
            }.onFailure { android.util.Log.w("TvShelf","CATALOG source=JustWatch country=TR type=$kind error="+(it.message?.takeIf { msg -> msg.startsWith("HTTP ") } ?: it.javaClass.simpleName)) }
            groups.add(group)
        }
        val mixed=mutableListOf<Item>()
        for(i in 0 until 12) for(group in groups) group.getOrNull(i)?.let { mixed.add(it) }
        val result=mixed.distinctBy { it.title }.take(16)
        if(result.isNotEmpty()) {
            catalogueByProvider[cacheKey]=now to result
        }
        return result
    }

    fun currentLink(pkg:String): Uri? = if(active && selectedPackage==pkg) items.getOrNull(index)?.link else null

    private fun netflixCatalog():List<Item> {
        val prefs=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE)
        val key=prefs.getString("tmdb_api_key","").orEmpty().trim()
        if(key.isBlank()) return emptyList()
        val lang=if(prefs.getString("language","tr")=="en") "en-US" else "tr-TR"
        val list=mutableListOf<Item>()
        for(type in listOf("movie","tv")) {
            runCatching {
                val url="https://api.themoviedb.org/3/discover/"+type+"?watch_region=TR&with_watch_providers=8&with_watch_monetization_types=flatrate&include_adult=false&sort_by=popularity.desc&language="+lang+"&api_key="+java.net.URLEncoder.encode(key,"UTF-8")
                val connection=URL(url).openConnection().apply { connectTimeout=5000; readTimeout=6000 }
                val arr=org.json.JSONObject(connection.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("results")
                if(arr!=null) for(i in 0 until minOf(arr.length(),12)) {
                    val obj=arr.optJSONObject(i) ?: continue
                    val art=obj.optString("backdrop_path").takeIf { it.startsWith("/") && it.length>4 } ?: continue
                    val title=obj.optString(if(type=="tv") "name" else "title").takeIf { it.isNotBlank() } ?: continue
                    val credit=if(lang=="tr-TR") "Netflix Türkiye kataloğu · TMDB / JustWatch" else "Netflix Turkey catalog · TMDB / JustWatch"
                    list.add(Item(title,credit,Uri.parse("https://image.tmdb.org/t/p/w1280"+art),null))
                }
            }.onFailure { android.util.Log.w("TvShelf","Netflix catalog unavailable: "+type) }
        }
        return list.distinctBy { it.title }.take(16)
    }

    fun select(pkg: String?, liveTv:Boolean=false) {
        stop()
        if (pkg.isNullOrBlank()) return
        active = true
        selectedPackage=pkg
        record("…")
        val ticket = generation
        main.postDelayed({
            if (ticket == generation && active) (if(liveTv) liveWorker else worker).execute {
                if(ticket!=generation) return@execute
                val now=android.os.SystemClock.elapsedRealtime()
                val cached=nativeCache[pkg]?.takeIf { !liveTv && now-it.first<60000L }?.second
                val native=if(liveTv) listOfNotNull(liveChannel(pkg)) else cached ?: query(pkg).also { nativeCache[pkg]=now to it }
                if(ticket!=generation) return@execute
                val netflix=pkg in setOf("com.netflix.ninja","com.netflix.mediaclient")
                val tod=pkg in setOf("com.beonetv.tod","com.digiturk.tod","com.digiturk.tod.mobil","com.todtv.tod","com.digiturk.iq.mobil")
                val fallback=(netflix || tod) && native.isEmpty()
                val jw=if(fallback) justWatchNetflix(tod) else emptyList()
                val result=if(fallback && netflix) jw.ifEmpty { netflixCatalog() } else if(fallback) jw else native
                main.post {
                    if (ticket == generation && active) {
                        items = result
                        available = result.isNotEmpty()
                        val images=result.count { it.image!=null }
                        val videos=result.count { it.video!=null }
                        record(if(liveTv) (if(result.isEmpty()) "Kanal bilgisi yok" else "Kanal: "+result.first().title) else if(fallback) (if(result.isEmpty()) "Katalog verisi yok" else (if(jw.isNotEmpty()) "JustWatch TR" else "TMDB")+" · IMG: $images") else "IMG: $images  VID: $videos")
                        if (available) show(0) else onItem(null, null)
                    }
                }
            }
        }, if(liveTv) 0L else 350L)
    }

    fun next(delta: Int) {
        if (items.isNotEmpty()) show((index + delta + items.size) % items.size)
    }

    fun expand(value: Boolean) {
        expanded = value
        runCatching { player?.setVolume(if (value) 1f else 0f, if (value) 1f else 0f) }
    }

    private fun show(position: Int) {
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        ready = false
        index = position
        val item = items[position]
        val ticket = ++generation
        video = item.video
        onItem(item, null)
        (if(item.channelLogo) liveWorker else worker).execute {
            if(ticket!=generation) return@execute
            val bitmap = item.image?.let { load(it) } ?: item.fallbackLogo?.let { load(it) } ?: item.catalogLogo?.let { load(it) }
            main.post {
                if (ticket != generation || !active) bitmap?.recycle()
                else {
                    onItem(item, bitmap)
                    android.util.Log.i("TvShelf", "IMAGE pkg=$selectedPackage loaded=${bitmap!=null} size=${bitmap?.width ?: 0}x${bitmap?.height ?: 0} scheme=${item.image?.scheme ?: "none"} host=${item.image?.host ?: "none"}")
                    if(item.channelLogo) record("Kanal: "+item.title+if(bitmap!=null) "  LOGO: OK" else "  LOGO: —")
                    main.postDelayed({
                        if (ticket == generation && active) {
                            ready = true
                            startVideo()
                        }
                    }, if (bitmap != null) 3500L else 0L)
                    main.postDelayed({
                        if (ticket == generation && active && items.size > 1) next(1)
                    }, if (item.video != null) 35000L else context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getInt("shelf_seconds",9).coerceIn(6,20)*1000L)
                }
            }
        }
    }

    private fun artLimit():Int {
        val pref=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getString("art_quality","auto")
        return when(pref) { "fhd" -> 1920; "4k" -> 3840; else -> if((context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice) 1920 else 3840 }
    }

    private fun load(uri: Uri): Bitmap? {
        val limit=artLimit()
        val high=if(limit>1920 && uri.scheme=="https") when(uri.host) {
            "images.justwatch.com" -> uri.toString().replace("/s1440/","/s2160/").let { Uri.parse(it) }
            "image.tmdb.org" -> Uri.parse(uri.toString().replace("/w1280/","/original/"))
            "i.ytimg.com" -> if(uri.pathSegments.firstOrNull()=="vi" && uri.pathSegments.size>=3) Uri.parse("https://i.ytimg.com/vi/"+uri.pathSegments[1]+"/maxresdefault.jpg") else uri
            else -> uri
        } else uri
        val first=loadBitmap(high,limit)
        if(first!=null && (high==uri || first.width>=640)) return first
        first?.recycle()
        return if(high!=uri) loadBitmap(uri,limit) else null
    }

    private fun loadBitmap(uri: Uri,limit:Int): Bitmap? = runCatching {
        if(uri.scheme=="asset") {
            val path=uri.path?.removePrefix("/") ?: return@runCatching null
            if(!path.matches(Regex("channel_logos/[0-9]{4}\\.png"))) return@runCatching null
            return@runCatching context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }
        val cacheDir=java.io.File(context.cacheDir,"shelf-images-v3").apply { mkdirs() }
        val remote=uri.scheme=="https"
        val cache=if(remote) java.io.File(cacheDir,java.security.MessageDigest.getInstance("SHA-256").digest((limit.toString()+":"+uri.toString()).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }+".img") else null
        if(cache!=null && cache.isFile && System.currentTimeMillis()-cache.lastModified() in 0..604800000L) {
            BitmapFactory.decodeFile(cache.absolutePath)?.let { cache.setLastModified(System.currentTimeMillis()); return@runCatching it }
        }
        val stream = if (uri.scheme == "https") URL(uri.toString()).openConnection().apply {
            connectTimeout = 5000
            readTimeout = 6000
        }.getInputStream() else context.contentResolver.openInputStream(uri) ?: return@runCatching null
        val bytes = stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > 10000000) throw java.io.IOException("Image too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit*2 || (bounds.outWidth.toLong()/sample)*(bounds.outHeight.toLong()/sample)>9_000_000L) sample *= 2
        val decoded=BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val longest=maxOf(decoded.width,decoded.height)
        val bitmap=if(longest>limit) Bitmap.createScaledBitmap(decoded,maxOf(1,decoded.width*limit/longest),maxOf(1,decoded.height*limit/longest),true).also { if(it!==decoded) decoded.recycle() } else decoded
        if(bitmap!=null && cache!=null) runCatching {
            cache.outputStream().use { bitmap.compress(if(bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,96,it) }
            val entries=cacheDir.listFiles().orEmpty().sortedBy { it.lastModified() }
            var total=entries.sumOf { it.length() }
            for(file in entries) { if(total<=128L*1024*1024) break; val size=file.length(); if(file.delete()) total-=size }
        }
        bitmap
    }.getOrNull()

    private fun startVideo() {
        val source = video ?: return
        val output = surface ?: return
        if (!active || !ready || player != null) return
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(output)
            mp.setDataSource(context, source)
            mp.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; fit() }
            mp.setOnPreparedListener {
                if (player === it) {
                    vw = it.videoWidth
                    vh = it.videoHeight
                    android.util.Log.i("TvShelf","VIDEO pkg="+selectedPackage+" size="+vw+"x"+vh)
                    fit()
                    it.setVolume(if (expanded) 1f else 0f, if (expanded) 1f else 0f)
                    it.isLooping = items.size == 1
                    it.start()
                }
            }
            mp.setOnInfoListener { p, what, _ ->
                if (player === p && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    texture.alpha = 1f
                    record("IMG: "+items.count { it.image!=null }+"  VID: "+items.count { it.video!=null }+"  ▶")
                }
                false
            }
            mp.setOnCompletionListener { if (active && player === it && items.size > 1) next(1) }
            mp.setOnErrorListener { p, _, _ -> if (player === p) releasePlayer(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            android.util.Log.w("TvShelf", "Preview video unavailable", e)
        }
    }

    private fun fit() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return
        val area=previewBounds ?: return
        if(area.width()<=0 || area.height()<=0) return
        val factor=if(area.top>0) maxOf(area.width().toFloat()/vw,area.height().toFloat()/vh) else maxOf(w/vw,h/vh)
        val dw=vw*factor; val dh=vh*factor
        texture.setTransform(Matrix().apply { setScale(dw/w,dh/h); postTranslate((w-dw)/2,if(area.top>0) area.top.toFloat() else (h-dh)/2) })
    }

    private fun releasePlayer() {
        val old = player
        player = null
        texture.alpha = 0f
        runCatching { old?.release() }
    }

    fun stop() {
        generation++
        selectedPackage=null
        active = false
        expanded = false
        available = false
        ready = false
        items = emptyList()
        video = null
        main.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    fun destroy() {
        ChannelLogoService.removeListener(channelListener)
        stop()
        texture.surfaceTextureListener = null
        surface?.release()
        surface = null
        worker.shutdownNow()
        liveWorker.shutdownNow()
    }
}
