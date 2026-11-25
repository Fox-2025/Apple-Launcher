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
            val accepted=LiveTuner.open(this)
            android.util.Log.i("HomeRedirectService","POWER tuner accepted="+accepted)
        }
    }
    private fun scheduleLive() {
        powerHandler.removeCallbacks(openLive)
        if(prefs.getBoolean("live_on_power",true)) powerHandler.postDelayed(openLive,3000)
    }
    private val screenReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:Intent?) {
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenWasOff=true; prefs.edit().putBoolean("screen_off_pending",true).apply(); powerHandler.removeCallbacks(openLive); android.util.Log.i("HomeRedirectService","POWER screen_off") }
                Intent.ACTION_SCREEN_ON -> { android.util.Log.i("HomeRedirectService","POWER screen_on"); if(screenWasOff || prefs.getBoolean("screen_off_pending",false)) { screenWasOff=false; prefs.edit().remove("screen_off_pending").apply(); scheduleLive() } }
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
        if(powerManager?.isInteractive==true && prefs.getBoolean("screen_off_pending",false)) { prefs.edit().remove("screen_off_pending").apply(); scheduleLive() }
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

/** Use an actual tuner channel, never a generic TV activity that can restore HDMI. */
internal object LiveTuner {
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("launcher_prefs",android.content.Context.MODE_PRIVATE)
    fun remember(c:android.content.Context,u:android.net.Uri) {
        if(android.media.tv.TvContract.isChannelUriForTunerInput(u)) prefs(c).edit().putString("last_tuner_uri",u.toString()).apply()
    }
    fun channel(c:android.content.Context):android.net.Uri? {
        val manager=c.getSystemService(android.media.tv.TvInputManager::class.java)
        val tuners=runCatching { manager?.tvInputList.orEmpty().filter { !it.isPassthroughInput && it.type==android.media.tv.TvInputInfo.TYPE_TUNER }.map { it.id }.toSet() }.getOrDefault(emptySet())
        val resolver=c.contentResolver
        val saved=prefs(c).getString("last_tuner_uri",null)?.let { android.net.Uri.parse(it) }?.takeIf { android.media.tv.TvContract.isChannelUriForTunerInput(it) }
        fun valid(u:android.net.Uri):Boolean=runCatching { resolver.query(u,arrayOf("input_id","type"),null,null,null)?.use { it.moveToFirst() && it.getString(1)!="TYPE_PREVIEW" && (tuners.isEmpty() || it.getString(0) in tuners) }==true }.getOrDefault(false)
        if(saved!=null && valid(saved)) return saved
        // Only provider records with explicit tuner URI; ignore passthrough and arbitrary app intents.
        var recent:android.net.Uri?=null; var stamp=0L
        runCatching {
            resolver.query(android.net.Uri.parse("content://android.media.tv/watch_next_program"),null,null,null,null)?.use { cur ->
                fun v(n:String):String? { val i=cur.getColumnIndex(n); return if(i>=0 && !cur.isNull(i)) cur.getString(i) else null }
                while(cur.moveToNext()) {
                    if(v("package_name")!="com.tcl.tv" || v("watch_next_type")!="0") continue
                    val t=v("last_engagement_time_utc_millis")?.toLongOrNull() ?: continue
                    val u=runCatching { Intent.parseUri(v("intent_uri") ?: "",Intent.URI_INTENT_SCHEME).data }.getOrNull() ?: continue
                    if(t>stamp && android.media.tv.TvContract.isChannelUriForTunerInput(u) && valid(u)) { recent=u; stamp=t }
                }
            }
        }
        if(recent!=null) return recent
        for(id in tuners) runCatching {
            resolver.query(android.media.tv.TvContract.buildChannelsUriForInput(id),arrayOf("_id","browsable","type"),null,null,null)?.use { cur ->
                while(cur.moveToNext()) if(cur.getInt(1)==1 && cur.getString(2)!="TYPE_PREVIEW") return android.media.tv.TvContract.buildChannelUri(cur.getLong(0))
            }
        }
        android.util.Log.i("HomeRedirectService","TUNER no accessible channel; inputs="+tuners.size)
        return null
    }
    fun open(c:android.content.Context):Boolean {
        val u=channel(c) ?: return false
        val i=Intent(Intent.ACTION_VIEW,u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result=runCatching { c.startActivity(Intent(i).setClassName("com.tcl.tv","com.tcl.tv.TVActivity")) }.recoverCatching { c.startActivity(Intent(i).setPackage("com.tcl.tv")) }
        if(result.isSuccess) remember(c,u)
        android.util.Log.i("HomeRedirectService","TUNER explicit channel accepted="+result.isSuccess)
        return result.isSuccess
    }
}
