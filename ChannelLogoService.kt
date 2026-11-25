package com.tvos.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional, separately enabled. Reads only the TCL current-channel fields, never remote keys. */
class ChannelLogoService : AccessibilityService() {
    private val handler=Handler(Looper.getMainLooper())
    private val retry=Runnable { capture() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected=true
        lastChannel=null
        capture()
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.packageName?.toString()!=TV) return
        // Read the visible channel banner immediately, then retry once after the UI settles.
        capture()
        handler.removeCallbacks(retry)
        handler.postDelayed(retry,180L)
    }

    private fun capture() {
        val root=runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if(root.packageName?.toString()!=TV) return
            val name=read(root,"tvCurChannelName")
            val number=read(root,"tvCurChannelNum")
            if(name.isNullOrBlank() || number.isNullOrBlank() || !number.matches(Regex("[0-9]{1,6}(?:[.-][0-9]{1,4})?"))) return
            if(name.length>100) return
            val next=Channel(name,number)
            if(lastChannel!=next) {
                lastChannel=next
                android.util.Log.i("ChannelLogo","Current TCL channel captured: "+number+" "+name)
            }
        } catch(e:Exception) {
            android.util.Log.d("ChannelLogo","Channel banner unavailable: "+e.javaClass.simpleName)
        } finally { root.recycle() }
    }

    private fun read(root:AccessibilityNodeInfo,id:String):String? {
        val nodes=root.findAccessibilityNodeInfosByViewId(TV+":id/"+id)
        try {
            return nodes.firstOrNull { it.packageName?.toString()==TV && it.isVisibleToUser }?.text?.toString()?.trim()
        } finally { nodes.forEach { it.recycle() } }
    }

    override fun onInterrupt() {}
    override fun onUnbind(intent:android.content.Intent?):Boolean {
        handler.removeCallbacksAndMessages(null); connected=false; lastChannel=null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); connected=false; lastChannel=null
        super.onDestroy()
    }

    companion object {
        private const val TV="com.tcl.tv"
        data class Channel(val name:String,val number:String)
        @Volatile var connected=false
            private set
        @Volatile private var lastChannel:Channel?=null
        fun current():Channel? = if(connected) lastChannel else null
    }
}
