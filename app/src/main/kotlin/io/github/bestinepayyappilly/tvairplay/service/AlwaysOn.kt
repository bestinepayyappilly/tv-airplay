// Always-on behaviour, ported from besliky/airplay-tv (service/ReceiverService.kt,
// service/Notifications.kt). Copyright (C) 2026 besliky. Licensed under GPL-3.0-or-later.

package io.github.bestinepayyappilly.tvairplay.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.bestinepayyappilly.tvairplay.MainActivity
import io.github.bestinepayyappilly.tvairplay.R
import io.github.bestinepayyappilly.tvairplay.discovery.Advertiser
import io.github.bestinepayyappilly.tvairplay.discovery.NetworkMonitor

/**
 * Keeps the receiver reachable while the TV idles and brings it forward when a sender connects:
 * - publishes mDNS for the server's lifetime and re-announces it on network change and SCREEN_ON
 * - holds wake and Wi-Fi locks only while a session is open
 * - wakes the screen (the panel, or the TV over HDMI-CEC) and opens the app on connect, falling
 *   back to a full-screen-intent notification when background starts are blocked
 *
 * Public methods may be called from any thread; work runs on the main thread.
 */
class AlwaysOn(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val advertiser = Advertiser(ctx)
    private val network = NetworkMonitor(ctx, ::onNetworkChanged)

    private var registration: Advertiser.Registration? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenReceiver: BroadcastReceiver? = null

    fun start(reg: Advertiser.Registration) = onMain {
        registration = reg
        acquireMulticastLock()
        advertiser.publish(reg)
        network.start()
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                // the TV may have slept with the radio down; announce again
                override fun onReceive(c: Context, i: Intent) {
                    Log.i(TAG, "screen on, re-announcing")
                    advertiser.refresh()
                }
            }.also {
                ContextCompat.registerReceiver(ctx, it, IntentFilter(Intent.ACTION_SCREEN_ON),
                    ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }

    fun stop() = onMain {
        registration = null
        advertiser.withdraw()
        network.stop()
        screenReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
        releaseSessionLocks()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        cancelSessionPrompt()
    }

    // inline when already on main, so stop() inside Service.onDestroy unregisters before teardown
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun onNetworkChanged(snapshot: NetworkMonitor.Snapshot) {
        val reg = registration ?: return
        if (snapshot.available) {
            // a new address must be announced again; a first network only needs the registration
            val wasPublished = advertiser.isPublished
            advertiser.publish(reg)
            if (wasPublished) advertiser.refresh()
        } else {
            advertiser.withdraw()
        }
    }

    // ---- session ----

    fun sessionStarted() = onMain {
        acquireSessionLocks()
        wakeScreen()
    }

    fun sessionEnded() = onMain {
        releaseSessionLocks()
        cancelSessionPrompt()
    }

    /** Wakes the screen and opens the app; posts a full-screen prompt if the start is blocked. */
    fun bringToFront() = onMain {
        wakeScreen()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            Settings.canDrawOverlays(ctx) || isInForeground()
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot open the app", e)
        }
        if (!allowed) {
            Log.w(TAG, "background start needs \"display over other apps\"; posting a prompt")
            showSessionPrompt()
        }
    }

    @Suppress("DEPRECATION") // the only way for a service to turn the screen on
    private fun wakeScreen() {
        if (power.isInteractive) return
        Log.i(TAG, "turning the screen on")
        try {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "airplay:wake")
                .acquire(WAKE_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot turn the screen on", e)
        }
    }

    private fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // ---- locks ----

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        multicastLock = wifi?.createMulticastLock("airplay:mdns")?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (e: RuntimeException) { Log.w(TAG, "multicast lock unavailable", e) }
        }
    }

    private fun acquireSessionLocks() {
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:session").apply {
                setReferenceCounted(false)
                acquire(SESSION_LOCK_MAX_MS)
            }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, "airplay:session")?.apply {
                setReferenceCounted(false)
                try { acquire() } catch (e: RuntimeException) { Log.w(TAG, "Wi-Fi lock unavailable", e) }
            }
        }
    }

    private fun releaseSessionLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---- fallback prompt ----

    private fun showSessionPrompt() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(SESSION_CHANNEL_ID, ctx.getString(R.string.session_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, SESSION_NOTIFICATION_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, SESSION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(ctx.getString(R.string.session_prompt_title))
            .setContentText(ctx.getString(R.string.session_prompt_text))
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        try { nm.notify(SESSION_NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    private fun cancelSessionPrompt() {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(SESSION_NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "AlwaysOn"
        private const val WAKE_MS = 3_000L
        // safety cap in case a session end is never reported
        private const val SESSION_LOCK_MAX_MS = 6 * 60 * 60 * 1000L
        private const val SESSION_CHANNEL_ID = "airplay_session"
        private const val SESSION_NOTIFICATION_ID = 2
    }
}
