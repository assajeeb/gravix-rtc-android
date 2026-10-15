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

import com.gravixcloud.rtc.events.DisconnectReason
import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.LoggingLevel
import com.gravixcloud.rtc.util.TimeoutException
import com.gravixcloud.rtc.util.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import kotlin.time.Duration.Companion.milliseconds

/**
 * GRAVIX (0.1.2): your rejoin of a live room (a new join of the same session, e.g. `GravixJoin.prepare` + attach).
 * True when it connected. Kotlin: `GravixRejoinAction { myScreen.rejoin() }`; Java: [fromFuture].
 */
interface GravixRejoinAction {
    suspend fun rejoin(): Boolean

    companion object {
        @JvmSynthetic
        operator fun invoke(block: suspend () -> Boolean): GravixRejoinAction = object : GravixRejoinAction {
            override suspend fun rejoin(): Boolean = block()
        }

        @JvmStatic
        fun fromFuture(rejoin: Supplier<CompletableFuture<Boolean>>): GravixRejoinAction = object : GravixRejoinAction {
            override suspend fun rejoin(): Boolean = rejoin.get().awaitGravix() == true
        }
    }
}

/** GRAVIX (0.1.2): whether the live is on now (your room row, a host-is-live flag). Called on the policy's thread. */
fun interface GravixLiveCheck {
    fun isLiveOn(): Boolean
}

/**
 * GRAVIX (0.1.2): a viewer joins again when the live is back ([liveBack]), and after a session that ended by itself
 * while the live is on ([sessionEnded]): after 1 / 2 / 4 / 8 s, never after a kick, another device, a deleted room or
 * a refused join ([rejoinable]); at most 3 rejoins per incident (rejoins that each ended at once; an incident is over
 * once a rejoin stayed up 60 s) and 12 per live; an optional entry check ([Builder.setMayRejoin]) before each attempt.
 * Your app supplies [GravixLiveCheck] and the [GravixRejoinAction]. One per room screen; [release] it with the screen.
 *
 * Its decisions are `[GravixJoin]` step lines (rejoinScheduled, rejoinAttempt, rejoinOk, rejoinFail, rejoinRefused,
 * rejoinGaveUp, rejoinLiveBack).
 */
@Suppress("TooManyFunctions")
class GravixRejoinPolicy private constructor(builder: Builder) {
    private val rejoinAction: GravixRejoinAction = builder.rejoin
    private val liveOn: GravixLiveCheck = builder.liveOn
    private val mayRejoin: GravixRejoinAction? = builder.mayRejoin
    private val onGaveUp: Runnable? = builder.onGaveUp
    private val backoffMs: List<Long> = builder.backoffMs
    private val maxPerIncident: Int = builder.maxPerIncident
    private val stableAfterMs: Long = builder.stableAfterMs
    private val maxTotal: Int = builder.maxTotal
    private val mayRejoinTimeoutMs: Long = builder.mayRejoinTimeoutMs
    private val logSink: GravixLogSink? = builder.logSink
    private val now: () -> Long = builder.clock
    private val scope = CoroutineScope(SupervisorJob() + builder.dispatcher)
    private val lock = Any()

    private var timer: Job? = null
    private var attempt = 0
    private var totalAttempts = 0
    private var incidentRejoins = 0
    private var incidentGaveUp = false
    private var joinedAtMs = -1L
    private var refusedFlag = false
    private var running = false
    private var released = false
    private var ended = false

    /** A rejoin is scheduled or running. */
    val pending: Boolean
        get() = synchronized(lock) { timer != null || running }

    /** Attempts in the current incident. */
    val attempts: Int
        get() = synchronized(lock) { attempt }

    /** Attempts in this live. */
    val total: Int
        get() = synchronized(lock) { totalAttempts }

    /** The entry check said no: no more rejoins from this policy. */
    val refused: Boolean
        get() = synchronized(lock) { refusedFlag }

    private fun log(step: String, detail: String, at: LoggingLevel = LoggingLevel.INFO) =
        GravixJoinLog.step(step, null, null, detail, at, logSink)

    /** The live ended (the host left): nothing is scheduled until [liveBack]. */
    fun liveEnded() {
        synchronized(lock) {
            ended = true
            cancelLocked()
            attempt = 0
        }
    }

