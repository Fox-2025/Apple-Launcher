package com.tvos.launcher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
    private lateinit var shelfTitle: LinearLayout
    private lateinit var toggle: TextView
    private lateinit var list: RecyclerView
    private val appAdapter = AppAdapter()

    private var all = listOf<App>()
    private var visible = listOf<App>()
    private var hiddenSet = setOf<String>()
    private var showHidden = false

    private val bigCount = 5
    private val bigHeight = 250

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
        hiddenSet = prefs.getStringSet("hidden", setOf()) ?: setOf()
        showHidden = prefs.getBoolean("show_hidden", false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        // Arka plan
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Saat
        val textClock = TextClock(this).apply {
            textColor = Color.WHITE
            textSize = 64f
            gravity = Gravity.CENTER
        }

        // Üst bar
        shelfTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(16), dp(32), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }

        toggle = TextView(this).apply {
            text = "Gizlenenleri göster"
            setTextColor(Color.WHITE)
            textSize = 14f
            setOnClickListener { toggleHidden() }
            visibility = View.GONE
        }

        // RecyclerView
        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = appAdapter
            clipToPadding = false
            setPadding(0, dp(120), 0, 0)
        }

        loadApps()

        root.addView(backdrop)
        root.addView(shelfTitle)
        root.addView(list)

        appAdapter.setOnItemClickListener { app ->
            try {
                startActivity(app.launch)
            } catch (e: Exception) {
                Toast.makeText(this, "Uygulama başlatılamadı", Toast.LENGTH_SHORT).show()
            }
        }

        list.addOnItemTouchListener(AppLongPressListener(this))
    }

    private fun loadApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        all = apps
            .filter { it.packageName != packageName }
            .mapNotNull { appInfo ->
                try {
                    val intent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    val icon = appInfo.loadIcon(pm)
                    val label = appInfo.loadLabel(pm).toString()
                    App(label, appInfo.packageName, intent!!, null, icon)
                } catch (e: Exception) {
                    null
                }
            }

        updateVisibleList()
    }

    private fun updateVisibleList() {
        visible = all.filter { !hiddenSet.contains(it.pkg) }
        appAdapter.update(visible)
        updateBackdrop()
    }

    private fun updateBackdrop() {
        val current = visible.firstOrNull()
        current?.let {
            backdrop.setImageDrawable(it.icon)
            backdrop.alpha = 0.3f
            backdrop.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(
                    dp(40).toFloat(),
                    dp(40).toFloat(),
                    Shader.TileMode.CLAMP
                )
            )

            shelfTitle.removeAllViews()
            shelfTitle.addView(
                TextView(this).apply {
                    text = it.label
                    setTextSize(32f)
                    setTextColor(Color.WHITE)
                }
            )
        }
    }

    private fun toggleHidden() {
        showHidden = !showHidden
        prefs.edit().putBoolean("show_hidden", showHidden).apply()
        updateVisibleList()
        toggle.text = if (showHidden) "Gizlenenleri gizle" else "Gizlenenleri göster"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("show_hidden", showHidden)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        showHidden = savedInstanceState.getBoolean("show_hidden", false)
        prefs.edit().putBoolean("show_hidden", showHidden).apply()
    }
}
