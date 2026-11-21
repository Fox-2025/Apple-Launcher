package com.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
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

/**
 * Apple TV tarzı sade ana ekran: koyu degrade zemin, geniş yuvarlak kutucuklar,
 * odaklanan kutucuk büyür ve yükselir, adı sadece odakta görünür.
 * Reklam yok, öneri yok, internet izni yok.
 * Uygulamaya uzun basarak gizle / göster / uygulama bilgisi.
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
    private lateinit var toggle: TextView
    private lateinit var list: RecyclerView
    private val appAdapter = AppAdapter()

    private var all = listOf<App>()
    private var visible = listOf<App>()
    private var hiddenSet = setOf<String>()
    private var showHidden = false

    private val spanCount = 5
    private var tileW = 0
    private var tileH = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mini", MODE_PRIVATE)

        val sidePad = dp(80)
        val gap = dp(24)
        tileW = (resources.displayMetrics.widthPixels - 2 * sidePad - spanCount * gap) / spanCount
        tileH = tileW * 9 / 16

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF2B2B2E.toInt(), 0xFF000000.toInt())
            )
            setPadding(sidePad, dp(32), sidePad, dp(12))
            clipChildren = false
            clipToPadding = false
        }

        // Sağ üstte saat ve tarih
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        header.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.END
        })
        header.addView(TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            setTextColor(0x99FFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.END
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, spanCount)
            adapter = appAdapter
            itemAnimator = null
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(40), 0, dp(40))
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        toggle = TextView(this).apply {
            setTextColor(0x66FFFFFF)
            textSize = 14f
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Color.WHITE else 0x66FFFFFF) }
            setOnClickListener { showHidden = !showHidden; refresh() }
        }
        root.addView(
            toggle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.END }
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    // Home tuşuna tekrar basılınca başa dön
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        list.scrollToPosition(0)
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    // Ana ekrandan geri tuşuyla çıkılmasın
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

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
        all = result.sortedBy { it.label.lowercase() }
        refresh()
    }

    private fun refresh() {
        hiddenSet = HashSet(prefs.getStringSet("hidden", emptySet()) ?: emptySet())
        visible = if (showHidden) all else all.filter { it.pkg !in hiddenSet }
        toggle.text = if (showHidden) "Gizlenenleri sakla" else "Gizlenenleri göster"
        appAdapter.notifyDataSetChanged()
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
        val items = arrayOf(
            if (isHidden) "Göster" else "Gizle",
            "Uygulama bilgisi (kaldır / devre dışı bırak)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                if (which == 0) {
                    val set = HashSet(hiddenSet)
                    if (isHidden) set.remove(app.pkg) else set.add(app.pkg)
                    prefs.edit().putStringSet("hidden", set).apply()
                    refresh()
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .show()
    }

    private inner class VH(
        val root: LinearLayout,
        val img: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(root)

    private inner class AppAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = this@MainActivity

            val img = ImageView(ctx)
            val card = FrameLayout(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF3A3A3D.toInt(), 0xFF202022.toInt())
                ).apply { cornerRadius = dp(14).toFloat() }
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                elevation = dp(4).toFloat()
                addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
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
                layoutParams = RecyclerView.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(12), dp(6), dp(12), dp(6))
                }
                addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileH))
                addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnFocusChangeListener { v, focused ->
                    v.pivotX = v.width / 2f
                    v.pivotY = tileH / 2f
                    v.animate()
                        .scaleX(if (focused) 1.12f else 1f)
                        .scaleY(if (focused) 1.12f else 1f)
                        .translationZ(if (focused) dp(16).toFloat() else 0f)
                        .setDuration(150).start()
                    label.animate().alpha(if (focused) 1f else 0f).setDuration(150).start()
                }
            }
            return VH(root, img, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val app = visible[position]
            if (app.banner != null) {
                h.img.scaleType = ImageView.ScaleType.CENTER_CROP
                h.img.setPadding(0, 0, 0, 0)
                h.img.setImageDrawable(app.banner)
            } else {
                h.img.scaleType = ImageView.ScaleType.FIT_CENTER
                h.img.setPadding(dp(36), dp(20), dp(36), dp(20))
                h.img.setImageDrawable(app.icon)
            }
            h.label.text = app.label
            h.root.alpha = if (app.pkg in hiddenSet) 0.4f else 1f
            h.root.setOnClickListener { launchApp(app) }
            h.root.setOnLongClickListener { showOptions(app); true }
        }
    }
}
