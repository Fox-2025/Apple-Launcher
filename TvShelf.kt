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
        val channelLogo:Boolean=false, val fallbackLogo:Uri?=null, val catalogLogo:Uri?=null)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
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
    var available = false
        private set

    init {
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
        for (path in listOf("preview_program", "watch_next_program")) {
            runCatching {
                context.contentResolver.query(Uri.parse("content://android.media.tv/" + path),
                    null, null, null, null)?.use { c ->
                    fun value(column: String): String? {
                        val i = c.getColumnIndex(column)
                        return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                    }
                    while (result.size < 10 && c.moveToNext()) {
                        if (value("package_name") != pkg || value("browsable") == "0") continue
                        val art = uri(value("poster_art_uri")) ?: uri(value("thumbnail_uri"))
                        val clip = uri(value("preview_video_uri"))
                        if (art != null || clip != null)
                            result.add(Item(value("title").orEmpty(), value("short_description").orEmpty(), art, clip))
                    }
                }
            }.onFailure { android.util.Log.w("TvShelf", "Provider unavailable: " + path, it) }
        }
        return result.toList()
    }

    private fun normalizeChannel(value:String):String = java.text.Normalizer.normalize(
        value.lowercase(java.util.Locale.ROOT),java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").replace('ı','i').trim()
        .replace(Regex("(?i)(?:\\s|[-_])+(?:fhd|uhd|hd|sd|4k)$"),"")
        .replace(Regex("[^a-z0-9+]"),"")

    private data class ChannelLogo(val name:String,val uri:Uri)
    private val channelCatalog by lazy {
        val entries=linkedMapOf<String,ChannelLogo>()
        runCatching {
            val array=org.json.JSONArray(context.assets.open("turkish_channel_logos.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            for(i in 0 until array.length()) {
                val row=array.getJSONObject(i)
                val name=row.getString("name")
                val image=uri(row.optString("url")) ?: continue
                val aliases=row.optJSONArray("aliases")
                val names=mutableListOf(name)
                if(aliases!=null) for(j in 0 until aliases.length()) names.add(aliases.optString(j))
                for(alias in names) {
                    val key=normalizeChannel(alias)
                    if(key.length>=2) entries.putIfAbsent(key,ChannelLogo(name,image))
                }
            }
        }
        entries
    }

    private fun liveChannel(pkg:String):Item? {
        val resolver=context.contentResolver
        var best:Item?=null
        var bestTime=Long.MIN_VALUE
        for(path in listOf("watch_next_program","preview_program")) {
            runCatching {
                resolver.query(Uri.parse("content://android.media.tv/"+path),null,null,null,null)?.use { c ->
                    fun v(col:String):String? { val i=c.getColumnIndex(col); return if(i>=0 && !c.isNull(i)) c.getString(i) else null }
                    while(c.moveToNext()) {
                        if(v("package_name")!=pkg || v("browsable")=="0") continue
                        // A program poster is not a channel logo. Require a tuner channel URI,
                        // or an exact known Turkish channel name from the live TV provider.
                        val intentUri=v("intent_uri")?.let { raw -> runCatching {
                            android.content.Intent.parseUri(raw,android.content.Intent.URI_INTENT_SCHEME).data
                        }.getOrNull() }
                        val channelUri=intentUri?.takeIf { android.media.tv.TvContract.isChannelUriForTunerInput(it) }
                        var name:String?=null
                        var logo:Uri?=null
                        var program:String?=null
                        if(channelUri!=null) {
                            runCatching {
                                resolver.query(channelUri,arrayOf("display_name","type"),null,null,null)?.use { channel ->
                                    if(channel.moveToFirst() && channel.getString(1)!="TYPE_PREVIEW") {
                                        name=channel.getString(0)
                                        logo=android.media.tv.TvContract.buildChannelLogoUri(channelUri)
                                    }
                                }
                            }
                            if(!name.isNullOrBlank()) runCatching {
                                val now=System.currentTimeMillis()
                                val current=android.media.tv.TvContract.buildProgramsUriForChannel(channelUri,now,now+1)
                                resolver.query(current,arrayOf("title","start_time_utc_millis","end_time_utc_millis"),null,null,null)?.use { p ->
                                    while(p.moveToNext()) if(p.getLong(1)<=now && p.getLong(2)>now) { program=p.getString(0); break }
                                }
                            }
                        }
                        val candidate=channelCatalog[normalizeChannel(name ?: v("title").orEmpty())]
                        if(name.isNullOrBlank()) name=candidate?.name
                        val display=name?.takeIf { it.isNotBlank() } ?: continue
                        val providerLogo=uri(v("logo_uri"))
                        val recent=v("last_engagement_time_utc_millis")?.toLongOrNull() ?: 0L
                        val watched=path=="watch_next_program" && v("watch_next_type")=="0" && recent>0L && recent<=System.currentTimeMillis()
                        val score=if(watched) recent else 0L
                        if(score<=bestTime) continue
                        val english=context.getSharedPreferences("launcher_prefs",Context.MODE_PRIVATE).getString("language","tr")=="en"
                        val source=if(watched) { if(english) "Recently watched · Live TV" else "Yakın zamanda izlenen · Canlı TV" }
                            else { if(english) "Channel suggestion · Live TV" else "Kanal önerisi · Canlı TV" }
                        best=Item(display,if(program.isNullOrBlank()) source else source+"\n"+program,
                            logo,null,true,providerLogo,candidate?.uri)
                        bestTime=score
                    }
                }
            }.onFailure { android.util.Log.w("TvShelf","Live channel metadata unavailable: "+path,it) }
        }
        return best
    }

    fun select(pkg: String?, liveTv:Boolean=false) {
        stop()
        if (pkg.isNullOrBlank()) return
        active = true
        selectedPackage=pkg
        record("…")
        val ticket = generation
        main.postDelayed({
            if (ticket == generation && active) worker.execute {
                val result = if(liveTv) listOfNotNull(liveChannel(pkg)) else query(pkg)
                main.post {
                    if (ticket == generation && active) {
                        items = result
                        available = result.isNotEmpty()
                        val images=result.count { it.image!=null }
                        val videos=result.count { it.video!=null }
                        record(if(liveTv) (if(result.isEmpty()) "Kanal bilgisi yok" else "Kanal: "+result.first().title) else "IMG: $images  VID: $videos")
                        if (available) show(0) else onItem(null, null)
                    }
                }
            }
        }, 350L)
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
        worker.execute {
            val bitmap = item.image?.let { load(it) } ?: item.fallbackLogo?.let { load(it) } ?: item.catalogLogo?.let { load(it) }
            main.post {
                if (ticket != generation || !active) bitmap?.recycle()
                else {
                    onItem(item, bitmap)
                    if(item.channelLogo) record("Kanal: "+item.title+if(bitmap!=null) "  LOGO: OK" else "  LOGO: —")
                    main.postDelayed({
                        if (ticket == generation && active) {
                            ready = true
                            startVideo()
                        }
                    }, if (bitmap != null) 5000L else 0L)
                    main.postDelayed({
                        if (ticket == generation && active && items.size > 1) next(1)
                    }, if (item.video != null) 35000L else 9000L)
                }
            }
        }
    }

    private fun load(uri: Uri): Bitmap? = runCatching {
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
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2160) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
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
        val factor = maxOf(w / vw, h / vh)
        texture.setTransform(Matrix().apply { setScale(vw * factor / w, vh * factor / h, w / 2, h / 2) })
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
        stop()
        texture.surfaceTextureListener = null
        surface?.release()
        surface = null
        worker.shutdownNow()
    }
}
