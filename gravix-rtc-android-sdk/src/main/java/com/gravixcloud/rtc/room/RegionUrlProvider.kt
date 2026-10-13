/*
 * Copyright 2024-2026 LiveKit, Inc.
 * Modifications Copyright 2026 Gravity Compile
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
import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.regions.GravixRegionFailoverOrder
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.executeAsync
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * @suppress
 */
class RegionUrlProvider
@AssistedInject
constructor(
    @Assisted val serverUrl: URI,
    @Assisted var token: String,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    private var regionSettings: RegionSettings? = null

    /** GRAVIX: the list this provider has (fetched or server-sent), to name the connected region. */
    internal val gravixRegionSettings: RegionSettings? get() = regionSettings
    private var lastUpdateAt: Long = 0L
    private var settingsCacheTimeMs = 30000
    private val attemptedRegions = mutableSetOf<RegionInfo>()

    fun isGravixCloud() = serverUrl.isGravixCloud()

    @Throws(RoomException.ConnectException::class)
    suspend fun getNextBestRegionUrl() = coroutineScope {
        if (!isGravixCloud()) {
            throw IllegalStateException("Region availability is only supported for Gravix Cloud domains")
        }
        if (regionSettings == null || SystemClock.elapsedRealtime() - lastUpdateAt > settingsCacheTimeMs) {
            fetchRegionSettings()
        }
        val regions = regionSettings?.regions ?: return@coroutineScope null
        val regionsLeft = regions.filter { region ->
            !attemptedRegions.any { attempted -> attempted.url == region.url }
        }

        if (regionsLeft.isEmpty()) {
            return@coroutineScope null
        }

        val nextRegion = regionsLeft.first()
        attemptedRegions.add(nextRegion)
        GxLog.d { "next region: $nextRegion" }
        return@coroutineScope nextRegion.url
    }

    /**
     * GRAVIX: the next region to try, for [GravixRegionFailover] (port of the Flutter SDK's provider). The
     * upstream [getNextBestRegionUrl] above is unchanged and is what the engine uses with every
     * GravixRecoveryOptions region switch off.
     *
     * - [measuredOrder] (measuredRegionFailover): the untried regions in the order of the fresh start-up
     *   measurement (nearest first, near-only cap, the server's order as the last resort); the entry for
     *   [currentUrl] (the url that just failed) goes last. Without a fresh measurement, the server's order.
     * - [lastGoodFallback] (lastGoodRegionList): a failed fetch of the list uses the list this provider already had
     *   instead of failing, unless it was refused (401/403). With [currentUrlFailed] and a kept list, a list whose
     *   host is [currentUrl]'s host is not asked at all (that host just failed; asking costs the fetch's timeout).
     */
    @Throws(RoomException.ConnectException::class)
    suspend fun getNextBestRegionUrl(
        currentUrl: String?,
        measuredOrder: Boolean,
        lastGoodFallback: Boolean,
        currentUrlFailed: Boolean,
    ): String? {
        if (!isGravixCloud()) {
            throw IllegalStateException("Region availability is only supported for Gravix Cloud domains")
        }
        val stale = regionSettings == null || SystemClock.elapsedRealtime() - lastUpdateAt > settingsCacheTimeMs
        val kept = regionSettings
        // the list's server (host AND port: another port on the same host is another server) just failed
        if (stale && lastGoodFallback && currentUrlFailed && kept != null && currentUrl != null &&
            gravixSameServer(currentUrl, serverUrl)
        ) {
            GxLog.i {
                "region list: not asking ${serverUrl.host}, its host just failed ($currentUrl); using the last good list " +
                    "(${kept.regions.size} regions, ${(SystemClock.elapsedRealtime() - lastUpdateAt) / 1000} s old)"
            }
        } else if (stale) {
            try {
                fetchRegionSettings()
            } catch (e: Exception) {
                e.rethrowIfCancellationSignal()
                val refused = gravixLastFetchStatus == 401 || gravixLastFetchStatus == 403
                val lastGood = regionSettings
                if (!lastGoodFallback || refused || lastGood == null) throw e
                GxLog.i {
                    "region list: ${serverUrl.host} failed ($e); using the last good list (${lastGood.regions.size} regions, " +
                        "${(SystemClock.elapsedRealtime() - lastUpdateAt) / 1000} s old)"
                }
            }
        }
        var regionsLeft: List<RegionInfo> = regionSettings?.regions.orEmpty().filter { region ->
            !attemptedRegions.any { attempted -> attempted.url == region.url }
        }
        if (measuredOrder) {
            val current = currentUrl?.let { GravixRegionFailoverOrder.normalizeUrl(it) }
            fun isCurrent(r: RegionInfo) = current != null && GravixRegionFailoverOrder.normalizeUrl(r.url) == current
            val others = regionsLeft.filter { !isCurrent(it) }
            regionsLeft = GravixRegionFailoverOrder.order(others, GravixRegionMeasurements.current(), { it.region }, { it.url }) +
                regionsLeft.filter { isCurrent(it) }
        }
        val nextRegion = regionsLeft.firstOrNull() ?: return null
        attemptedRegions.add(nextRegion)
        GxLog.d { "next region: $nextRegion" }
        return nextRegion.url
    }

    /** GRAVIX: the HTTP status of the last failed [fetchRegionSettings] (null when it got no answer). */
    @Volatile
    internal var gravixLastFetchStatus: Int? = null
        private set

    @Throws(RoomException.ConnectException::class)
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fetchRegionSettings(): RegionSettings? {
        gravixLastFetchStatus = null
        val request = Request.Builder()
            .url(serverUrl.getCloudConfigUrl("/regions").toString())
            .header("Authorization", "Bearer $token")
            .build()
        val bodyString = okHttpClient.newCall(request)
            .executeAsync()
            .use { response ->
                if (!response.isSuccessful) {
                    gravixLastFetchStatus = response.code // GRAVIX: a refusal is the token, not the region
                    throw RoomException.ConnectException("Could not fetch region settings: ${response.code} ${response.message}")
                }
                return@use response.body?.string()
            }

        if (bodyString == null) {
            throw RoomException.ConnectException("Could not fetch region settings: empty response body!")
        }

        return json.decodeFromString<RegionSettings>(bodyString).withoutRetiredServers().also {
            regionSettings = it
            lastUpdateAt = SystemClock.elapsedRealtime()
        }
    }

    fun clearAttemptedRegions() {
        attemptedRegions.clear()
    }

    fun setServerReportedRegions(regionSettings: RegionSettings) {
        this.regionSettings = regionSettings.withoutRetiredServers()
        this.lastUpdateAt = SystemClock.elapsedRealtime()
    }

    @AssistedFactory
    interface Factory {
        fun create(serverUrl: URI, token: String): RegionUrlProvider
    }

    internal companion object {
        /**
         * GRAVIX: whether [url] (ws/wss/http/https) names the same server as [server]: same host (case-insensitive)
         * and the same effective port (ws/http 80, wss/https 443 when none is given). False when either cannot be
         * parsed.
         */
        fun gravixSameServer(url: String, server: URI): Boolean {
            val a = runCatching { URI(url) }.getOrNull() ?: return false
            val hostA = a.host?.lowercase() ?: return false
            val hostB = server.host?.lowercase() ?: return false
            return hostA == hostB && a.gravixEffectivePort() == server.gravixEffectivePort()
        }

        private fun URI.gravixEffectivePort(): Int = when {
            port != -1 -> port
            scheme.equals("wss", true) || scheme.equals("https", true) -> 443
            else -> 80
        }
    }
}

