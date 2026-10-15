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

import android.os.SystemClock
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.regions.GravixRegionFailoverOrder
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * GRAVIX: one reconnect attempt as the region strategy sees it.
 *
 * @property retry the attempt index of the engine's reconnect loop (>= 1; attempt 0 is the resume and always uses
 *   the session url).
 * @property full whether the attempt will be a full reconnect (a new session) rather than a resume.
 * @property sessionUrl the url the session was on when the reconnect began.
 * @property lastUrl the url of the previous attempt.
 * @property lastRefused the previous attempt was refused (HTTP 401/403: the token, not the region).
 * @property startedAtMs when the reconnect began (`SystemClock.elapsedRealtime`).
 */
internal data class GravixRegionAttempt(
    val retry: Int,
    val full: Boolean,
    val sessionUrl: String,
    val lastUrl: String,
    val lastRefused: Boolean,
    val startedAtMs: Long,
)

/**
 * GRAVIX: the engine hook. Consulted where the upstream engine asked `RegionUrlProvider.getNextBestRegionUrl()`
 * (the reconnect loop and the first connect). The engine uses none (exactly the upstream ordering) when
 * [GravixRecoveryOptions.regionStrategyEnabled] is false.
 */
internal interface GravixRegionStrategy {
    /** The url reconnect attempt [GravixRegionAttempt.retry] should use. */
    suspend fun reconnectUrl(attempt: GravixRegionAttempt): String

    /** The dial timeout of the attempt [reconnectUrl] just chose (null = the normal signal timeout). */
    val nextDialTimeoutMs: Long?

    /** A first connect to [failedUrl] failed: the url to try next, or null to give up. */
    suspend fun connectFailoverUrl(failedUrl: String, refused: Boolean): String?
}

/**
 * GRAVIX: what [GravixRegionFailover] needs to know about the device and the servers (replaceable in tests).
 */
internal interface GravixFailoverEvidence {
    /** True when the device's network changed (lost / available) at or after [sinceMs] (elapsedRealtime). */
    fun networkChangedSince(sinceMs: Long): Boolean

    /** True when the device has a network at all (an active network exists; an unknown answer is true). */
    fun networkAvailable(): Boolean

    /** One HTTP HEAD to [url]'s origin; any answer (even an error status) = reachable. */
    suspend fun reachable(url: String, timeoutMs: Long): Boolean
}

/**
 * GRAVIX: the region strategy behind [GravixRecoveryOptions] (port of the Flutter engine's restartConnection
 * failover, mapped onto this engine's reconnect loop):
 *
 * - attempt 0 (the resume) is not asked: it always uses the session url;
 * - [GravixRecoveryOptions.retrySameUrlFirst]: the first full attempt after a failure at the session url goes back
 *   to it once, with a short dial ([GravixRecoveryOptions.sameUrlRetryDialTimeoutMs]), unless it was refused;
 * - then the next region from [RegionUrlProvider], in measured order ([GravixRecoveryOptions.measuredRegionFailover])
 *   with the last good list on a failed fetch ([GravixRecoveryOptions.lastGoodRegionList]);
 * - [GravixRecoveryOptions.failoverNeedsEvidence]: leaving the session url needs evidence that its region failed;
 *   without it the session url is retried;
 * - no region left: the attempts are reset and the last url is kept (upstream keeps it too).
 *
 * One instance per reconnect (its same-url state belongs to that reconnect).
 */
internal class GravixRegionFailover(
    private val options: GravixRecoveryOptions,
    private val provider: RegionUrlProvider?,
    private val evidence: GravixFailoverEvidence,
) : GravixRegionStrategy {

    private var sameUrlRetried = false

    override var nextDialTimeoutMs: Long? = null
        private set

    private fun same(a: String, b: String) = GravixRegionFailoverOrder.normalizeUrl(a) == GravixRegionFailoverOrder.normalizeUrl(b)

    override suspend fun reconnectUrl(attempt: GravixRegionAttempt): String {
        nextDialTimeoutMs = null
        // a resume only makes sense where the session is
        if (!attempt.full) return attempt.sessionUrl
        val atSessionUrl = same(attempt.lastUrl, attempt.sessionUrl)
        if (options.retrySameUrlFirst && atSessionUrl && !sameUrlRetried && !attempt.lastRefused) {
            sameUrlRetried = true
            nextDialTimeoutMs = options.sameUrlRetryDialTimeoutMs
            GxLog.i { "reconnect at ${GravixStepLogs.host(attempt.sessionUrl)} failed; retrying it once (full) before another region" }
            return attempt.sessionUrl
        }
        val next = nextRegion(attempt.lastUrl, currentUrlFailed = !attempt.lastRefused)
        if (next == null) {
            // nowhere else to go: try the list again from the top on the next attempt
            provider?.clearAttemptedRegions()
            return attempt.lastUrl
        }
        if (atSessionUrl && options.failoverNeedsEvidence && !attempt.lastRefused &&
            !regionFailed(attempt.sessionUrl, next, attempt.startedAtMs)
        ) {
            GxLog.i {
                "reconnect at ${attempt.sessionUrl} failed, but nothing shows its region failed (the device network " +
                    "changed, or the server answers): staying on it"
            }
            provider?.clearAttemptedRegions()
            return attempt.sessionUrl
        }
        GxLog.i { "region failover: ${GravixStepLogs.host(attempt.lastUrl)} -> ${GravixStepLogs.host(next)}" }
        return next
    }

    override suspend fun connectFailoverUrl(failedUrl: String, refused: Boolean): String? =
        nextRegion(failedUrl, currentUrlFailed = !refused)

    private suspend fun nextRegion(currentUrl: String, currentUrlFailed: Boolean): String? {
        val p = provider ?: return null
        return try {
            p.getNextBestRegionUrl(
                currentUrl = currentUrl,
                measuredOrder = options.measuredRegionFailover,
                lastGoodFallback = options.lastGoodRegionList,
                currentUrlFailed = currentUrlFailed,
            )
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            GxLog.w(e) { "could not get the next region" }
            null
        }
    }

    /**
     * Evidence that [current] (the session's region) failed and [next] did not: the device's network steady since
     * the reconnect began and present, [current] not answering a probe, [next] answering one.
     */
    private suspend fun regionFailed(current: String, next: String, sinceMs: Long): Boolean {
        if (evidence.networkChangedSince(sinceMs)) return false
        if (!evidence.networkAvailable()) return false
        return coroutineScope {
            val cur = async { evidence.reachable(current, PROBE_TIMEOUT_MS) }
            val nxt = async { evidence.reachable(next, PROBE_TIMEOUT_MS) }
            !cur.await() && nxt.await()
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 2000L

        fun now(): Long = SystemClock.elapsedRealtime()
    }
}
