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

package com.gravixcloud.rtc.room

import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.RTCStatsReport

/**
 * GRAVIX: how a peer connection's media travels, from its selected ICE candidate pair ([Room.selectedTransport]).
 * The Flutter SDK's `GravixIceTransport` (0.4.18).
 */
enum class GravixIceTransport {
    /** Direct UDP (host / srflx / prflx over UDP): the normal case. */
    UDP,

    /**
     * ICE over TCP (the server's TCP port). The server prefers it for a participant after a failure it took for "UDP
     * blocked"; on a lossy link its round trip can grow to seconds ([com.gravixcloud.rtc.events.RoomEvent.SlowTcpTransport]).
     */
    TCP,

    /** Through a TURN relay (whatever the protocol to the relay). */
    RELAY,
    ;

    internal companion object {
        /**
         * The transport of an ICE candidate line (`candidate:<foundation> <component> <protocol> <priority> <ip>
         * <port> typ <type> ...`); null when it cannot be read.
         */
        fun ofCandidateSdp(sdp: String?): GravixIceTransport? {
            if (sdp.isNullOrBlank()) return null
            val tokens = sdp.trim().removePrefix("a=").split(WHITESPACE)
            val typAt = tokens.indexOf("typ")
            if (tokens.size < 3 || typAt < 0 || typAt + 1 >= tokens.size) return null
            return of(type = tokens[typAt + 1], protocol = tokens[2])
        }

        fun of(type: String?, protocol: String?): GravixIceTransport = when {
            type.equals("relay", ignoreCase = true) -> RELAY
            protocol.equals("tcp", ignoreCase = true) -> TCP
            else -> UDP
        }

        /** The session's transport: TCP when either connection is on TCP, else RELAY when either is relayed. */
        fun session(a: GravixIceTransport?, b: GravixIceTransport?): GravixIceTransport? = when {
            a == TCP || b == TCP -> TCP
            a == RELAY || b == RELAY -> RELAY
            a == null && b == null -> null
            else -> UDP
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** GRAVIX: the selected candidate pair of a stats report (null fields: no selected pair yet / not reported). */
internal data class GravixSelectedPair(val transport: GravixIceTransport?, val rttMs: Long?) {
    companion object {
        val NONE = GravixSelectedPair(null, null)

        /** Reads the selected pair (transport stats' selectedCandidatePairId, else a selected/nominated pair). */
        fun of(report: RTCStatsReport?): GravixSelectedPair {
            val stats = report?.statsMap?.values ?: return NONE
            var selectedId: String? = null
            val pairs = HashMap<String, Map<String, Any?>>()
            val locals = HashMap<String, Map<String, Any?>>()
            for (s in stats) {
                when (s.type) {
                    "transport" -> (s.members["selectedCandidatePairId"] as? String)?.takeIf { it.isNotEmpty() }?.let { selectedId = it }
                    "candidate-pair" -> pairs[s.id] = s.members
                    "local-candidate" -> locals[s.id] = s.members
                }
            }
            val pair = selectedId?.let { pairs[it] }
                ?: pairs.values.firstOrNull { it["selected"] == true || (it["nominated"] == true && it["state"] == "succeeded") }
                ?: return NONE
            val local = locals[pair["localCandidateId"] as? String] ?: return NONE
            val transport = GravixIceTransport.of(local["candidateType"] as? String, local["protocol"] as? String)
            val rtt = (pair["currentRoundTripTime"] as? Number)?.let { Math.round(it.toDouble() * MS_PER_S) }
            return GravixSelectedPair(transport, rtt)
        }

        private const val MS_PER_S = 1000.0
    }
}

/**
 * GRAVIX (GravixRecoveryOptions.iceTransportWatchMs): keeps each peer connection's transport, from libwebrtc's
 * selected-candidate-pair callback ([onSelectedPair], no polling), reports changes, and only while either connection
 * runs over ICE/TCP reads the round trip every iceTransportWatchMs (no timer otherwise) ([readStats]: one getStats per connected connection) to
 * report a TCP round trip that stays above slowTcpRttMs for slowTcpForMs (once per episode). Owned by the engine.
 */
internal class GravixIceTransportWatch(
    /** iceTransportWatchMs, slowTcpRttMs, slowTcpForMs: read at each use (Room.gravixRecovery may change). */
    private val settings: () -> GravixRecoveryOptions,
    /** True while the session is steady (connected, no reconnect running): the round trip is read only then. */
    private val steady: () -> Boolean,
    /** The stats of the publisher / subscriber connection when it is connected, else null. */
    private val readStats: suspend (publisher: Boolean) -> RTCStatsReport?,
    private val now: () -> Long,
    private val onChanged: (transport: GravixIceTransport?, previous: GravixIceTransport?, publisher: GravixIceTransport?, subscriber: GravixIceTransport?) -> Unit,
    private val onSlowTcp: (rttMs: Long, overMs: Long) -> Unit,
) {
    @Volatile
    var publisher: GravixIceTransport? = null
        private set

    @Volatile
    var subscriber: GravixIceTransport? = null
        private set

    val transport: GravixIceTransport?
        get() = GravixIceTransport.session(publisher, subscriber)

    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var slowSince: Long? = null
    private var slowReported = false

    val enabled: Boolean
        get() = (settings().iceTransportWatchMs ?: 0L) > 0L

    /**
     * The selected pair of one connection changed (libwebrtc's onSelectedCandidatePairChanged). Ignored outside a
     * session (before [start], after [stop]): a callback still in flight when the session closed changes nothing.
     */
    fun onSelectedPair(isPublisher: Boolean, transport: GravixIceTransport?) {
        if (!enabled || transport == null) return
        update(if (isPublisher) transport else null, if (isPublisher) null else transport)
    }

    private fun update(pub: GravixIceTransport?, sub: GravixIceTransport?) {
        val change = synchronized(lock) {
            if (scope == null) return
            val before = this.transport
            val wasOnTcp = onTcp
            if (pub != null) publisher = pub
            if (sub != null) subscriber = sub
            if (wasOnTcp && !onTcp) {
                // the TCP episode is over: a later one starts from zero
                slowSince = null
                slowReported = false
            }
            val after = this.transport
            pollWhileOnTcpLocked()
            if (after != before) Triple(after, before, publisher to subscriber) else null
        } ?: return
        GxLog.i { "ICE transport: ${change.first} (publisher ${change.third.first}, subscriber ${change.third.second})" }
        onChanged(change.first, change.second, change.third.first, change.third.second)
    }

    private val onTcp: Boolean
        get() = publisher == GravixIceTransport.TCP || subscriber == GravixIceTransport.TCP

    /** The session's coroutine scope for the round-trip reading (which runs only while a connection is on TCP). */
    fun start(scope: CoroutineScope) {
        if (!enabled) return
        synchronized(lock) {
            this.scope = scope
            pollWhileOnTcpLocked()
        }
    }

    /**
     * Reads the round trip every iceTransportWatchMs while either connection is on TCP (no timer at all otherwise),
     * and only while the session is steady. The loop decides to end under [lock], so an update to TCP racing with its
     * end starts a new one. Called with [lock] held.
     */
    private fun pollWhileOnTcpLocked() {
        val scope = scope ?: return
        if (!enabled || !onTcp || job?.isActive == true) return
        job = scope.launch {
            while (isActive && pollTick()) {
                // pollTick waits one interval and reads when it may
            }
        }
    }

    /** One tick of the reading loop: false when the loop is to end (decided under [lock]: off, or no longer on TCP). */
    private suspend fun pollTick(): Boolean {
        val interval = settings().iceTransportWatchMs?.takeIf { it > 0 } ?: 0L
        val go = synchronized(lock) {
            (interval > 0 && onTcp).also { if (!it) job = null }
        }
        if (!go) return false
        delay(interval)
        if (!onTcp || !steady()) return true // no stats while (re)connecting
        try {
            read(timeoutMs = interval)
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            GxLog.d(e) { "ice transport: stats reading failed" }
        }
        return true
    }

    /**
     * One reading of both connections (also refreshes the transports from the stats). A connection whose stats do not
     * arrive within [timeoutMs] counts as unread (a lost libwebrtc callback must not wedge the watch).
     */
    internal suspend fun read(timeoutMs: Long) {
        val p = GravixSelectedPair.of(withTimeoutOrNull(timeoutMs) { readStats(true) })
        val s = GravixSelectedPair.of(withTimeoutOrNull(timeoutMs) { readStats(false) })
        update(p.transport, s.transport)
        checkSlow(p, s)
    }

    private fun checkSlow(p: GravixSelectedPair, s: GravixSelectedPair) {
        val worst = listOf(p, s).filter { it.transport == GravixIceTransport.TCP }.mapNotNull { it.rttMs }.maxOrNull() ?: -1L
        val report = synchronized(lock) {
            if (worst <= settings().slowTcpRttMs) {
                slowSince = null
                slowReported = false
                return
            }
            val t = now()
            val since = slowSince ?: t.also { slowSince = it }
            val over = t - since
            if (!slowReported && over >= settings().slowTcpForMs) {
                slowReported = true
                over
            } else {
                null
            }
        } ?: return
        GxLog.w { "ICE/TCP round trip $worst ms for $report ms" }
        onSlowTcp(worst, report)
    }

    /**
     * A full rejoin: a new session with new peer connections. Forgets the old transports and the slow-TCP episode and
     * stops the reading (the new connections' selected pairs start it again); the scope is kept. No event.
     */
    fun resetSession() {
        synchronized(lock) {
            job?.cancel()
            job = null
            publisher = null
            subscriber = null
            slowSince = null
            slowReported = false
        }
    }

    /** The session ended: forget everything (the getters return null) and ignore late callbacks. */
    fun stop() {
        synchronized(lock) {
            resetSession()
            scope = null
        }
    }
}
