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

package com.gravixcloud.rtc.join

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.RoomException
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.LoggingLevel
import com.gravixcloud.rtc.util.launchFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * GRAVIX (0.1.2): fast joining with a few calls, for an app that drives [Room] -- the token cache, the tap-time join,
 * the hand-over to the room screen, one session at a time and the room switch, which apps used to write by hand
 * around `Room.connect`.
 *
 * ```kotlin
 * val join = GravixJoin.Builder(context, GravixJoinTokenProvider(GravixJoinTokenFetcher { r ->
 *     GravixJoinCredentials(serverUrl, myBackend.joinToken(r.room, r.canPublish)) })).build()
 * join.warmAsync(request)                       // a room on screen / under the finger: the token, no connect
 * join.prepare(request)                         // the tap: the connect starts now
 * val room = join.attach(request)               // the room screen: the same connect (null: failed / ended)
 * join.switchTo(next, GravixSwitchBeforeConnect { closeOldScreen(); true })
 * join.leave()                                  // the real exit
 * ```
 *
 * One per app (or per signed-in user). Calls may come from any thread; listener callbacks and futures complete on the
 * main thread. Nothing here changes `Room.connect` or [com.gravixcloud.rtc.service.GravixRoomService] for an app
 * that does not use it.
 *
 * The url a join dials is the measured nearest region when the token's url is one of the measured regions
 * (`GravixRegionMeasurements.resolveConnectUrl`; start the measurement with `GravixRegions.startMeasurement`), else
 * the token's url. Not in the Android SDK (unlike the Flutter SDK): a standby pre-connect -- [warm] fetches the token
 * only (the engine has no standby socket).
 */
@Suppress("TooManyFunctions") // the Kotlin calls and their Java futures
class GravixJoin private constructor(builder: Builder) {
    private val appContext: Context? = builder.appContext

    /** Where the join tokens come from (and are cached). */
    val tokens: GravixJoinTokenProvider = builder.tokens
    private val defaults: GravixJoinHooks = builder.defaults
    private val attachTimeoutMs: Long = builder.attachTimeoutMs
    private val abandonInBackground: Boolean = builder.abandonInBackground
    private val retryCapacityRefusals: Boolean = builder.retryCapacityRefusals
    internal val random: () -> Double = builder.random
    private val listener: GravixJoinListener? = builder.listener
    internal val logSink: GravixLogSink? = builder.logSink
    internal val callbackExecutor: Executor = builder.callbackExecutor ?: mainThreadExecutor()
    internal val lifecycle: GravixAppLifecycle = builder.lifecycle ?: ProcessAppLifecycle()
    internal val nanoTime: () -> Long = builder.nanoTime
    internal val wallTime: () -> Long = builder.wallTime
    internal val lock = Any()
    internal val scope = CoroutineScope(SupervisorJob() + (builder.dispatcher ?: Dispatchers.Main.immediate))
    private val live = HashMap<String, GravixPrejoin>()
    private var currentHandle: GravixPrejoin? = null

    /** The session this GravixJoin started (or adopted) last, while it is live (not ended, its Room not gone). */
    val current: GravixPrejoin?
        get() {
            val c = synchronized(lock) { currentHandle } ?: return null
            if (c.state == GravixPrejoinState.ENDED) return null
            return if (pendingFor(c.key) === c) c else null
        }

    /** The live join for [request]'s session, if any (a failed or ended one is not returned). */
    fun pending(request: GravixJoinRequest): GravixPrejoin? = pendingFor(request.sessionKey)

    // never under the lock: ending a dead Room's join may run its leave (app code)
    private fun pendingFor(key: String): GravixPrejoin? {
        val h = synchronized(lock) { live[key] } ?: return null
        return if (h.dropIfRoomGone()) null else h
    }

    internal fun unregister(handle: GravixPrejoin) {
        synchronized(lock) { if (live[handle.key] === handle) live.remove(handle.key) }
    }

