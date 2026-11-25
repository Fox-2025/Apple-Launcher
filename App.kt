package com.tvos.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class App(
    val label: String,
    val pkg: String,
    val launch: Intent,
    val banner: Bitmap?,
    val icon: Drawable
)
