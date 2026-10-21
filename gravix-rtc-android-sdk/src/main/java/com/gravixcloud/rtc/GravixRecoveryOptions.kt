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

package com.gravixcloud.rtc

/**
 * GRAVIX: how a room recovers from network and region failures (the Flutter SDK's `GravixRecoveryOptions`: its
 * region-failover part, the reconnect fixes and the 0.4.18 network-change fixes that apply to this engine). Every
 * field is a separate switch whose OFF value is exactly the upstream engine's behaviour ([networkChangeWindowMs],
 * [slowTcpRttMs] and [slowTcpForMs] are parameters of other switches); [UPSTREAM] turns all of them off at once.
 * Set as `RoomOptions(gravixRecovery = ...)` or `room.gravixRecovery = ...`.
 *
 * Which hosts each switch affects: [measuredRegionFailover], [lastGoodRegionList], [refusalEndsConnect] and the
 * region side of [failoverNeedsEvidence] act only where there is a region list, i.e. Gravix Cloud urls (`*.gravixcloud.com`,
 * `*.gravixcloud.run`, `/settings/regions`). [retrySameUrlFirst] (with its short dial,
 * [sameUrlRetryDialTimeoutMs]) and all the other switches ([ignoreStaleSignalFailures], [resumeOnPeerDisconnected],
 * the reconnect-wait and network switches, [resumeAfterNetworkSocketError], [freshPeerFailedGraceMs],
 * [iceTransportWatchMs]) apply to EVERY host, self-hosted servers included (as in the Flutter SDK).
 *
 * @property measuredRegionFailover region failover (a failed first connect, or a failed full reconnect) dials the
 *   regions in MEASURED order when the start-up measurement (`GravixRegions.startMeasurement`) is fresh: fastest
 *   `ok` first, regions slower than max(2 x best, best + 100 ms) of the best remaining candidate only when nothing
 *   near is left, unmeasured / failed ones after the measured ones; the url that just failed goes last. Off (or no
 *   fresh measurement): the server's `/settings/regions` order -- which could move a session to a distant region
 *   while a nearer one was up.
 * @property retrySameUrlFirst a reconnect whose resume failed makes its first FULL reconnect at the url the session
 *   is on (with [sameUrlRetryDialTimeoutMs]) before any other region. Upstream's first full reconnect already went to
 *   the next region in the list: a short path drop moved the session to another region for the rest of its life.
 * @property sameUrlRetryDialTimeoutMs the signal dial timeout (until the WebSocket opens; not the JoinResponse or
 *   peer-connection waits) of that same-url attempt. Null: the normal signal timeout.
 * @property failoverNeedsEvidence a full reconnect that failed at the session's url moves to another region only
 *   with evidence that the region failed: no connectivity change since the reconnect began, a network present, the
 *   session url not answering a probe (HTTP HEAD, 2 s) and the other region answering one. Otherwise the session url
 *   is retried. Off: any failure there moves the session (the device's own Wi-Fi going off could move it for good).
 * @property lastGoodRegionList a failed fetch of the region list (network error, timeout, 5xx) falls back to the
 *   last list the provider had (the connect-time fetch or a list the server sent), however old; when the list's host
 *   is the url that just failed (same host AND port), it is not asked at all. A 401/403 still ends the attempt (the token, not the
 *   region). Off: a failed fetch ends the failover (upstream).
 * @property refusalEndsConnect a FIRST connect the server refused (HTTP 401/403 from `/rtc/validate` or the upgrade:
 *   the token, not the region) fails at once instead of dialling the next region with the same token (every region
 *   would refuse it, each costing a dial). As the Flutter SDK (a NotAllowed connect error is not failed over). Only
 *   401/403: other answers (a 5xx) are the region's fault and still fail over. Off: upstream (any failure tries the
 *   next region).
 * @property resumeOnPeerDisconnected a connected session whose primary peer connection (or the publisher, when the
 *   subscriber is primary) reports DISCONNECTED starts its reconnect -- a resume first -- at once, as the Flutter
 *   SDK's engine does. Upstream waits for FAILED, which libwebrtc reports about 10 s later (continual gathering), or
 *   for the signal ping timeout: on the sim a paused or dropped region was noticed 16.5 s after the fault instead of
 *   6.5 s. The cost: a DISCONNECTED that would have recovered by itself gets a resume (an ICE restart). Applies to
 *   every host. Off: upstream.
 * @property resumePrimaryDeadlineMs a resume whose PRIMARY peer connection is still not connected this long after the
 *   ReconnectResponse becomes a full rejoin at once (the Flutter SDK's 3 s), instead of waiting the upstream 20 s
 *   for both connections: a resume whose ICE never completes kept the session without media for the whole wait.
 *   Null = off (upstream).
 * @property leaveAbortsResumeWait a server Leave (or the signal socket closing) while a resume waits for its peer
 *   connections ends the wait at once and the next attempt (a full rejoin) starts. Upstream drops the reconnect
 *   request the Leave makes ("already in progress") and the resume sits out its wait with no signal. Off: upstream.
 * @property restartAbortsOnSignalLoss a full reconnect whose own signal socket closes while it waits for its peer
 *   connections fails at once and the next attempt starts (a flapping network): no offer, answer or candidate can
 *   pass without the socket. Off: upstream (the 20 s wait runs out). Unlike the Flutter SDK, an attempt that failed
 *   this way still counts against the reconnect policy.
 * @property waitForNetwork while the device has no network (no active network), the reconnect loop waits for one
 *   before its next attempt (re-checking every 250 ms) and then attempts at once, without the back-off; the time
 *   still counts against the reconnect policy's budget. Upstream kept dialling offline: on the sim 5 s of airplane
 *   mode cost 9 failed attempts, spent the resume, and the session came back 6.7 s after the network with a FULL
 *   rejoin (the others saw it leave and join). A resume that failed as the network went away (before the reading
 *   changed) is tried again when the network is back. The Flutter SDK's engine waits for connectivity too. Off:
 *   upstream.
 * @property networkWaitProbeIntervalMs with [waitForNetwork]: while waiting, probe the session's url this often (one
 *   HTTP HEAD; any answer ends the wait): a stale reading, or a per-app network block lifted, sends no network event.
 *   Null = no probe.
 * @property rejoinClearsFullRequest a full-reconnect request the server made during a reconnect (Leave{RECONNECT}) is
 *   satisfied when that reconnect succeeds with a full rejoin. Upstream kept it, so the NEXT, unrelated drop skipped
 *   the resume and rejoined in full (the others saw a leave and a join). Part of the Flutter SDK's
 *   upstreamReconnectFixes. Off: upstream.
 * @property resumeWaitsForLiveState the reconnect's wait for its peer connections also waits for their LIVE state,
 *   within the same 20 s (and [resumePrimaryDeadlineMs]). Upstream waited on the connection observers only: when the
 *   connections had come back by themselves before a resume, the resume's ICE restart took the live connection to
 *   DISCONNECTED while its observer still said CONNECTED, the wait returned at once, the live check failed and the
 *   resume escalated to a full rejoin 80 ms later (sim: 2 of 6 short airplane-mode drops). Off: upstream.
 * @property signalDialCapMs the signal deadline (WebSocket dial + JoinResponse) of a FRESH join -- the first connect, a
 *   full reconnect, a region failover; not a resume, not the short same-url retry -- instead of upstream's 10 s. On
 *   the sim (uplink delay 2 s +-0.5 s, 35 % loss) 1 of 3 first joins failed at 10 s on a path that works, slowly.
 *   A host with no TCP connection still fails at the HTTP client's connect timeout (10 s by default). Unlike the
 *   Flutter SDK, no parallel connection races the first. Null = upstream (10 s).
 * @property silentHostFailFast a signal dial that failed before any connection came up (refused, unreachable) or
 *   timed out (connect, or the upgrade's answer: a hung server) is not followed by a `/rtc/validate` request to the
 *   same host, which would fail or time out the same way -- with [signalDialCapMs] before the join could fail (a
 *   paused host: 10 s read timeout + 10 s validate). A refusal (401/403) answers at once, so it is still told apart.
 *   Off: upstream (always validated).
 * @property ignoreStaleSignalFailures the signal client reports a WebSocket failure only for the current WebSocket.
 *   Upstream checks that before a blocking validate request (up to the HTTP timeout to a silent host) and not
 *   after it, so a failure of an earlier attempt could fail the next attempt's join, or close the session the
 *   client had just joined in another region (seen on the sim: a paused region's stale failure closed the
 *   failover's new session). Off: upstream.
 * @property resumeAfterNetworkSocketError a resume whose dial failed with a socket error (no answer from the server:
 *   the interface going away under it) while the device's network changed -- within [networkChangeWindowMs] before
 *   the reconnect began, or since -- or while the device reads offline, makes the NEXT attempt a resume again instead of
 *   a full rejoin (the server still holds the session for about 15-20 s). Two such failed resumes in a row still
 *   become a full rejoin. The Flutter SDK 0.4.18's switch of the same name; device run (Wi-Fi off 5 s): the resume
 *   dialled as the Wi-Fi went away failed, and the next attempt was a full rejoin started the instant the Wi-Fi came
 *   back (the others saw the host leave and join). A socket error on a steady network, a refusal and a timeout are a
 *   full rejoin as before. Applies to every host. Off: upstream (every attempt after the first is full, unless
 *   [waitForNetwork] waited for the network).
 * @property networkChangeWindowMs how long before a reconnect began a change of the device's network (a network
 *   lost or available, as `ConnectivityManager` reports it) still counts as "the network changed" for
 *   [resumeAfterNetworkSocketError] and [RoomEvent.ReconnectAttempt.afterNetworkChange][com.gravixcloud.rtc.events.RoomEvent.ReconnectAttempt.afterNetworkChange].
 *   The Flutter SDK's resumeReasonNetworkWindow (30 s); its other use there, the reason a resume sends, does not apply:
 *   this SDK sends no reconnect reason (the server reads RR_UNKNOWN and never takes a resume for "UDP blocked").
 * @property freshPeerFailedGraceMs a peer connection that has NEVER connected and reports FAILED gets this long to
 *   connect before the engine acts on the FAILED (the primary connection: the session's connection state; the
 *   publisher in subscriber-primary mode: a reconnect). Over ICE/TCP a new connection's first checks can fail at once
 *   (checking -> failed -> connected within ~0.1-1.7 s on a device). Without it a full rejoin's new connection that
 *   did so made the rejoin end with [RoomEvent.Connected][com.gravixcloud.rtc.events.RoomEvent.Connected] instead of [RoomEvent.Reconnected][com.gravixcloud.rtc.events.RoomEvent.Reconnected] (the app's "reconnecting"
 *   never ended with a reconnected), and a publisher that failed once on its first negotiation started a reconnect
 *   at once. A connection that was connected and fails is acted on at once, as before; a join or rejoin still times
 *   out as before. With it on, a rejoin's new primary connection that stayed down longer than the grace and then
 *   connected within the rejoin's wait also ends the rejoin with [RoomEvent.Reconnected][com.gravixcloud.rtc.events.RoomEvent.Reconnected]. The Flutter SDK 0.4.18's
 *   freshPeerFailedGrace (3 s). Null = off (upstream).
 * @property iceTransportWatchMs observation of how the session's media travels: [com.gravixcloud.rtc.room.Room.selectedTransport]
 *   (UDP / TCP / RELAY, from each peer connection's selected ICE candidate pair as libwebrtc reports it -- no polling)
 *   and [RoomEvent.IceTransportChanged][com.gravixcloud.rtc.events.RoomEvent.IceTransportChanged]; while either connection runs over
 *   ICE/TCP and the session is steady (connected, no resume or rejoin running), its round trip is read this often
 *   (one getStats per connected peer connection; never while the session is being recovered) for
 *   [RoomEvent.SlowTcpTransport][com.gravixcloud.rtc.events.RoomEvent.SlowTcpTransport]. The server prefers ICE/TCP for a participant after a failure it took
 *   for "UDP blocked"; on a lossy link such a session can run at seconds of round trip. Null = off (nothing is
 *   observed; the getters stay null).
 * @property slowTcpRttMs with [iceTransportWatchMs]: a TCP session whose selected pair's round trip stays above this
 *   for [slowTcpForMs] is reported once ([RoomEvent.SlowTcpTransport][com.gravixcloud.rtc.events.RoomEvent.SlowTcpTransport]),
 *   again after it came back below (1 s).
 * @property slowTcpForMs see [slowTcpRttMs] (10 s).
 * @property mediaRefusalFailover GRAVIX (2026-10-21, per-region media): when the server refuses a medium on this node
 *   (a signal request response carrying `media_not_allowed`, e.g. a video publish on an audio-only region), the
 *   session moves ONCE to the next region that allows it (a full reconnect; the region is noted as refusing that
 *   medium for 10 min) and the refused camera is published again there if the app had asked for it. A second refusal
 *   in the same session is left to the app; the session's join media becomes video for every later restart /
 *   failover. Off: the refusal steers nothing (no move, no region note, no media change). The Flutter SDK's
 *   GravixRecoveryOptions.mediaRefusalFailover.
 */
