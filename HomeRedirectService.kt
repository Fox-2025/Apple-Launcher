package com.tvos.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Optional and user-enabled. Receives only stock launcher window transitions.
 * No screen content, key logging, or other application events are requested.
 */
class HomeRedirectService : AccessibilityService() {
    private var lastRedirect = 0L
    private val powerHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var screenWasOff=false
    private var receiverRegistered=false
    private val prefs by lazy { getSharedPreferences("launcher_prefs",MODE_PRIVATE) }
    private val powerManager by lazy { getSystemService(android.os.PowerManager::class.java) }
    private val openLive=Runnable {
        if(prefs.getBoolean("live_on_power",true) && powerManager?.isInteractive==true && connected) {
            val intent=Intent(Intent.ACTION_MAIN).setClassName("com.tcl.tv","com.tcl.tv.TVActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val result=runCatching { startActivity(intent) }.recoverCatching {
                val fallback=packageManager.getLeanbackLaunchIntentForPackage("com.tcl.tv")
                    ?: packageManager.getLaunchIntentForPackage("com.tcl.tv") ?: throw it
                startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            android.util.Log.i("HomeRedirectService","POWER live_tv accepted="+result.isSuccess)
        }
    }
    private fun scheduleLive() {
        powerHandler.removeCallbacks(openLive)
        if(prefs.getBoolean("live_on_power",true)) powerHandler.postDelayed(openLive,1500)
    }
    private val screenReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:Intent?) {
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenWasOff=true; powerHandler.removeCallbacks(openLive) }
                Intent.ACTION_SCREEN_ON -> if(screenWasOff) { screenWasOff=false; scheduleLive() }
            }
        }
    }
    private fun consumeBoot() {
        val at=prefs.getLong("live_boot_pending",-1L)
        if(at<0) return
        prefs.edit().remove("live_boot_pending").apply()
        if(SystemClock.elapsedRealtime()-at in 0..120000L) {
            if(powerManager?.isInteractive==true) scheduleLive() else screenWasOff=true
        }
    }
    private fun stopPower() {
        powerHandler.removeCallbacks(openLive)
        if(receiverRegistered) { runCatching { unregisterReceiver(screenReceiver) }; receiverRegistered=false }
        if(activeInstance===this) activeInstance=null
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected=true
        val config = serviceInfo
        config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        config.packageNames = STOCK_HOME.toTypedArray()
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        config.notificationTimeout = 0
        serviceInfo = config
        activeInstance=this
        if(!receiverRegistered) runCatching {
            val filter=android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
            if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenReceiver,filter,android.content.Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver,filter)
            receiverRegistered=true
        }
        consumeBoot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg !in STOCK_HOME) return
        // Avoid redirecting configuration, account, permission or onboarding panels.
        val name = cls.substringAfterLast('.')
        if (name !in setOf("HomeActivity","MainActivity","LauncherActivity","TvLauncherActivity")) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRedirect < 350L) return
        lastRedirect = now
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { android.util.Log.w("HomeRedirectService","Home redirect failed",it) }
    }

    override fun onDestroy() { connected=false; stopPower(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { connected=false; stopPower(); return super.onUnbind(intent) }
    override fun onInterrupt() {}
    companion object {
        private var activeInstance:HomeRedirectService?=null
        internal fun notifyBoot() { activeInstance?.consumeBoot() }
        @Volatile var connected=false
            private set
        private val STOCK_HOME = setOf("com.google.android.apps.tv.launcherx","com.google.android.tvlauncher")
    }
}

/** Records a real boot only; ordinary Home navigation and package updates do not trigger it. */
class LiveBootReceiver:android.content.BroadcastReceiver() {
    override fun onReceive(context:android.content.Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        context.getSharedPreferences("launcher_prefs",android.content.Context.MODE_PRIVATE).edit()
            .putLong("live_boot_pending",SystemClock.elapsedRealtime()).apply()
        HomeRedirectService.notifyBoot()
    }
}