/**
 * GRAVIX: region discovery and failover (RegionUrlProvider, `/settings/regions`) is enabled only for Gravix
 * Cloud hosts. Upstream recognised its own cloud suffixes instead, so a Gravix url never got failover.
 * The suffix match (`endsWith`, as upstream) is deliberately stricter than the Flutter SDK's `contains`:
 * a host such as `x.gravixcloud.com.example.net` is not treated as Gravix Cloud.
 */
internal fun URI.isGravixCloud() = regionUrlProviderTesting || host != null && GRAVIX_CLOUD_HOST_SUFFIXES.any { host.endsWith(it) }

internal val GRAVIX_CLOUD_HOST_SUFFIXES = listOf(".gravixcloud.com", ".gravixcloud.run")

/**
 * GRAVIX(retired servers, 2026-10-12): the retired Gravix deployment, the stock media server (Singapore and Bangalore)
 * and its token endpoint that ran before the Gravix Cloud fleet. Apps that still had it configured (as their server
 * url, or as a fallback when a token response had no `url`) joined a server with no region list and no failover,
 * outside the fleet; a "region list 404" report on 2026-10-08 came from that. Exact hosts only: a sub-domain or a
 * look-alike is not matched, and the fleet's region hosts (`<region>.gravixcloud.com`) are never in this set. Hostnames
 * only: the old servers' address is not listed (a wss url to a bare address fails TLS anyway).
 */
internal val GRAVIX_RETIRED_HOSTS = setOf("rtc.gravixcloud.com", "rtc-blr1.gravixcloud.com", "token.gravixcloud.com")

/**
 * GRAVIX: the retired host [url] names (lower case), or null. Takes ws/wss/http/https urls and a bare `host[:port]`;
 * the host is compared case-insensitively without a trailing dot. A url that cannot be parsed is not retired (the
 * connect then fails as it always did).
 */
internal fun retiredGravixServerHost(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "wss://$trimmed"
    val host = runCatching { URI(withScheme).host }.getOrNull() ?: return null
    val normalized = host.lowercase().removeSuffix(".")
    return normalized.takeIf { it in GRAVIX_RETIRED_HOSTS }
}

/** GRAVIX: throws [RetiredServerException] when [url] names a retired Gravix server. */
internal fun requireNotRetiredGravixServer(url: String) {
    retiredGravixServerHost(url)?.let { throw RetiredServerException(it) }
}

/**
 * GRAVIX: the list without retired Gravix servers. A region list is dialled by the engine's failover directly (not
 * through [Room.connect]), so a retired entry is left out here instead of being refused later.
 */
internal fun RegionSettings.withoutRetiredServers(): RegionSettings {
    val kept = regions.filter { retiredGravixServerHost(it.url) == null }
    if (kept.size == regions.size) return this
    GxLog.w { "region list: left out ${regions.size - kept.size} retired Gravix server(s)" }
    return copy(regions = kept)
}

internal fun URI.getCloudConfigUrl(appendPath: String = ""): URI {
    val scheme = if (this.scheme.startsWith("ws")) {
        this.scheme.replaceFirst("ws", "http")
    } else {
        this.scheme
    }
    return URI(
        scheme,
        null,
        this.host,
        this.port,
        "/settings$appendPath",
        null,
        null,
    )
}

private var regionUrlProviderTesting = false

@VisibleForTesting
fun setRegionUrlProviderTesting(enable: Boolean) {
    regionUrlProviderTesting = enable
}

/**
 * @suppress
 */
@Serializable
data class RegionSettings(val regions: List<RegionInfo>) {
    companion object {
        fun fromProto(proto: GravixcloudRtc.RegionSettings): RegionSettings {
            return RegionSettings(
                proto.regionsList.map { region ->
                    RegionInfo(region.region, region.url, region.distance)
                },
            )
        }
    }
}

/**
 * @suppress
 */
@Serializable
data class RegionInfo(val region: String, val url: String, val distance: Long)