    /** The live is back after [liveEnded]: joins again now (counters reset). */
    fun liveBack() {
        synchronized(lock) {
            if (released || refusedFlag || !ended) return
            ended = false
            attempt = 0
            totalAttempts = 0
            resetIncidentLocked()
            log("rejoinLiveBack", "joining again")
            scheduleLocked(0, "liveBack")
        }
    }

    /**
     * The session ended by itself (the Room's `RoomEvent.Disconnected` reason; null: unknown). Schedules a rejoin when
     * [rejoinable], the live is on and the caps allow; true when one was scheduled.
     */
    fun sessionEnded(reason: DisconnectReason?): Boolean {
        var gaveUp = false
        val scheduled = synchronized(lock) {
            val why = when {
                released -> "released"
                refusedFlag -> "refused before"
                ended -> "live ended"
                timer != null || running -> "already pending"
                else -> null
            }
            if (why != null) {
                log("rejoinRefused", "why=$why", LoggingLevel.VERBOSE)
                return@synchronized false
            }
            if (!rejoinable(reason)) {
                log("rejoinRefused", "why=not after ${reason?.name?.lowercase()}")
                return@synchronized false
            }
            if (!liveOn.isLiveOn()) {
                log("rejoinRefused", "why=live is off")
                return@synchronized false
            }
            if (!incidentGaveUp && joinedAtMs >= 0 && now() - joinedAtMs >= stableAfterMs) resetIncidentLocked()
            if (incidentRejoins >= maxPerIncident) {
                if (!incidentGaveUp) {
                    incidentGaveUp = true
                    log("rejoinGaveUp", "why=$incidentRejoins rejoins each ended at once")
                    gaveUp = true
                }
                return@synchronized false
            }
            scheduleLocked(delayLocked(), reason?.name?.lowercase() ?: "unknown")
            true
        }
        if (gaveUp) onGaveUp?.run()
        return scheduled
    }

    /** Your own join of the session succeeded (resets the backoff). */
    fun joined() {
        synchronized(lock) { attempt = 0 }
    }

    /** Stops the policy (the screen closed). */
    fun release() {
        synchronized(lock) {
            released = true
            cancelLocked()
        }
        scope.cancel()
    }

    private fun resetIncidentLocked() {
        incidentRejoins = 0
        incidentGaveUp = false
        joinedAtMs = -1L
    }

    private fun delayLocked(): Long = backoffMs[if (attempt < backoffMs.size) attempt else backoffMs.size - 1]

    private fun scheduleLocked(delayMs: Long, after: String) {
        log("rejoinScheduled", "n=${attempt + 1} in=${delayMs}ms after=$after")
        cancelLocked()
        timer = scope.launch {
            delay(delayMs)
            run()
        }
    }

    private fun cancelLocked() {
        timer?.cancel()
        timer = null
    }

    private fun stillWanted(): Boolean = !released && !refusedFlag && !ended && liveOn.isLiveOn()

    @Suppress("SwallowedException", "CyclomaticComplexMethod") // a failed entry check joins anyway (logged)
    private suspend fun run() {
        var gaveUpWhy: String? = null
        synchronized(lock) {
            timer = null
            if (!stillWanted()) return
            if (totalAttempts >= maxTotal) {
                gaveUpWhy = "cap of $maxTotal attempts"
            } else {
                running = true
            }
        }
        gaveUpWhy?.let {
            log("rejoinGaveUp", "why=$it")
            onGaveUp?.run()
            return
        }
        mayRejoin?.let { check ->
            val allowed = try {
                withDeadline(mayRejoinTimeoutMs.milliseconds) { check.rejoin() }
            } catch (e: CancellationException) {
                synchronized(lock) { running = false }
                throw e
            } catch (e: TimeoutException) {
                log("rejoinCheck", "timed out, joining anyway")
                true
            } catch (e: Exception) {
                log("rejoinCheck", "failed (${e.javaClass.simpleName}), joining anyway")
                true
            }
            synchronized(lock) {
                if (!allowed) {
                    running = false
                    refusedFlag = true
                    cancelLocked()
                    log("rejoinRefused", "why=entry check")
                    return
                }
                if (released || ended || !liveOn.isLiveOn()) {
                    running = false
                    return
                }
            }
        }
        val n = synchronized(lock) {
            attempt++
            totalAttempts++
            log("rejoinAttempt", "n=$attempt total=$totalAttempts")
            attempt
        }
        var ok = false
        try {
            ok = rejoinAction.rejoin()
            log(if (ok) "rejoinOk" else "rejoinFail", if (ok) "n=$n" else "n=$n connected=false")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("rejoinFail", "n=$n err=${e.javaClass.simpleName}")
        } finally {
            synchronized(lock) { running = false }
        }
        var gaveUp = false
        synchronized(lock) {
            if (released || ended) return
            if (ok) {
                incidentRejoins++
                joinedAtMs = now()
                attempt = 0
                return
            }
            if (!liveOn.isLiveOn()) return
            if (attempt >= backoffMs.size) {
                log("rejoinGaveUp", "why=all $attempt attempts failed")
                gaveUp = true
            } else {
                scheduleLocked(delayLocked(), "retry")
            }
        }
        if (gaveUp) onGaveUp?.run()
    }