    private fun log(step: String, request: GravixJoinRequest? = null, detail: String? = null, at: LoggingLevel = LoggingLevel.INFO) =
        GravixJoinLog.step(step, request?.sessionKey, null, detail, at, logSink)

    internal fun emit(event: GravixJoinEvent) {
        val l = listener ?: return
        callbackExecutor.execute {
            try {
                l.onEvent(event)
            } catch (e: Exception) {
                log("error", event.request, "listener threw ${e.javaClass.simpleName} (ignored)")
            }
        }
    }

    // ------------------------------------------------------------------------------------------- warm

    /**
     * Ahead of a tap, no connect: [request]'s token into the provider's cache (a room on screen, the one under the
     * finger). Never throws: the credentials, or null when no token could be had.
     */
    @JvmSynthetic
    suspend fun warm(request: GravixJoinRequest): GravixJoinCredentials? {
        log("warmStart", request, null, LoggingLevel.VERBOSE)
        return try {
            val c = tokens.getCredentials(request)
            val via = if (c.fromCache) "cached" else "fetched"
            log("warmOk", request, "via=$via host=${GravixJoinLog.host(c.url)}")
            emit(GravixJoinEvent(GravixJoinEventKind.WARM, request, wallTime(), 0, via))
            c
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("warmFail", request, "not warmed (${GravixJoinLog.errorOf(e)})")
            null
        }
    }

    /** [warm] as a future (fire and forget from a list adapter; null when no token could be had). */
    fun warmAsync(request: GravixJoinRequest): CompletableFuture<GravixJoinCredentials?> =
        launchFuture(scope, callbackExecutor) { warm(request) }

    // ------------------------------------------------------------------------------------------- prepare / switch

    /**
     * The tap: starts [request]'s join now and returns its handle (the same handle while that session is live).
     * Another session of this GravixJoin is left first: the new connect starts only after that leave returned (the
     * token is fetched meanwhile).
     */
    fun prepare(request: GravixJoinRequest): GravixPrejoin = start(request, false, null, null)

    /** [prepare] with [hooks] for this join (ignored when the session's handle is live already). */
    fun prepare(request: GravixJoinRequest, hooks: GravixJoinHooks?): GravixPrejoin = start(request, false, null, hooks)

    /**
     * A room switch (the tap in the old room): the old session is stopped now (its leave), the token fetched beside
     * it, and the new room connects the moment both are done -- never two sessions. Events: SWITCH_START,
     * SWITCH_OLD_LEFT, SWITCH_CONNECTED.
     */
    fun switchTo(request: GravixJoinRequest): GravixPrejoin = start(request, true, null, null)

    /** [switchTo] with your [beforeConnect] beside the old session's leave (false cancels the switch). */
    fun switchTo(request: GravixJoinRequest, beforeConnect: GravixSwitchBeforeConnect?): GravixPrejoin =
        start(request, true, beforeConnect, null)

    /** [switchTo] with [beforeConnect] and [hooks] for the new join. */
    fun switchTo(request: GravixJoinRequest, beforeConnect: GravixSwitchBeforeConnect?, hooks: GravixJoinHooks?): GravixPrejoin =
        start(request, true, beforeConnect, hooks)

