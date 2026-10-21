/*
 * Copyright 2026 Gravity Compile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gravixcloud.rtc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/** ServiceInfo.FOREGROUND_SERVICE_TYPE_* (literals, so the JVM tests need no SDK stubs) and the type choice. */
internal object CallServiceTypes {
    const val MEDIA_PLAYBACK = 0x02
    const val CAMERA = 0x40
    const val MICROPHONE = 0x80

    fun compute(sdkInt: Int, micWanted: Boolean, micGranted: Boolean, cameraWanted: Boolean, cameraGranted: Boolean): Int {
        if (sdkInt < 29) return 0
        var types = MEDIA_PLAYBACK
        if (sdkInt >= 30) {
            if (micWanted && micGranted) types = types or MICROPHONE
            if (cameraWanted && cameraGranted) types = types or CAMERA
        }
        return types
    }

    fun names(types: Int): List<String> {
        val out = mutableListOf<String>()
        if (types and MICROPHONE != 0) out.add("microphone")
        if (types and MEDIA_PLAYBACK != 0) out.add("mediaPlayback")
        if (types and CAMERA != 0) out.add("camera")
        return out
    }
}

/**
 * GRAVIX (2026-10-21): the call foreground service ([GravixForegroundService] starts and stops it; the Flutter SDK's
 * GravixCallService). Keeps the microphone (Android 11+ silences capture ~5 s after the app leaves the screen unless a
 * `microphone`-typed foreground service runs), the room playback (`mediaPlayback`, listeners too) and optionally the
 * camera alive in the background. Merely being in the merged manifest does nothing.
 *
 * Android 12+ refuses to start a foreground service from the background and Android 14 refuses the microphone/camera
 * types unless the app is visible (and the permission granted): starts and type upgrades happen while the app is on
 * screen; [GravixForegroundService] retries one it could not apply on the next return to the foreground.
 */
class GravixCallService : Service() {

    internal data class Config(
        val title: String?,
        val text: String?,
        val icon: Int,
        val showLeaveAction: Boolean,
        val leaveLabel: String?,
        val micWanted: Boolean,
        val cameraWanted: Boolean,
        val keepAlive: Boolean,
    )

