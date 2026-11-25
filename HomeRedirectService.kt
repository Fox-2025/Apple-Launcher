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
    private var wakePending=false
    private var wakeAttempt=0
    private var stockSeen=false
    private var wakeStarted=0L
    private var coldBoot=false
    private val openLive=object:Runnable {
        override fun run() {
            if(!wakePending) return
            if(!prefs.getBoolean("live_on_power",true) || powerManager?.isInteractive!=true || !connected) {
                wakePending=false; return
            }
            val accepted=LiveTuner.open(this@HomeRedirectService,fast=coldBoot && wakeAttempt==0)
            wakeAttempt++
            android.util.Log.i("HomeRedirectService","POWER tuner accepted="+accepted+" attempt="+wakeAttempt+" elapsed="+(SystemClock.elapsedRealtime()-wakeStarted))
            if(!accepted && wakeAttempt<2) { powerHandler.postDelayed(this,650); return }
            wakePending=false
            if(!accepted && stockSeen) redirectHome()
        }
    }
    private fun scheduleLive(boot:Boolean=false) {
        if(!prefs.getBoolean("live_on_power",true) || wakePending) return
        wakePending=true; wakeAttempt=0; stockSeen=false; coldBoot=boot; wakeStarted=SystemClock.elapsedRealtime()
        android.util.Log.i("HomeRedirectService","POWER scheduling boot="+boot+" uptime="+wakeStarted)
        powerHandler.removeCallbacks(openLive)
        powerHandler.postDelayed(openLive,150)
    }
    private fun redirectHome() {
        val now=SystemClock.uptimeMillis()
        if(now-lastRedirect<350L) return
        lastRedirect=now
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { android.util.Log.w("HomeRedirectService","Home redirect failed",it) }
    }
    private val screenReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context?,intent:Intent?) {
            when(intent?.action) {
                Intent.ACTION_SCREEN_OFF -> { screenWasOff=true; wakePending=false; stockSeen=false; prefs.edit().putBoolean("screen_off_pending",true).apply(); powerHandler.removeCallbacks(openLive); android.util.Log.i("HomeRedirectService","POWER screen_off") }
                Intent.ACTION_SCREEN_ON -> { android.util.Log.i("HomeRedirectService","POWER screen_on"); val boot=tryEarlyBoot(); if(screenWasOff || prefs.getBoolean("screen_off_pending",false)) { screenWasOff=false; prefs.edit().remove("screen_off_pending").apply(); if(!boot) scheduleLive() } }
            }
        }
    }
    private fun tryEarlyBoot():Boolean {
        BootSession.observe(this)
        if(powerManager?.isInteractive!=true || wakePending || !prefs.getBoolean("live_on_power",true)) return false
        if(!BootSession.claimEarly(this)) return false
        prefs.edit().remove("screen_off_pending").remove("live_boot_pending").apply()
        screenWasOff=false
        scheduleLive(boot=true)
        return true
    }
    private fun consumeBoot() {
        val at=prefs.getLong("live_boot_pending",-1L)
        if(at<0) return
        prefs.edit().remove("live_boot_pending").apply()
        if(SystemClock.elapsedRealtime()-at !in 0..120000L || !prefs.getBoolean("live_on_power",true)) return
        if(BootSession.wasHandled(this)) return
        if(powerManager?.isInteractive==true) {
            if(tryEarlyBoot()) return
            BootSession.markHandled(this)
            scheduleLive(boot=true)
        } else { screenWasOff=true; prefs.edit().putBoolean("screen_off_pending",true).apply() }
    }
    private fun stopPower() {
        powerHandler.removeCallbacks(openLive)
        wakePending=false
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
        val early=tryEarlyBoot()
        consumeBoot()
        if(!early && powerManager?.isInteractive==true && prefs.getBoolean("screen_off_pending",false)) { prefs.edit().remove("screen_off_pending").apply(); scheduleLive() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg !in STOCK_HOME) return
        // Avoid redirecting configuration, account, permission or onboarding panels.
        val name = cls.substringAfterLast('.')
        if (name !in setOf("HomeActivity","MainActivity","LauncherActivity","TvLauncherActivity")) return
        // A changed boot count is available before the delayed BOOT_COMPLETED broadcast.
        tryEarlyBoot()
        // During a confirmed wake only, skip our launcher and try the tuner first.
        if(wakePending) {
            stockSeen=true
            powerHandler.removeCallbacks(openLive)
            powerHandler.post(openLive)
            return
        }
        redirectHome()
    }

    override fun onDestroy() { connected=false; stopPower(); super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { connected=false; stopPower(); return super.onUnbind(intent) }
    override fun onInterrupt() {}
    companion object {
        private var activeInstance:HomeRedirectService?=null
        internal fun notifyBoot() { activeInstance?.consumeBoot() }
        internal fun observeLauncher(c:android.content.Context) { BootSession.observe(c); activeInstance?.tryEarlyBoot() }
        @Volatile var connected=false
            private set
        private val STOCK_HOME = setOf("com.google.android.apps.tv.launcherx","com.google.android.tvlauncher")
    }
}

