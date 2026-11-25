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
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected=true
        val config = serviceInfo
        config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        config.packageNames = STOCK_HOME.toTypedArray()
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        config.notificationTimeout = 0
        serviceInfo = config
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

    override fun onDestroy() { connected=false; super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { connected=false; return super.onUnbind(intent) }
    override fun onInterrupt() {}
    companion object {
        @Volatile var connected=false
            private set
        private val STOCK_HOME = setOf("com.google.android.apps.tv.launcherx","com.google.android.tvlauncher")
    }
}
