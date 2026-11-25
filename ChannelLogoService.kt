package com.tvos.launcher

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Separately enabled by the user. Reads only verified TCL channel fields. */
class ChannelLogoService : AccessibilityService() {
    private val handler=Handler(Looper.getMainLooper())
    private var until=0L
    private var targetWindow=-1
    private var retryPending=false
    private val retry=object:Runnable {
        override fun run() {
            retryPending=false
            if(!connected) return
            capture()
            if(SystemClock.uptimeMillis()<until) { retryPending=true; handler.postDelayed(this,100L) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected=true
        publish(null)
        android.util.Log.i("ChannelLogo","SERVICE connected")
        capture()
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.packageName?.toString()!=TV) return
        targetWindow=event.windowId
        // Do not cancel an already scheduled sample when TCL emits repeated events.
        // Sample throughout the short tune transition, not just its first frame.
        until=SystemClock.uptimeMillis()+2200L
        capture()
        if(!retryPending) { retryPending=true; handler.postDelayed(retry,100L) }
    }

    private fun capture() {
        // The channel banner can be a separate TV overlay window.
        val roots=mutableListOf<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow?.let { roots.add(it) } }
        runCatching {
            val ws=windows.orEmpty()
            try {
                for(w in ws.sortedByDescending { it.id==targetWindow }) {
                    if(w.id==targetWindow || w.isActive || w.isFocused) w.root?.let { roots.add(it) }
                }
            } finally { ws.forEach { it.recycle() } }
        }
        try {
            for(root in roots) {
                if(root.packageName?.toString()!=TV) continue
                val name=read(root,"tvCurChannelName")
                val number=read(root,"tvCurChannelNum")
                if(name.isNullOrBlank() || number.isNullOrBlank() || !number.matches(Regex("[0-9]{1,6}(?:[.-][0-9]{1,4})?"))) continue
                if(name.length>100) continue
                publish(Channel(name,number))
                return
            }
        } catch(e:Exception) {
            android.util.Log.d("ChannelLogo","Channel banner unavailable: "+e.javaClass.simpleName)
        } finally { roots.forEach { it.recycle() } }
    }

    private fun read(root:AccessibilityNodeInfo,id:String):String? {
        val nodes=root.findAccessibilityNodeInfosByViewId(TV+":id/"+id)
        try {
            return nodes.firstOrNull { it.packageName?.toString()==TV && it.isVisibleToUser }?.text?.toString()?.trim()
        } finally { nodes.forEach { it.recycle() } }
    }

    override fun onInterrupt() {}
    private fun disconnected() {
        handler.removeCallbacksAndMessages(null); retryPending=false; connected=false; publish(null)
    }
    override fun onUnbind(intent:android.content.Intent?):Boolean {
        disconnected()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    companion object {
        private const val TV="com.tcl.tv"
        data class Channel(val name:String,val number:String)
        @Volatile var connected=false
            private set
        @Volatile private var lastChannel:Channel?=null
        private val listeners=linkedSetOf<()->Unit>()
        // All listener registration and publishing occur on the main thread.
        fun addListener(listener:()->Unit) { listeners.add(listener) }
        fun removeListener(listener:()->Unit) { listeners.remove(listener) }
        private fun publish(next:Channel?) {
            if(lastChannel==next) return
            lastChannel=next
            if(next!=null) android.util.Log.i("ChannelLogo","Current TCL channel captured: "+next.number+" "+next.name+" uptime="+SystemClock.elapsedRealtime())
            listeners.toList().forEach { it() }
        }
        fun current():Channel? = if(connected) lastChannel else null
    }
}