/** Records a real boot only; ordinary Home navigation and package updates do not trigger it. */
class LiveBootReceiver:android.content.BroadcastReceiver() {
    override fun onReceive(context:android.content.Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        BootSession.observe(context)
        if(BootSession.wasHandled(context)) return
        context.getSharedPreferences("launcher_prefs",android.content.Context.MODE_PRIVATE).edit()
            .putLong("live_boot_pending",SystemClock.elapsedRealtime()).apply()
        HomeRedirectService.notifyBoot()
    }
}

/** A real boot changes Settings.Global.boot_count. Seed on update; never infer a boot from Home alone. */
internal object BootSession {
    private fun prefs(c:android.content.Context)=c.getSharedPreferences("launcher_prefs",android.content.Context.MODE_PRIVATE)
    private fun count(c:android.content.Context)=runCatching { android.provider.Settings.Global.getInt(c.contentResolver,"boot_count",-1) }.getOrDefault(-1)
    @Synchronized fun observe(c:android.content.Context) {
        val n=count(c); if(n<0) return
        val p=prefs(c); val old=p.getInt("observed_boot_count",-1)
        if(old==n) return
        val early=old>=0 && SystemClock.elapsedRealtime()<=180000L && p.getBoolean("live_on_power",true)
        p.edit().putInt("observed_boot_count",n).putInt("early_boot_count",if(early) n else -1).apply()
        android.util.Log.i("HomeRedirectService","BOOT count="+n+" previous="+old+" early="+early+" uptime="+SystemClock.elapsedRealtime())
    }
    @Synchronized fun claimEarly(c:android.content.Context):Boolean {
        val n=count(c); val p=prefs(c)
        if(n<0 || p.getInt("early_boot_count",-1)!=n || p.getInt("handled_boot_count",-1)==n || SystemClock.elapsedRealtime()>180000L) return false
        p.edit().putInt("handled_boot_count",n).remove("early_boot_count").apply()
        return true
    }
    @Synchronized fun wasHandled(c:android.content.Context):Boolean {
        val n=count(c); return n>=0 && prefs(c).getInt("handled_boot_count",-1)==n
    }
    @Synchronized fun markHandled(c:android.content.Context) {
        val n=count(c); if(n>=0) prefs(c).edit().putInt("handled_boot_count",n).remove("early_boot_count").apply()
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
    fun open(c:android.content.Context,fast:Boolean=false):Boolean {
        val cached=if(fast) prefs(c).getString("last_tuner_uri",null)?.let { android.net.Uri.parse(it) }?.takeIf { android.media.tv.TvContract.isChannelUriForTunerInput(it) } else null
        val u=cached ?: channel(c) ?: return false
        android.util.Log.i("HomeRedirectService","TUNER cached="+(cached!=null)+" uptime="+SystemClock.elapsedRealtime())
        val i=Intent(Intent.ACTION_VIEW,u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result=runCatching { c.startActivity(Intent(i).setClassName("com.tcl.tv","com.tcl.tv.TVActivity")) }.recoverCatching { c.startActivity(Intent(i).setPackage("com.tcl.tv")) }
        if(result.isSuccess) remember(c,u)
        android.util.Log.i("HomeRedirectService","TUNER explicit channel accepted="+result.isSuccess)
        return result.isSuccess
    }
}