    /**
     * Builds a [GravixRejoinPolicy]: [rejoin] joins the session again, [liveOn] says whether the live is on.
     */
    class Builder(internal val rejoin: GravixRejoinAction, internal val liveOn: GravixLiveCheck) {
        internal var mayRejoin: GravixRejoinAction? = null
        internal var onGaveUp: Runnable? = null
        internal var backoffMs: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        internal var maxPerIncident = 3
        internal var stableAfterMs = 60_000L
        internal var maxTotal = 12
        internal var mayRejoinTimeoutMs = 3_000L
        internal var logSink: GravixLogSink? = null
        internal var dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
        internal var clock: () -> Long = System::currentTimeMillis

        /** An entry check before each attempt (e.g. "am I banned?"); false stops the policy, a failure or 3 s joins anyway. */
        fun setMayRejoin(check: GravixRejoinAction): Builder = apply { mayRejoin = check }

        /** Called when the policy gave up (show a Retry). */
        fun setOnGaveUp(callback: Runnable): Builder = apply { onGaveUp = callback }

        /** The delays before attempt 1, 2, ... (ms; the last one repeats; also the attempts per incident). */
        fun setBackoffMs(vararg delaysMs: Long): Builder = apply {
            require(delaysMs.isNotEmpty()) { "backoff must not be empty" }
            backoffMs = delaysMs.toList()
        }

        fun setMaxPerIncident(max: Int): Builder = apply { maxPerIncident = max.coerceAtLeast(1) }

        fun setStableAfterMs(ms: Long): Builder = apply { stableAfterMs = ms.coerceAtLeast(0) }

        fun setMaxTotal(max: Int): Builder = apply { maxTotal = max.coerceAtLeast(1) }

        fun setMayRejoinTimeoutMs(ms: Long): Builder = apply { mayRejoinTimeoutMs = ms.coerceAtLeast(1) }

        fun setLogSink(sink: GravixLogSink): Builder = apply { logSink = sink }

        internal fun setTestEnvironment(dispatcher: CoroutineDispatcher, clock: () -> Long): Builder = apply {
            this.dispatcher = dispatcher
            this.clock = clock
        }

        fun build(): GravixRejoinPolicy = GravixRejoinPolicy(this)
    }

    companion object {
        /** Whether a session that ended with [reason] may be joined again by the SDK's policy (null: unknown = yes). */
        @JvmStatic
        fun rejoinable(reason: DisconnectReason?): Boolean = when (reason) {
            null,
            DisconnectReason.UNKNOWN_REASON,
            DisconnectReason.SIGNAL_CLOSE,
            DisconnectReason.CONNECTION_TIMEOUT,
            DisconnectReason.MEDIA_FAILURE,
            DisconnectReason.STATE_MISMATCH,
            DisconnectReason.SERVER_SHUTDOWN,
            DisconnectReason.MIGRATION,
            DisconnectReason.ROOM_CLOSED,
            -> true

            DisconnectReason.CLIENT_INITIATED,
            DisconnectReason.PARTICIPANT_REMOVED,
            DisconnectReason.DUPLICATE_IDENTITY,
            DisconnectReason.ROOM_DELETED,
            DisconnectReason.JOIN_FAILURE,
            DisconnectReason.USER_UNAVAILABLE,
            DisconnectReason.USER_REJECTED,
            DisconnectReason.SIP_TRUNK_FAILURE,
            -> false
        }
    }
}