data class GravixRecoveryOptions @JvmOverloads constructor(
    val measuredRegionFailover: Boolean = true,
    val retrySameUrlFirst: Boolean = true,
    val sameUrlRetryDialTimeoutMs: Long? = DEFAULT_SAME_URL_RETRY_DIAL_TIMEOUT_MS,
    val failoverNeedsEvidence: Boolean = true,
    val lastGoodRegionList: Boolean = true,
    val ignoreStaleSignalFailures: Boolean = true,
    val refusalEndsConnect: Boolean = true,
    val resumeOnPeerDisconnected: Boolean = true,
    val resumePrimaryDeadlineMs: Long? = DEFAULT_RESUME_PRIMARY_DEADLINE_MS,
    val leaveAbortsResumeWait: Boolean = true,
    val restartAbortsOnSignalLoss: Boolean = true,
    val waitForNetwork: Boolean = true,
    val networkWaitProbeIntervalMs: Long? = DEFAULT_NETWORK_WAIT_PROBE_INTERVAL_MS,
    val rejoinClearsFullRequest: Boolean = true,
    val signalDialCapMs: Long? = DEFAULT_SIGNAL_DIAL_CAP_MS,
    val silentHostFailFast: Boolean = true,
    val resumeWaitsForLiveState: Boolean = true,
    val resumeAfterNetworkSocketError: Boolean = true,
    val networkChangeWindowMs: Long = DEFAULT_NETWORK_CHANGE_WINDOW_MS,
    val freshPeerFailedGraceMs: Long? = DEFAULT_FRESH_PEER_FAILED_GRACE_MS,
    val iceTransportWatchMs: Long? = DEFAULT_ICE_TRANSPORT_WATCH_MS,
    val slowTcpRttMs: Long = DEFAULT_SLOW_TCP_RTT_MS,
    val slowTcpForMs: Long = DEFAULT_SLOW_TCP_FOR_MS,
    val mediaRefusalFailover: Boolean = true,
) {
    /** True when any region switch is on (the engine then consults the Gravix region strategy). */
    val regionStrategyEnabled: Boolean
        get() = measuredRegionFailover || retrySameUrlFirst || failoverNeedsEvidence || lastGoodRegionList

    companion object {
        const val DEFAULT_SAME_URL_RETRY_DIAL_TIMEOUT_MS = 2500L
        const val DEFAULT_RESUME_PRIMARY_DEADLINE_MS = 3000L
        const val DEFAULT_NETWORK_WAIT_PROBE_INTERVAL_MS = 2000L
        const val DEFAULT_SIGNAL_DIAL_CAP_MS = 30_000L
        const val DEFAULT_NETWORK_CHANGE_WINDOW_MS = 30_000L
        const val DEFAULT_FRESH_PEER_FAILED_GRACE_MS = 3000L
        const val DEFAULT_ICE_TRANSPORT_WATCH_MS = 5000L
        const val DEFAULT_SLOW_TCP_RTT_MS = 1000L
        const val DEFAULT_SLOW_TCP_FOR_MS = 10_000L

        /** Everything off: the upstream engine's behaviour. */
        @JvmField
        val UPSTREAM = GravixRecoveryOptions(
            measuredRegionFailover = false,
            retrySameUrlFirst = false,
            sameUrlRetryDialTimeoutMs = null,
            failoverNeedsEvidence = false,
            lastGoodRegionList = false,
            ignoreStaleSignalFailures = false,
            refusalEndsConnect = false,
            resumeOnPeerDisconnected = false,
            resumePrimaryDeadlineMs = null,
            leaveAbortsResumeWait = false,
            restartAbortsOnSignalLoss = false,
            waitForNetwork = false,
            networkWaitProbeIntervalMs = null,
            rejoinClearsFullRequest = false,
            signalDialCapMs = null,
            silentHostFailFast = false,
            resumeWaitsForLiveState = false,
            resumeAfterNetworkSocketError = false,
            freshPeerFailedGraceMs = null,
            iceTransportWatchMs = null,
            mediaRefusalFailover = false,
        )
    }
}