    private fun start(
        request: GravixJoinRequest,
        isSwitch: Boolean,
        beforeConnect: GravixSwitchBeforeConnect?,
        hooks: GravixJoinHooks?,
    ): GravixPrejoin {
        pendingFor(request.sessionKey)?.let { liveHandle -> return sameSession(liveHandle, isSwitch) }
        // the work that runs app code (the old leave, the token fetch, beforeConnect) starts after the lock
        val prevDownRef = CompletableDeferred<Job?>()
        val credentials: Deferred<GravixJoinCredentials> = scope.async(start = CoroutineStart.LAZY) { tokens.getCredentials(request) }
        val appDone: Deferred<Boolean>? = beforeConnect?.let { beforeConnectAsync(it, request) }
        val createRoom = hooks?.createRoom ?: defaults.createRoom
        val leave = hooks?.leave ?: defaults.leave
        val options = hooks?.connectOptions ?: defaults.connectOptions
        lateinit var h: GravixPrejoin
        h = GravixPrejoin(
            owner = this,
            request = request,
            target = { joinTarget(h, isSwitch, prevDownRef.await(), appDone, credentials, options) },
            createRoom = createRoom ?: GravixJoinRoomFactory { defaultRoom() },
            connectFn = hooks?.connect ?: defaults.connect ?: defaultConnect(options, request),
            leaveFn = leave ?: GravixJoinLeave.DEFAULT,
            attachTimeoutMs = attachTimeoutMs,
            abandonInBackground = hooks?.abandonInBackground ?: defaults.abandonInBackground ?: abandonInBackground,
            ownsRoom = createRoom == null && leave == null,
            retryCapacityRefusals = retryCapacityRefusals,
        )
        // (the live handle of this session if another thread started it meanwhile, the previous session)
        val (raced, prev) = synchronized(lock) {
            val other = live[h.key]?.takeIf { it.state != GravixPrejoinState.ENDED }
            val previous = currentHandle
            if (other == null) {
                live[h.key] = h
                currentHandle = h
            }
            other to previous
        }
        if (raced != null) {
            // another thread started this session meanwhile: its handle, and nothing of ours runs
            credentials.cancel()
            appDone?.cancel()
            return sameSession(raced, isSwitch)
        }
        val handle = h
        // never two sessions: the current one is stopped NOW, the new connect waits for its leave in target()
        val supersedes = if (prev == null || prev.state == GravixPrejoinState.ENDED) "" else " supersedes=${GravixJoinLog.keyOf(prev.key)}"
        GravixJoinLog.step("prepare", request.sessionKey, 0, "new${if (isSwitch) " switch" else ""}$supersedes", LoggingLevel.INFO, logSink)
        prevDownRef.complete(prev?.abandonInternal(GravixPrejoinEndReason.SUPERSEDED))
        credentials.start() // the token meanwhile (a cache hit after a warm)
        appDone?.start()
        handle.begin()
        if (isSwitch) {
            handle.mark(GravixJoinEventKind.SWITCH_START)
            scope.launch { if (handle.awaitConnected()) handle.mark(GravixJoinEventKind.SWITCH_CONNECTED) }
        }
        return handle
    }

    private fun sameSession(liveHandle: GravixPrejoin, isSwitch: Boolean): GravixPrejoin {
        synchronized(lock) { currentHandle = liveHandle }
        liveHandle.log("prepare", "same handle state=${liveHandle.state.name.lowercase()}${if (isSwitch) " switch" else ""}")
        return liveHandle
    }

    private fun beforeConnectAsync(bc: GravixSwitchBeforeConnect, request: GravixJoinRequest): Deferred<Boolean> = scope.async(start = CoroutineStart.LAZY) {
        try {
            bc.beforeConnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("switchCancelled", request, "beforeConnect threw ${e.javaClass.simpleName}")
            false
        }
    }

