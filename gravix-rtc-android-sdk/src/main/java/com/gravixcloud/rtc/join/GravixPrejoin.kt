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

import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.util.LoggingLevel
import com.gravixcloud.rtc.util.launchFuture
import com.gravixcloud.rtc.util.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.milliseconds

/**
 * GRAVIX (0.1.2): one join of one Room, started at the user's tap ([GravixJoin.prepare]) and handed to the room screen
 * when it is ready ([attach]).
 *
 * Why each rule is here (field reports from the apps that wrote this by hand):
 *  - The tap starts the connect at once, beside the app's own work and the navigation; the screen's [attach] gets that
 *    same connect (no second Room, no second session for one identity).
 *  - A Back right after the tap sent the app to the background before the room screen opened; the join connected
 *    anyway and a ghost viewer stayed in the room with no screen to leave it: the join is abandoned when the app goes
 *    to the background before [attach] (or was started there).
 *  - The screen claims the session when it CALLS [attach] (call it in onCreate: the screen is up while the connect
 *    runs). From then on only "background before the screen was shown" leaves ([GravixPrejoinEndReason.NEVER_SHOWN]);
 *    once shown (a frame drawn in the foreground, or [markShown]) Home / minimize never leaves.
 *  - A Room the server ended (a kick) or the app disconnected is not handed out again: the join ends
 *    ([GravixPrejoinEndReason.ROOM_GONE]) and your leave hook does not run on it.
 *  - A join nobody attaches (a gate refused the room after the tap and the app forgot to abandon) leaves after the
 *    attach timeout.
 *  - [abandon] while joining ends the connect at once (cancelled, then the leave), instead of waiting for a join
 *    nobody wants.
 *
 * Room events between the connect and [attach] are not replayed: read `room.remoteParticipants` and `room.state` after
 * [attach], then listen.
 */
