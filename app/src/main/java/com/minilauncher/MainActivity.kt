package com.minilauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 16:9 oranını koruyan kart. */
internal class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY)
        )
    }
}

/**
 * tvOS esinli ana ekran:
 *  - odaktaki uygulamanın görseli bulanıklaştırılıp arka plan olur, adı üstte büyük görünür
 *  - ilk satır büyük kutucuklar, alt satırlar biraz daha küçük
 *  - odaktaki kutucuk büyür, yükselir, hafifçe eğilir ve üzerinden parlama geçer
 *  - uzun bas: başa sabitle, gizle, uygulama bilgisi
 */
class MainActivity : Activity() {

    private class App(
        val label: String,
        val pkg: String,
        val launch: Intent,
        val banner: Drawable?,
        val icon: Drawable
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var backdrop: ImageView
    private lateinit var shelfTitle: TextView
    private lateinit var toggle: TextView
    private lateinit var list: RecyclerView
    private val appAdapter = AppAdapter()

    private var all = listOf<App>()
    private var visible = listOf<App>()
    private var hiddenSet = setOf<String>()
    private var showHidden = false

    private val bigCount = 5       // ilk satırdaki büyük kutucuk sayısı

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mini", MODE_PRIVATE)
        val dm = resources.displayMetrics
        val sidePad = dp(72)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Bulanık arka plan
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
            }
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        // Üstte açık, aşağı doğru kararan perde
        root.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x55000000, 0xB3000000.toInt(), 0xF2000000.toInt())
                )
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePad, dp(28), sidePad, dp(10))
            clipChildren = false
            clipToPadding = false
        }

        // Üst çubuk: Ayarlar + saat
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        val settingsBtn = TextView(this).apply {
            text = "Ayarlar"
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(18), dp(7), dp(18), dp(7))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(20).toFloat() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).setTextColor(if (f) Color.BLACK else 0xCCFFFFFF.toInt())
                (v.background as GradientDrawable).setColor(if (f) Color.WHITE else 0x22FFFFFF)
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        topBar.addView(settingsBtn)
        topBar.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(24) })
        content.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // Üst alan (top shelf): odaktaki uygulamanın adı
        val shelf = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        shelfTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 44f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        shelf.addView(shelfTitle)
        shelf.addView(TextView(this).apply {
            text = "Açmak için OK  ·  Seçenekler için OK'a uzun bas"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 16f
            setPadding(0, dp(4), 0, dp(8))
        })
        content.addView(shelf, LinearLayout.LayoutParams(-1, (dm.heightPixels * 0.27f).toInt()))

        // Uygulama ızgarası
        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 30).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position < bigCount) 6 else 5
                }
            }
            adapter = appAdapter
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(20), 0, dp(40))
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        toggle = TextView(this).apply {
            setTextColor(0x66FFFFFF)
            textSize = 13f
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else 0x66FFFFFF) }
            setOnClickListener { showHidden = !showHidden; refresh() }
        }
        content.addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
        if (currentFocus == null) focusFirst()
    }

    // Home tuşuna tekrar basılınca başa dön
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        list.scrollToPosition(0)
        focusFirst()
    }

    // Ana ekrandan geri tuşuyla çıkılmasın
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun focusFirst() {
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun pinned(): List<String> =
        (prefs.getString("pins", "") ?: "").split("\n").filter { it.isNotEmpty() }

    private fun load() {
        val pm = packageManager
        val seen = HashSet<String>()
        val result = ArrayList<App>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                result.add(
                    App(
                        ri.loadLabel(pm).toString(), pkg, launch,
                        ri.activityInfo.loadBanner(pm), ri.loadIcon(pm)
                    )
                )
            }
        }
        val pins = pinned()
        all = result.sortedWith(
            compareBy<App>({ val i = pins.indexOf(it.pkg); if (i < 0) Int.MAX_VALUE else i })
                .thenBy { it.label.lowercase() }
        )
        refresh()
    }

    private fun refresh() {
        hiddenSet = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
        visible = if (showHidden) all else all.filter { it.pkg !in hiddenSet }
        toggle.text = if (showHidden) "Gizlenenleri sakla" else "Gizlenenleri göster"
        appAdapter.notifyDataSetChanged()
        if (shelfTitle.text.isNullOrEmpty()) visible.firstOrNull()?.let { showShelf(it) }
    }

    private fun showShelf(app: App) {
        shelfTitle.text = app.label
        val src = app.banner ?: app.icon
        val d = src.constantState?.newDrawable()?.mutate() ?: src
        backdrop.setImageDrawable(d)
        backdrop.animate().cancel()
        backdrop.alpha = 0f
        backdrop.animate().alpha(0.75f).setDuration(400).start()
    }

    private fun launchApp(app: App) {
        try {
            startActivity(app.launch)
        } catch (e: Exception) {
            Toast.makeText(this, "Açılamadı: ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOptions(app: App) {
        val isHidden = app.pkg in hiddenSet
        val isPinned = app.pkg in pinned()
        val items = arrayOf(
            if (isPinned) "Sabitlemeyi kaldır" else "Başa sabitle",
            if (isHidden) "Göster" else "Gizle",
            "Uygulama bilgisi (kaldır / devre dışı bırak)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val pins = pinned().filter { it != app.pkg }
                        val newPins = if (isPinned) pins else listOf(app.pkg) + pins
                        prefs.edit().putString("pins", newPins.joinToString("\n")).apply()
                        load()
                        list.scrollToPosition(0)
                    }
                    1 -> {
                        val set = HashSet(hiddenSet)
                        if (isHidden) set.remove(app.pkg) else set.add(app.pkg)
                        prefs.edit().putStringSet("hidden", set).apply()
                        refresh()
                    }
                    else -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .show()
    }

    private inner class VH(
        val root: LinearLayout,
        val card: AspectFrame,
        val img: ImageView,
        val shine: View,
        val label: TextView
    ) : RecyclerView.ViewHolder(root) {
        var tilt: ObjectAnimator? = null
    }

    private inner class AppAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = this@MainActivity

            val img = ImageView(ctx)
            val shine = View(ctx).apply {
                visibility = View.INVISIBLE
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF)
                )
            }
            val card = AspectFrame(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF3A3A3D.toInt(), 0xFF202022.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                elevation = dp(4).toFloat()
                addView(img, FrameLayout.LayoutParams(-1, -1))
                addView(shine, FrameLayout.LayoutParams(dp(70), -1))
            }
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                alpha = 0f
                setPadding(0, dp(10), 0, 0)
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                defaultFocusHighlightEnabled = false
                clipChildren = false
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(dp(10), dp(6), dp(10), dp(6))
                }
                addView(card, LinearLayout.LayoutParams(-1, -2))
                addView(label, LinearLayout.LayoutParams(-1, -2))
            }
            return VH(root, card, img, shine, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = visible[position]

            if (app.banner != null) {
                h.img.scaleType = ImageView.ScaleType.CENTER_CROP
                h.img.setPadding(0, 0, 0, 0)
                h.img.setImageDrawable(app.banner)
            } else {
                h.img.scaleType = ImageView.ScaleType.FIT_CENTER
                h.img.setPadding(dp(34), dp(18), dp(34), dp(18))
                h.img.setImageDrawable(app.icon)
            }
            h.label.text = app.label
            h.root.alpha = if (app.pkg in hiddenSet) 0.4f else 1f
            h.root.setOnClickListener { launchApp(app) }
            h.root.setOnLongClickListener { showOptions(app); true }

            h.root.setOnFocusChangeListener { v, focused ->
                v.pivotX = v.width / 2f
                v.pivotY = h.card.height / 2f
                v.animate()
                    .scaleX(if (focused) 1.1f else 1f)
                    .scaleY(if (focused) 1.1f else 1f)
                    .translationZ(if (focused) dp(16).toFloat() else 0f)
                    .setDuration(160).start()
                h.label.animate().alpha(if (focused) 1f else 0f).setDuration(160).start()

                h.tilt?.cancel()
                h.tilt = null
                h.card.rotationY = 0f
                h.shine.animate().cancel()
                h.shine.visibility = View.INVISIBLE

                if (focused) {
                    showShelf(app)
                    h.card.cameraDistance = resources.displayMetrics.density * 9000f
                    h.tilt = ObjectAnimator.ofFloat(h.card, View.ROTATION_Y, -3f, 3f).apply {
                        duration = 2200
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        start()
                    }
                    val w = h.card.width.toFloat()
                    h.shine.translationX = -dp(70).toFloat()
                    h.shine.visibility = View.VISIBLE
                    h.shine.animate().translationX(w).setDuration(900)
                        .withEndAction { h.shine.visibility = View.INVISIBLE }.start()
                }
            }
        }
    }
}