    /** What a join started here dials: after the previous session's leave and the app's beforeConnect, the token. */
    @Suppress("LongParameterList")
    private suspend fun joinTarget(
        h: GravixPrejoin,
        isSwitch: Boolean,
        prevDown: Job?,
        appDone: Deferred<Boolean>?,
        credentials: Deferred<GravixJoinCredentials>,
        options: ConnectOptions?,
    ): GravixJoinCredentials {
        prevDown?.join()
        val ok = appDone?.await() ?: true
        if (!ok) {
            h.log("switchCancelled", "beforeConnect said no")
            h.abandonInternal(GravixPrejoinEndReason.CANCELLED)
            throw IllegalStateException("switch cancelled")
        }
        if (isSwitch) h.mark(GravixJoinEventKind.SWITCH_OLD_LEFT)
        val c = try {
            credentials.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            h.log("tokenFail", GravixJoinLog.errorOf(e))
            throw e
        }
        h.mark(GravixJoinEventKind.TOKEN_READY, if (c.fromCache) "cached" else "fetched")
        // the measured nearest region when the url is one of the measured regions (a lookup, never a probe), among
        // the regions allowing the join's media (2026-10-21: the request's, promoted by a camera on connect or the
        // token's video grant; GravixRegionMedia)
        val url = try {
            val media = GravixRegionMedia.joinMedia(h.request.media ?: options?.media, options?.video == true, c.token)
            GravixRegionMeasurements.resolveConnectUrl(c.url, media = media)
        } catch (e: Exception) {
            h.log("error", "region lookup threw ${e.javaClass.simpleName}: the token's url", LoggingLevel.VERBOSE)
            c.url
        }
        val source = if (url == c.url) "token" else "measured"
        h.mark(GravixJoinEventKind.URL_CHOSEN, source, "source=$source host=${GravixJoinLog.host(url)}")
        return if (url == c.url) c else GravixJoinCredentials(url, c.token, c.fromCache)
    }

    private fun defaultRoom(): Room {
        val ctx = appContext ?: throw IllegalStateException("GravixJoin: no Context for the default Room (set a createRoom hook)")
        return GravixRtc.create(ctx)
    }

    private fun defaultConnect(options: ConnectOptions?, request: GravixJoinRequest): GravixJoinConnect = GravixJoinConnect { room, c ->
        val o = options ?: ConnectOptions()
        // GRAVIX (2026-10-21): the request's media reaches the Room (its failover order) unless the options name one
        room.connect(c.url, c.token, if (o.media == null && request.media != null) o.copy(media = request.media) else o)
    }

    // ------------------------------------------------------------------------------------------- adopt

    /**
     * You connected [request]'s session yourself, outside this GravixJoin (your own reconnect: a role change with a new
     * token whose canPublish differs, so the session changed): [room] becomes the current session, already attached.
     * [pending] / [attach] return it; the next [prepare] / [switchTo] and [leave] leave it with [leave] (null: the
     * defaults' leave, else [GravixJoinLeave.DEFAULT]). No background guard, no never-shown watch, no attach timeout.
     *
     * The previous session ends with REPLACED without its leave when a screen had it (you tore it down) or its Room is
     * [room]; one still joining (or connected, never attached) is abandoned normally (SUPERSEDED, its leave runs).
     */
    fun adopt(request: GravixJoinRequest, room: Room, leave: GravixJoinLeave?): GravixPrejoin {
        val key = request.sessionKey
        val liveHandle: GravixPrejoin?
        val prev: GravixPrejoin?
        val h = GravixPrejoin(
            owner = this,
            request = request,
            target = null,
            createRoom = GravixJoinRoomFactory { room },
            connectFn = GravixJoinConnect { _, _ -> },
            leaveFn = leave ?: defaults.leave ?: GravixJoinLeave.DEFAULT,
            attachTimeoutMs = 0,
            abandonInBackground = false,
            ownsRoom = false,
            retryCapacityRefusals = false,
        )
        synchronized(lock) {
            liveHandle = live[key]
            prev = currentHandle
            if (liveHandle == null || liveHandle.room !== room) {
                live[key] = h
                currentHandle = h
            } else {
                currentHandle = liveHandle
            }
        }
        // the replaced ones end after the lock (one still joining runs its leave)
        if (liveHandle != null && liveHandle.room === room) {
            if (prev != null && prev !== liveHandle) prev.replacedBy(room)
            liveHandle.adoptInPlace()
            return liveHandle
        }
        liveHandle?.replacedBy(room)
        if (prev != null && prev !== liveHandle) prev.replacedBy(room)
        h.beginAdopted(room)
        return h
    }

