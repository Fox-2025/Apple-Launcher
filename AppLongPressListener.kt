package com.tvos.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

class AppLongPressListener(private val context: Context) : View.OnTouchListener {

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            // Burada uzun basma işlemleri yapılır
            // Bu örnek için basit bir Toast
            Toast.makeText(context, "Uzun basma algılandı", Toast.LENGTH_SHORT).show()
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }
}