@Suppress("TooManyFunctions", "LongParameterList") // the Kotlin calls and their Java futures; an internal constructor
class GravixPrejoin internal constructor(
    private val owner: GravixJoin,
    /** The session (room, identity, publish right). */
    val request: GravixJoinRequest,
    private val target: (suspend () -> GravixJoinCredentials)?,
    private val createRoom: GravixJoinRoomFactory,
    private val connectFn: GravixJoinConnect,
    private val leaveFn: GravixJoinLeave,
    private val attachTimeoutMs: Long,
    /** Whether the app going to the background before [attach] abandons this join. */
    val abandonInBackground: Boolean,
    // built with the default factory AND the default leave: the only Room released after it died (ROOM_GONE)
    private val ownsRoom: Boolean,
) {
    internal val key: String = request.sessionKey
    private val lock = owner.lock
    private val startNs = owner.nanoTime()

    /** When it started (the tap; ms since the epoch). */
    val startedAtMs: Long = owner.wallTime()

    private val connectedDeferred = CompletableDeferred<Boolean>()
    private val endedDeferred = CompletableDeferred<GravixPrejoinEndReason>()

    @Volatile
    private var stateField = GravixPrejoinState.STARTING

    /** Where it is. */
    val state: GravixPrejoinState
        get() = stateField

    /** The Room, once the token is in hand (connecting or later). */
    @Volatile
    var room: Room? = null
        private set

    /** What it dials, once known (your app's data: it holds the token). */
    @Volatile
    var credentials: GravixJoinCredentials? = null
        private set

    /** Why the token or the connect failed. */
    @Volatile
    var error: Throwable? = null
        private set

    /** Why it ended, once [state] is ENDED. */
    @Volatile
    var endReason: GravixPrejoinEndReason? = null
        private set

    private var joinJob: Job? = null
    private var timeoutJob: Job? = null
    private var roomWatchJob: Job? = null
    private var lifecycleHandle: AutoCloseable? = null
    private var leaving: Job? = null
    private var claimed = false
    private var watchShown = false
    private var shown = false
    private var roomLeft = false
    private var leaveSkip: String? = null

    @Volatile
    private var roomDisconnected = false
    private var roomWasLive = false
    private var connectStartMs = 0L

    private val isEnded: Boolean
        get() = stateField == GravixPrejoinState.ENDED

    private fun sinceTapMs(): Long = (owner.nanoTime() - startNs) / 1_000_000

    // ------------------------------------------------------------------------------------------- public calls

    /**
     * The connected Room for the room screen, or null when the join failed or ended. The same Room for every call. The
     * screen claims the session at this CALL (see the class comment); with [leaveIfNeverShown] the Room is left when
     * the app goes to the background before the screen was shown. The first call's [leaveIfNeverShown] holds.
     */
    @JvmSynthetic
    suspend fun attach(leaveIfNeverShown: Boolean): Room? {
        if (isEnded || dropIfRoomGone()) {
            log("attachCall", "ended (${endReason?.name?.lowercase() ?: "room_gone"}): null")
            return null
        }
        log("attachCall", "${if (claimed) "again" else "claimed"} state=${stateField.name.lowercase()}")
        claim(leaveIfNeverShown)
        val ok = connectedDeferred.await()
        if (!ok || isEnded) return null
        synchronized(lock) {
            if (stateField == GravixPrejoinState.ATTACHED) return room
            if (stateField != GravixPrejoinState.CONNECTED) return null
            stateField = GravixPrejoinState.ATTACHED
        }
        mark(GravixJoinEventKind.ATTACHED)
        return room
    }

    /** [attach] with the never-shown watch on. */
    @JvmSynthetic
    suspend fun attach(): Room? = attach(true)

    /** Java: [attach] as a future (completes on the [GravixJoin]'s callback thread; null when failed / ended). */
    fun attachAsync(leaveIfNeverShown: Boolean): CompletableFuture<Room?> =
        launchFuture(owner.scope, owner.callbackExecutor) { attach(leaveIfNeverShown) }

    /** Java: [attach] with the never-shown watch on. */
    fun attachAsync(): CompletableFuture<Room?> = attachAsync(true)

    /**
     * A screen of this session is shown to the user (call it from the room screen's onResume when you know better than
     * the frame watch, e.g. a screen drawn under an overlay): from now on the background never leaves the room. Does
     * not attach.
     */
    fun markShown() {
        synchronized(lock) {
            if (isEnded || shown) return
            shown = true
        }
        mark(GravixJoinEventKind.SHOWN, null, "markShown")
        disposeLifecycle()
    }

    /** Ends the join and leaves its Room (a connect still running is cancelled first). Idempotent. */
    @JvmSynthetic
    suspend fun abandon() {
        abandonInternal(GravixPrejoinEndReason.ABANDONED).join()
    }

    /** Java: [abandon] as a future. */
    fun abandonAsync(): CompletableFuture<Void?> = launchFuture(owner.scope, owner.callbackExecutor) {
        abandon()
        null
    }

    /** The real exit of an attached Room (also ends a join that was not attached). */
    @JvmSynthetic
    suspend fun leave() {
        abandonInternal(GravixPrejoinEndReason.LEFT).join()
    }

    /** Java: [leave] as a future. */
    fun leaveAsync(): CompletableFuture<Void?> = launchFuture(owner.scope, owner.callbackExecutor) {
        leave()
        null
    }

    /** The join gate: true once connected; false when it failed or ended first. */
    @JvmSynthetic
    suspend fun awaitConnected(): Boolean = connectedDeferred.await()

    /** Java: [awaitConnected] as a future. */
    fun connectedFuture(): CompletableFuture<Boolean> =
        launchFuture(owner.scope, owner.callbackExecutor) { connectedDeferred.await() }

    /** Completes once, with why it ended. A screen that attached listens to it (e.g. NEVER_SHOWN: close the screen). */
    @JvmSynthetic
    suspend fun awaitEnded(): GravixPrejoinEndReason = endedDeferred.await()

    /** Java: [awaitEnded] as a future. */
    fun endedFuture(): CompletableFuture<GravixPrejoinEndReason> =
        launchFuture(owner.scope, owner.callbackExecutor) { endedDeferred.await() }

    override fun toString(): String = "GravixPrejoin(${stateField.name.lowercase()})"

    // ------------------------------------------------------------------------------------------- the join

    internal fun begin() {
        mark(GravixJoinEventKind.PREJOIN_START)
        if (abandonInBackground) {
            if (owner.lifecycle.isInBackground) {
                // started behind the user's back: nothing is dialled
                abandonInternal(GravixPrejoinEndReason.BACKGROUND)
                return
            }
            try {
                lifecycleHandle = owner.lifecycle.observe(::onBackground, ::onForeground)
            } catch (e: Exception) {
                log("error", "no lifecycle guard (${e.javaClass.simpleName})")
            }
        }
        if (attachTimeoutMs > 0) {
            timeoutJob = owner.scope.launch {
                delay(attachTimeoutMs)
                if (!claimed && stateField != GravixPrejoinState.ATTACHED) abandonInternal(GravixPrejoinEndReason.ATTACH_TIMEOUT)
            }
        }
        joinJob = owner.scope.launch { join() }
    }

    private suspend fun join() {
        val t = try {
            target!!.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isEnded) return
            error = e
            mark(GravixJoinEventKind.CONNECT_FAILED, e.javaClass.simpleName, "no token (${GravixJoinLog.errorOf(e)})")
            abandonInternal(GravixPrejoinEndReason.FAILED)
            return
        }
        if (isEnded) return
        credentials = t
        val r = try {
            createRoom.create()
        } catch (e: Exception) {
            if (isEnded) return
            error = e
            mark(GravixJoinEventKind.CONNECT_FAILED, e.javaClass.simpleName, "createRoom threw ${e.javaClass.simpleName}")
            abandonInternal(GravixPrejoinEndReason.FAILED)
            return
        }
        val endedMeanwhile = synchronized(lock) {
            if (isEnded) {
                true
            } else {
                room = r
                stateField = GravixPrejoinState.CONNECTING
                false
            }
        }
        if (endedMeanwhile) {
            // ended between the token and the Room: this Room was never handed out, leave it here
            log("leaveStart", "a Room built after the end")
            withContext(NonCancellable) { runLeave(r) }
            return
        }
        connectStartMs = sinceTapMs()
        mark(GravixJoinEventKind.CONNECT_START, null, "host=${GravixJoinLog.host(t.url)}")
        try {
            connectFn.connect(r, t)
        } catch (e: CancellationException) {
            if (isEnded) {
                log("connectCancelled", "abandoned during the connect", LoggingLevel.VERBOSE)
                return
            }
            throw e
        } catch (e: Throwable) {
            if (isEnded) {
                log("connectFail", "after the end (${GravixJoinLog.errorOf(e)})", LoggingLevel.VERBOSE)
                return
            }
            error = e
            mark(
                GravixJoinEventKind.CONNECT_FAILED,
                e.javaClass.simpleName,
                "${GravixJoinLog.errorOf(e)} in=${sinceTapMs() - connectStartMs}ms",
            )
            abandonInternal(GravixPrejoinEndReason.FAILED)
            return
        }
        val keep = synchronized(lock) {
            if (isEnded) {
                false
            } else {
                stateField = GravixPrejoinState.CONNECTED
                true
            }
        }
        if (!keep) {
            log("connectOk", "after the end: not kept")
            return
        }
        watchRoom(r)
        mark(GravixJoinEventKind.CONNECTED)
        connectedDeferred.complete(true)
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    private fun claim(leaveIfNeverShown: Boolean) {
        val watch = synchronized(lock) {
            if (claimed) return
            claimed = true
            timeoutJob?.cancel()
            timeoutJob = null
            if (shown) return
            if (leaveIfNeverShown && lifecycleHandle != null) {
                watchShown = true
                true
            } else {
                false
            }
        }
        if (watch) awaitShownFrame() else disposeLifecycle()
    }

    private fun onBackground() {
        val reason = synchronized(lock) {
            when {
                isEnded || shown -> null
                !claimed -> GravixPrejoinEndReason.BACKGROUND
                watchShown -> GravixPrejoinEndReason.NEVER_SHOWN
                else -> null
            }
        } ?: return
        abandonInternal(reason)
    }

    private fun onForeground() {
        val again = synchronized(lock) { claimed && watchShown && !shown && !isEnded }
        if (again) awaitShownFrame()
    }

    private fun awaitShownFrame() {
        try {
            owner.lifecycle.afterNextFrame {
                val nowShown = synchronized(lock) {
                    if (shown || isEnded || owner.lifecycle.isInBackground) {
                        false
                    } else {
                        shown = true
                        true
                    }
                }
                if (nowShown) {
                    mark(GravixJoinEventKind.SHOWN, null, "frame")
                    disposeLifecycle()
                }
                // otherwise wait for the next foreground (its frame) or a background
            }
        } catch (e: Exception) {
            log("error", "no frame watch (${e.javaClass.simpleName})")
        }
    }

    private fun disposeLifecycle() {
        val h = synchronized(lock) {
            val h = lifecycleHandle
            lifecycleHandle = null
            h
        } ?: return
        try {
            h.close()
        } catch (e: Exception) {
            log("error", "lifecycle unwatch threw ${e.javaClass.simpleName}", LoggingLevel.VERBOSE)
        }
    }

    // ------------------------------------------------------------------------------------------- the Room's end

    // Watches the connected Room for the end of its session (a resume or a full reconnect keeps it CONNECTED /
    // RECONNECTING; RoomEvent.Disconnected comes only when it is over).
    private fun watchRoom(r: Room) {
        roomIsDead()
        roomWatchJob = owner.scope.launch {
            r.events.events.collect {
                if (it is RoomEvent.Disconnected) {
                    roomDisconnected = true
                    log("roomGone", "reason=${it.reason.name.lowercase()}")
                    abandonInternal(GravixPrejoinEndReason.ROOM_GONE)
                }
            }
        }
    }

    // Whether the connected (or attached) Room is dead: its RoomEvent.Disconnected seen, or DISCONNECTED after it was
    // live (a reconnect reads RECONNECTING, a resume CONNECTED).
    private fun roomIsDead(): Boolean {
        val s = stateField
        if (s != GravixPrejoinState.CONNECTED && s != GravixPrejoinState.ATTACHED) return false
        val r = room ?: return false
        if (roomDisconnected) return true
        return try {
            if (r.state != Room.State.DISCONNECTED) {
                roomWasLive = true
                false
            } else {
                roomWasLive
            }
        } catch (e: Exception) {
            log("error", "room state unreadable (${e.javaClass.simpleName})", LoggingLevel.VERBOSE)
            false
        }
    }

    /** A connected (or attached) join whose Room is dead ends here: true when it did. */
    internal fun dropIfRoomGone(): Boolean {
        if (isEnded || !roomIsDead()) return false
        abandonInternal(GravixPrejoinEndReason.ROOM_GONE)
        return true
    }

    // ------------------------------------------------------------------------------------------- the end

    /** Ends the join with [reason] and leaves its Room; returns the leave's job (the running one when ended). */
    internal fun abandonInternal(reason: GravixPrejoinEndReason): Job {
        var r = reason
        val leaveJob: Job
        synchronized(lock) {
            if (isEnded) return leaving ?: Job().apply { complete() }
            val dead = roomIsDead()
            if (dead && r != GravixPrejoinEndReason.LEFT) r = GravixPrejoinEndReason.ROOM_GONE
            if (dead && !ownsRoom) {
                roomLeft = true // never your leave hook on a dead Room
                leaveSkip = "dead room"
            }
            finishLocked(r)
            val running = joinJob
            // started after the lock: the leave is app code (and runs at once on Dispatchers.Main.immediate)
            leaveJob = owner.scope.launch(start = CoroutineStart.LAZY) {
                // a connect still running is ended first (Room.connect unwinds on cancel), then the leave
                if (running != null) {
                    try {
                        withDeadline(UNWIND_CAP_MS.milliseconds) { running.cancelAndJoin() }
                    } catch (e: Exception) {
                        log("error", "connect did not unwind in ${UNWIND_CAP_MS}ms (${e.javaClass.simpleName})")
                    }
                }
                withContext(NonCancellable) { leaveRoom() }
            }
            leaving = leaveJob
        }
        leaveJob.start()
        if (r == GravixPrejoinEndReason.LEFT) {
            mark(GravixJoinEventKind.LEFT)
        } else {
            mark(GravixJoinEventKind.ABANDONED, r.name.lowercase())
        }
        afterFinish(r)
        return leaveJob
    }

    private fun finishLocked(reason: GravixPrejoinEndReason) {
        endReason = reason
        stateField = GravixPrejoinState.ENDED
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun afterFinish(reason: GravixPrejoinEndReason) {
        disposeLifecycle()
        roomWatchJob?.cancel()
        roomWatchJob = null
        owner.unregister(this)
        connectedDeferred.complete(false)
        endedDeferred.complete(reason)
    }

    private suspend fun leaveRoom() {
        val r = room
        if (r == null) {
            log("leaveSkipped", "no room yet")
            return
        }
        val skip = synchronized(lock) {
            if (roomLeft) {
                leaveSkip ?: "already left"
            } else {
                roomLeft = true
                null
            }
        }
        if (skip != null) {
            log("leaveSkipped", skip)
            return
        }
        runLeave(r)
    }

    private suspend fun runLeave(r: Room) {
        val t0 = sinceTapMs()
        log("leaveStart", "after=${endReason?.name?.lowercase() ?: "-"}")
        try {
            leaveFn.leave(r)
            log("leaveDone", "in=${sinceTapMs() - t0}ms")
        } catch (e: Exception) {
            log("leaveFail", "threw ${e.javaClass.simpleName} in=${sinceTapMs() - t0}ms (ignored)")
        }
    }

    // ------------------------------------------------------------------------------------------- adopt

    /** [GravixJoin.adopt] replaced this join with [newRoom]. */
    internal fun replacedBy(newRoom: Room) {
        val attachedOrSame = synchronized(lock) {
            if (isEnded) return
            if (stateField == GravixPrejoinState.ATTACHED || room === newRoom) {
                roomLeft = true // the app's room now: never left here
                finishLocked(GravixPrejoinEndReason.REPLACED)
                true
            } else {
                false
            }
        }
        if (attachedOrSame) {
            mark(GravixJoinEventKind.ABANDONED, GravixPrejoinEndReason.REPLACED.name.lowercase())
            afterFinish(GravixPrejoinEndReason.REPLACED)
        } else {
            abandonInternal(GravixPrejoinEndReason.SUPERSEDED)
        }
    }

    /** [GravixJoin.adopt] of this join's own Room under its own key. */
    internal fun adoptInPlace() {
        synchronized(lock) {
            if (stateField != GravixPrejoinState.CONNECTED) return
            stateField = GravixPrejoinState.ATTACHED
            claimed = true
            shown = true
            timeoutJob?.cancel()
            timeoutJob = null
        }
        disposeLifecycle()
        mark(GravixJoinEventKind.ADOPTED)
    }

    /** Sets this join up as an adopted (attached) Room. */
    internal fun beginAdopted(r: Room) {
        synchronized(lock) {
            room = r
            stateField = GravixPrejoinState.ATTACHED
            claimed = true
            shown = true
        }
        connectedDeferred.complete(true)
        watchRoom(r)
        mark(GravixJoinEventKind.ADOPTED)
    }

    // ------------------------------------------------------------------------------------------- marks and lines

    /** A timing event on this join's clock, and its step line. */
    internal fun mark(kind: GravixJoinEventKind, detail: String? = null, logDetail: String? = null) {
        val (step, at) = stepOf(kind)
        log(step, logDetail ?: logDetailOf(kind, detail), at)
        owner.emit(GravixJoinEvent(kind, request, owner.wallTime(), sinceTapMs(), detail))
    }

    /** One step line of this join, on its tap clock. */
    internal fun log(step: String, detail: String? = null, at: LoggingLevel = LoggingLevel.INFO) {
        if (!GravixJoinLog.writes(at)) return
        GravixJoinLog.step(step, key, sinceTapMs(), detail, at, owner.logSink)
    }

    private fun logDetailOf(kind: GravixJoinEventKind, detail: String?): String? = when (kind) {
        GravixJoinEventKind.TOKEN_READY -> detail?.let { "via=$it" }
        GravixJoinEventKind.ABANDONED -> detail?.let { "reason=$it" }
        GravixJoinEventKind.CONNECTED -> "in=${sinceTapMs() - connectStartMs}ms"
        else -> detail
    }

    private companion object {
        private const val UNWIND_CAP_MS = 5_000L

        @Suppress("CyclomaticComplexMethod") // a table
        fun stepOf(kind: GravixJoinEventKind): Pair<String, LoggingLevel> = when (kind) {
            GravixJoinEventKind.WARM -> "warmOk" to LoggingLevel.INFO
            GravixJoinEventKind.PREJOIN_START -> "handleStart" to LoggingLevel.VERBOSE
            GravixJoinEventKind.TOKEN_READY -> "tokenOk" to LoggingLevel.INFO
            GravixJoinEventKind.URL_CHOSEN -> "urlChosen" to LoggingLevel.INFO
            GravixJoinEventKind.CONNECT_START -> "connectStart" to LoggingLevel.INFO
            GravixJoinEventKind.CONNECTED -> "connectOk" to LoggingLevel.INFO
            GravixJoinEventKind.CONNECT_FAILED -> "connectFail" to LoggingLevel.INFO
            GravixJoinEventKind.ATTACHED -> "attached" to LoggingLevel.INFO
            GravixJoinEventKind.SHOWN -> "shown" to LoggingLevel.INFO
            GravixJoinEventKind.ABANDONED -> "abandon" to LoggingLevel.INFO
            GravixJoinEventKind.LEFT -> "left" to LoggingLevel.INFO
            GravixJoinEventKind.SWITCH_START -> "switchStart" to LoggingLevel.INFO
            GravixJoinEventKind.SWITCH_OLD_LEFT -> "switchOldLeft" to LoggingLevel.INFO
            GravixJoinEventKind.SWITCH_CONNECTED -> "switchConnected" to LoggingLevel.INFO
            GravixJoinEventKind.ADOPTED -> "adopt" to LoggingLevel.INFO
        }
    }
}