    /** [adopt] with the defaults' leave. */
    fun adopt(request: GravixJoinRequest, room: Room): GravixPrejoin = adopt(request, room, null)

    // ------------------------------------------------------------------------------------------- attach / end

    /**
     * The room screen: the connected Room of [request]'s session -- the one the tap started, or a join started now when
     * there is none (a cold join, with [hooks]). Null when it failed or ended (keep the handle of [prepare] to read its
     * error). The screen claims the session at this call.
     */
    @JvmSynthetic
    suspend fun attach(request: GravixJoinRequest, hooks: GravixJoinHooks?): Room? {
        val h = pending(request) ?: run {
            log("attachCold", request, "no live join: joining now${if (hooks != null) " (hooks passed)" else ""}")
            prepare(request, hooks)
        }
        return h.attach(hooks?.leaveIfNeverShown ?: true)
    }

    /** [attach] without hooks. */
    @JvmSynthetic
    suspend fun attach(request: GravixJoinRequest): Room? = attach(request, null)

    /** Java: [attach] as a future (completes on the main thread; null when failed / ended). */
    fun attachAsync(request: GravixJoinRequest, hooks: GravixJoinHooks?): CompletableFuture<Room?> =
        launchFuture(scope, callbackExecutor) { attach(request, hooks) }

    /** Java: [attach] without hooks, as a future. */
    fun attachAsync(request: GravixJoinRequest): CompletableFuture<Room?> = attachAsync(request, null)

    /** A gate refused [request]'s room after the tap (password, payment, a ban): its join is ended and left. */
    @JvmSynthetic
    suspend fun abandon(request: GravixJoinRequest) {
        val h = pending(request)
        if (h == null) {
            log("abandon", request, "nothing pending", LoggingLevel.VERBOSE)
            return
        }
        h.abandon()
    }

    /** Java: [abandon] as a future. */
    fun abandonAsync(request: GravixJoinRequest): CompletableFuture<Void?> = launchFuture(scope, callbackExecutor) {
        abandon(request)
        null
    }

    /** The real exit of the current session. */
    @JvmSynthetic
    suspend fun leave() {
        val c = synchronized(lock) {
            val c = currentHandle
            currentHandle = null
            c
        }
        if (c == null) {
            log("left", null, "no current session", LoggingLevel.VERBOSE)
            return
        }
        c.leave()
    }

    /** Java: [leave] as a future. */
    fun leaveAsync(): CompletableFuture<Void?> = launchFuture(scope, callbackExecutor) {
        leave()
        null
    }

    /** Another account signed in: every cached token is dropped (a running join is not touched: [leave] it). */
    fun forgetAll() {
        log("forgetAll", null, "tokens dropped")
        tokens.invalidateAll()
    }

    /**
     * Builds a [GravixJoin]. [context]: for the default Room ([GravixRtc.create]); [tokens]: your token call, cached.
     */
    class Builder(context: Context, internal val tokens: GravixJoinTokenProvider) {
        internal val appContext: Context = context.applicationContext ?: context
        internal var defaults: GravixJoinHooks = GravixJoinHooks.Builder().build()
        internal var attachTimeoutMs = DEFAULT_ATTACH_TIMEOUT_MS
        internal var abandonInBackground = true
        internal var retryCapacityRefusals = false
        internal var random: () -> Double = { Random.nextDouble() }
        internal var listener: GravixJoinListener? = null
        internal var logSink: GravixLogSink? = null

        internal var dispatcher: CoroutineDispatcher? = null
        internal var callbackExecutor: Executor? = null
        internal var lifecycle: GravixAppLifecycle? = null
        internal var nanoTime: () -> Long = System::nanoTime
        internal var wallTime: () -> Long = System::currentTimeMillis

        /** How every join builds, connects and leaves its Room (a call's own hooks win). */
        fun setDefaults(hooks: GravixJoinHooks): Builder = apply { defaults = hooks }