    /** Outcome of a start/update: the applied types, or why it failed. */
    internal data class Outcome(val types: Int, val error: String?)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var config: Config? = null
    private var currentTypes = 0
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = pendingConfig ?: config ?: Config(null, null, 0, false, null, false, false, true)
        pendingConfig = null
        config = cfg
        started = true
        var error: String? = null
        try {
            currentTypes = applyForeground(cfg)
            running = true
            if (cfg.keepAlive) acquireLocks()
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException / SecurityException: nothing may stay half-started
            Log.e(TAG, "startForeground failed", t)
            error = "${t.javaClass.simpleName}: ${t.message}"
            stopSelf()
        }
        deliver(Outcome(currentTypes, error))
        return START_NOT_STICKY
    }

    /** startForeground with the computed types; a refused microphone/camera type falls back to mediaPlayback alone. */
    private fun applyForeground(cfg: Config): Int {
        val notification = buildNotification(cfg)
        val types = computeTypes(cfg)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return 0
        }
        try {
            startForeground(NOTIFICATION_ID, notification, types)
            return types
        } catch (e: SecurityException) {
            if (types == CallServiceTypes.MEDIA_PLAYBACK) throw e
            Log.w(TAG, "types ${CallServiceTypes.names(types)} refused, falling back to mediaPlayback: ${e.message}")
            startForeground(NOTIFICATION_ID, notification, CallServiceTypes.MEDIA_PLAYBACK)
            return CallServiceTypes.MEDIA_PLAYBACK
        }
    }

    private fun computeTypes(cfg: Config): Int = CallServiceTypes.compute(
        Build.VERSION.SDK_INT,
        micWanted = cfg.micWanted,
        micGranted = granted(Manifest.permission.RECORD_AUDIO),
        cameraWanted = cfg.cameraWanted,
        cameraGranted = granted(Manifest.permission.CAMERA),
    )

    private fun granted(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Re-runs startForeground with new types. Never throws. */
    internal fun update(micWanted: Boolean?, cameraWanted: Boolean?): Outcome {
        val old = config ?: return Outcome(currentTypes, "not started")
        val cfg = old.copy(micWanted = micWanted ?: old.micWanted, cameraWanted = cameraWanted ?: old.cameraWanted)
        config = cfg
        val wanted = computeTypes(cfg)
        if (wanted == currentTypes || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Outcome(currentTypes, null)
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(cfg), wanted)
            currentTypes = wanted
            Outcome(currentTypes, null)
        } catch (t: Throwable) {
            // API 34+: adding microphone/camera from the background is a SecurityException; previous types stay
            Log.w(TAG, "type update to ${CallServiceTypes.names(wanted)} refused", t)
            Outcome(currentTypes, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiped away from Recents: the call ends with the task
        Log.i(TAG, "task removed, stopping")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        if (stopping === this) stopping = null
        releaseLocks()
        // a start waiting meanwhile belongs to the next instance, unless this one never got its start command
        if (!started) deliver(Outcome(0, "call service stopped before it started"))
        listener?.onStopped()
        super.onDestroy()
    }

    @Suppress("WakelockTimeout")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gravix:call").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock unavailable", t)
        }
        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "gravix:call").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wifi lock unavailable", t)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wifiLock = null
    }

    private fun buildNotification(cfg: Config): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setContentTitle(cfg.title ?: appLabel())
            .setContentText(cfg.text ?: DEFAULT_TEXT)
            .setSmallIcon(if (cfg.icon != 0) cfg.icon else smallIcon())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, immutable or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (cfg.showLeaveAction) {
            // explicit intent to a non-exported receiver (Android 14 rejects immutable PendingIntents of implicit ones)
            val leave = Intent(this, GravixCallLeaveReceiver::class.java).setAction(ACTION_LEAVE)
            val pi = PendingIntent.getBroadcast(this, 1, leave, immutable or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(0, cfg.leaveLabel ?: DEFAULT_LEAVE, pi).build())
        }
        return builder.build()
    }

    private fun appLabel(): String = try {
        applicationInfo.loadLabel(packageManager).toString()
    } catch (_: Throwable) {
        DEFAULT_TITLE
    }

    /** Meta-data [META_ICON] wins, else the app icon. */
    private fun smallIcon(): Int {
        try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            }
            val res = info.metaData?.getInt(META_ICON, 0) ?: 0
            if (res != 0) return res
        } catch (_: Throwable) {
        }
        return applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_btn_speak_now
    }

    internal interface Listener {
        fun onLeaveRequested()
        fun onStopped()
    }

    companion object {
        private const val TAG = "GravixCallService"
        const val CHANNEL_ID = "gravix_call"
        private const val CHANNEL_NAME = "Ongoing call"
        private const val NOTIFICATION_ID = 0x6763 // "gc"
        private const val DEFAULT_TITLE = "Call"
        private const val DEFAULT_TEXT = "In a room"
        private const val DEFAULT_LEAVE = "Leave"
        internal const val ACTION_LEAVE = "com.gravixcloud.rtc.CALL_LEAVE"

        /** Manifest meta-data: the notification's small icon (a drawable resource). */
        const val META_ICON = "com.gravixcloud.rtc.call_notification_icon"

        @Volatile
        internal var running: Boolean = false
            private set

        @Volatile
        internal var instance: GravixCallService? = null
            private set

        @Volatile
        internal var listener: Listener? = null

        private var pending: ((Outcome) -> Unit)? = null
        private var pendingConfig: Config? = null

        /** The instance a [stop] was sent to, until its onDestroy: never "updated" by a [start]. */
        @Volatile
        private var stopping: GravixCallService? = null

        @Synchronized
        private fun deliver(outcome: Outcome) {
            val callback = pending ?: return
            pending = null
            callback(outcome)
        }

        /** Starts the service; [onResult] gets the applied types once it is in the foreground, or an error. */
        @Synchronized
        internal fun start(context: Context, config: Config, onResult: (Outcome) -> Unit) {
            val live = instance
            // review 2026-10-21: an instance with a stop pending is going away; a start goes to the platform again
            if (running && live != null && live !== stopping) {
                onResult(live.update(config.micWanted, config.cameraWanted))
                return
            }
            pending?.invoke(Outcome(0, "superseded by a newer start request"))
            pending = onResult
            pendingConfig = config
            val intent = Intent(context, GravixCallService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException: started from the background
                Log.e(TAG, "startForegroundService failed", t)
                pending = null
                pendingConfig = null
                onResult(Outcome(0, "${t.javaClass.simpleName}: ${t.message}"))
            }
        }

        internal fun update(micWanted: Boolean?, cameraWanted: Boolean?): Outcome {
            val live = instance ?: return Outcome(0, "not running")
            return live.update(micWanted, cameraWanted)
        }

        @Synchronized
        internal fun stop(context: Context) {
            if (!running && instance == null) return
            stopping = instance
            context.stopService(Intent(context, GravixCallService::class.java))
        }
    }
}

/** The notification's "Leave" action; forwards to [GravixForegroundService.leaveRequests]. */
class GravixCallLeaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GravixCallService.ACTION_LEAVE) return
        GravixCallService.listener?.onLeaveRequested()
    }
}
