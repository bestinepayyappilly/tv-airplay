// Ported from besliky/airplay-tv (service/Advertiser.kt).
// Copyright (C) 2026 besliky. Licensed under GPL-3.0-or-later.

package com.castbay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Publishes _airplay._tcp and (optionally) _raop._tcp through the platform mDNS responder.
 *
 * Registration and unregistration are asynchronous. To never publish the same name twice
 * (which makes the system rename it to "Name (2)"), a new registration only starts after the
 * previous one is confirmed removed. If the name is taken, the configured name is tried once
 * more later, because the conflict is often with this device's own records still cached on
 * the network. All methods must be called on the main thread.
 */
class Advertiser(context: Context) {

    data class Registration(
        val airplayName: String,
        // null skips _raop._tcp (audio advertising off)
        val raopName: String?,
        val port: Int,
        val airplayTxt: Map<String, String>,
        val raopTxt: Map<String, String>,
    )

    private enum class State { IDLE, REGISTERING, REGISTERED, UNREGISTERING }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())

    private var state = State.IDLE
    private var desired: Registration? = null
    private var active: Registration? = null
    private var forceRefresh = false
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    private var pending = 0
    private var failed = false
    private var renamed = false
    private var renameRetried = false
    private var generation = 0
    private var retryDelayMs = RETRY_MIN_MS
    private var retryAt = 0L

    val isPublished: Boolean get() = state == State.REGISTERED && active != null && active == desired

    fun publish(registration: Registration) {
        if (registration != desired) {
            retryAt = 0L
            retryDelayMs = RETRY_MIN_MS
            renameRetried = false
        }
        desired = registration
        sync()
    }

    fun withdraw() {
        desired = null
        retryAt = 0L
        sync()
    }

    /** Re-announces the services, e.g. after the device joined a network or got a new address. */
    fun refresh() {
        if (desired != null) {
            forceRefresh = true
            retryAt = 0L
            renameRetried = false
            sync()
        }
    }

    private fun sync() {
        handler.removeCallbacks(retry)
        when (state) {
            State.REGISTERING, State.UNREGISTERING -> Unit // continues when the callbacks arrive
            State.REGISTERED -> if (desired != active || forceRefresh) unregisterAll()
            State.IDLE -> {
                val reg = desired ?: return
                val wait = retryAt - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    handler.postDelayed(retry, wait)
                    return
                }
                forceRefresh = false
                registerAll(reg)
            }
        }
    }

    private val retry = Runnable { sync() }

    private fun registerAll(reg: Registration) {
        state = State.REGISTERING
        active = reg
        pending = if (reg.raopName != null) 2 else 1
        failed = false
        renamed = false
        val gen = ++generation
        listeners.clear()
        register(gen, reg.airplayName, AIRPLAY_TYPE, reg.port, reg.airplayTxt)
        reg.raopName?.let { register(gen, it, RAOP_TYPE, reg.port, reg.raopTxt) }
        handler.postDelayed({
            if (generation == gen && state == State.REGISTERING) {
                Log.w(TAG, "mDNS responder did not confirm the registration")
                failed = true
                pending = 0
                registrationFinished()
            }
        }, REGISTER_TIMEOUT_MS)
    }

    private fun register(gen: Int, name: String, type: String, port: Int, txt: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            for ((k, v) in txt) {
                try {
                    setAttribute(k, v)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "TXT entry $k rejected", e)
                }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (gen != generation || state != State.REGISTERING) return@post
                    val published = serviceInfo.serviceName ?: name
                    Log.i(TAG, "published $type as \"$published\"")
                    if (published != name) renamed = true
                    if (--pending == 0) registrationFinished()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    if (gen != generation || state != State.REGISTERING) return@post
                    Log.w(TAG, "registration of $type failed ($errorCode)")
                    failed = true
                    if (--pending == 0) registrationFinished()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                handler.post { if (gen == generation) unregisterDone() }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    if (gen != generation) return@post
                    Log.w(TAG, "unregistration of $type failed ($errorCode)")
                    unregisterDone()
                }
            }
        }
        listeners += listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerService($type) failed", e)
            listeners -= listener
            handler.post {
                if (gen == generation && state == State.REGISTERING) {
                    failed = true
                    if (--pending == 0) registrationFinished()
                }
            }
        }
    }

    private fun registrationFinished() {
        state = State.REGISTERED
        if (failed) {
            failed = false
            retryAt = SystemClock.elapsedRealtime() + retryDelayMs
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
            unregisterAll()
        } else {
            retryDelayMs = RETRY_MIN_MS
            if (renamed && !renameRetried) {
                renameRetried = true
                Log.i(TAG, "name already in use, trying the configured name again later")
                val gen = generation
                handler.postDelayed({
                    if (generation == gen && state == State.REGISTERED) {
                        forceRefresh = true
                        sync()
                    }
                }, RENAME_RETRY_MS)
            }
            sync()
        }
    }

    private fun unregisterAll() {
        state = State.UNREGISTERING
        val current = listeners.toList()
        listeners.clear()
        pending = current.size
        val gen = generation
        if (current.isEmpty()) {
            finishUnregister()
            return
        }
        for (l in current) {
            try {
                nsd.unregisterService(l)
            } catch (e: RuntimeException) {
                // the registration never took effect, so there is nothing to remove
                handler.post { if (gen == generation) unregisterDone() }
            }
        }
        handler.postDelayed({ if (generation == gen && state == State.UNREGISTERING) finishUnregister() }, UNREGISTER_TIMEOUT_MS)
    }

    private fun unregisterDone() {
        if (state != State.UNREGISTERING) return
        if (--pending <= 0) finishUnregister()
    }

    private fun finishUnregister() {
        state = State.IDLE
        active = null
        generation++
        sync()
    }

    companion object {
        private const val TAG = "Advertiser"
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val RAOP_TYPE = "_raop._tcp"
        // probing takes about a second, but after a name conflict the responder probes
        // again under a new name, so a registration gets much longer to complete
        private const val REGISTER_TIMEOUT_MS = 15_000L
        private const val UNREGISTER_TIMEOUT_MS = 5_000L
        private const val RENAME_RETRY_MS = 10_000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
    }
}