        /** Leave a join nobody attached after [ms] (default 20 000; 0: never). A screen that called attach stops it. */
        fun setAttachTimeoutMs(ms: Long): Builder = apply { attachTimeoutMs = ms.coerceAtLeast(0) }

        /**
         * Whether a join is abandoned when the app goes to the background before the screen attached (default true).
         * False for an app that joins in the background on purpose (a call answered from a notification).
         */
        fun setAbandonInBackground(abandon: Boolean): Builder = apply { abandonInBackground = abandon }

        /**
         * Opt-in (0.3.1, default false): a join the server refuses because the app's owner is at its concurrent-user
         * cap ([isCapacityRefusal]: "app user limit reached") is tried again silently on the same Room, waiting 1, 2,
         * 3, 5, 8 s, then every 10 s (each +-20 %), until it connects, fails otherwise, or the join ends (left,
         * abandoned, superseded, the attach timeout before a screen claimed it). The screen's attach just waits
         * longer; no error is surfaced. The cap stays enforced on the server; "app plan limit reached" (billing) and
         * every other error still fail at once. Step lines `capacityRetry n= in=` per wait and `capacityRetryOk
         * after= waited=`. Each refused attempt still emits the Room's `RoomEvent.FailedToConnect` and
         * `RoomEvent.Disconnected(JOIN_FAILURE)`. A connect hook must keep the Room and let the SDK's
         * `RoomException.ConnectException` through (as the error, or in its cause chain); an error that drops it is
         * not recognised. The Flutter SDK 0.7.2's `GravixJoin(retryCapacityRefusals: true)`.
         */
        fun setRetryCapacityRefusals(retry: Boolean): Builder = apply { retryCapacityRefusals = retry }

        /** Timing events of every join (on the main thread). */
        fun setListener(listener: GravixJoinListener): Builder = apply { this.listener = listener }

        /** Where this GravixJoin's step lines go (default [GravixJoinLog.sink], else `GravixRtc.logger`). */
        fun setLogSink(sink: GravixLogSink): Builder = apply { logSink = sink }

        /** Tests: the coroutine dispatcher, the callback executor, the app lifecycle and the clocks. */
        internal fun setTestEnvironment(
            dispatcher: CoroutineDispatcher,
            callbackExecutor: Executor,
            lifecycle: GravixAppLifecycle,
            nanoTime: () -> Long = System::nanoTime,
            wallTime: () -> Long = System::currentTimeMillis,
        ): Builder = apply {
            this.dispatcher = dispatcher
            this.callbackExecutor = callbackExecutor
            this.lifecycle = lifecycle
            this.nanoTime = nanoTime
            this.wallTime = wallTime
        }

        fun build(): GravixJoin = GravixJoin(this)
    }

    companion object {
        /** Long enough for a route with a gate or two before the screen; short enough that a forgotten join does not sit in a room. */
        const val DEFAULT_ATTACH_TIMEOUT_MS = 20_000L

        private const val CAPACITY_REFUSAL_TEXT = "app user limit reached"

        /**
         * Whether [error] is the server's refusal at the app owner's concurrent-user cap ("app user limit reached",
         * the validate answer of a refused WebSocket), as [error] or in its cause chain: what
         * [Builder.setRetryCapacityRefusals] retries. Not the billing refusal ("app plan limit
         * reached"): that one does not clear while the screen is open.
         */
        @JvmStatic
        fun isCapacityRefusal(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).any {
                it is RoomException.ConnectException && it.message?.contains(CAPACITY_REFUSAL_TEXT) == true
            }

        private const val MAX_CAUSE_DEPTH = 4

        private fun mainThreadExecutor(): Executor {
            val main = Handler(Looper.getMainLooper())
            return Executor { r -> if (Looper.myLooper() == Looper.getMainLooper()) r.run() else main.post(r) }
        }
    }
}
