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

package com.gravixcloud.rtc.regions

import androidx.annotation.VisibleForTesting
import com.gravixcloud.rtc.util.executeAsync
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import com.gravixcloud.rtc.util.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

// GRAVIX: region choice at start-up, like Agora (port of the Flutter SDK's gravix_init_measure.dart, itself the
// React SDK's initMeasure.ts).
//
// The app starts the measurement once when it starts -- before any room or token exists. Each planned region gets
// up to SAMPLES requests on ONE keep-alive connection (GravixRegionProbeClient): the first opens it (DNS + TCP +
// TLS, dropped), the rest reuse it and each measures one round trip; the minimum of those is the region's RTT.
// Regions are measured in PARALLEL (at most MEASURE_CONCURRENCY), samples sequential within a region, stopped
// early once the best clearly wins, and bounded by a TOTAL budget (`probe_budget_ms`, default 1.5 s Wi-Fi / 3 s
// cellular). The answer is cached PER NETWORK; the region in use is an anchor a rival must beat twice in a row
// (GravixRegionSelector). Joins then use the measured region by a lookup ([GravixRegionMeasurements.pickRegion]),
// and region failover orders the server's region list by it (GravixRecoveryOptions.measuredRegionFailover).

/** One timed request to a region: the round trip in ms, or null when it failed. */
fun interface GravixRegionSampler {
    suspend fun sample(region: GravixRegionUrl): Long?
}

/** Makes the sampler for ONE region's samples, sending them on [client] (the region's keep-alive connection). */
fun interface GravixRegionSamplerFactory {
    fun create(client: GravixRegionProbeClient): GravixRegionSampler
}

/** Fetches the gateway's whole `GET /v1/regions` answer (regions, shortlist, budget, ttl). */
fun interface GravixRegionDirectoryFetcher {
    suspend fun fetch(regionsUrl: String): GravixRegionDirectory
}

/**
 * What to measure, and the replaceable steps. Java: `new GravixRegionMeasureOptions()` and the setters.
 *
 * @property regionsUrl the gateway's `GET /v1/regions` url (e.g. `https://<console host>/v1/regions`).
 * @property regionUrls the regions to measure, instead of fetching them.
 * @property shortlist with [regionUrls]: the region slugs to probe.
 * @property sampler used for every sample INSTEAD of a probe client (no connection reuse unless it keeps its own).
 * @property samplerFor makes each region's sampler on that region's probe client (default: [GravixRegionMeasurements.defaultSampler]).
 * @property store keeps the per-network answers (and, with [regionsUrl], the fetched list) across runs/launches.
 * @property budgetMs total time; default the gateway's `probe_budget_ms`, else 1.5 s (3 s on cellular).
 * @property currentRegion the region joins use now when the app holds one; default this network's cached answer.
 * @property networkKey the per-network cache key (default: the network type, `+vpn` while a VPN is up).
 * @property networkType the network type for the default budget.
 */
class GravixRegionMeasureOptions {
    var regionsUrl: String? = null
    var regionUrls: List<GravixRegionUrl>? = null
    var shortlist: List<String>? = null
    var sampler: GravixRegionSampler? = null
    var samplerFor: GravixRegionSamplerFactory? = null
    var fetchDirectory: GravixRegionDirectoryFetcher? = null
    var store: GravixRegionListStore? = null
    var budgetMs: Long? = null
    var currentRegion: String? = null
    var networkKey: GravixNetworkReader? = null
    var networkType: GravixNetworkReader? = null

    /** OkHttp client the probe clients and the list fetch are derived from (each region gets its own pool). */
    var okHttpClient: OkHttpClient? = null

    /** Where the run's workers execute; default a single-threaded view of [Dispatchers.Default]. */
    var dispatcher: CoroutineDispatcher? = null

    fun copy(): GravixRegionMeasureOptions = GravixRegionMeasureOptions().also {
        it.regionsUrl = regionsUrl
        it.regionUrls = regionUrls
        it.shortlist = shortlist
        it.sampler = sampler
        it.samplerFor = samplerFor
        it.fetchDirectory = fetchDirectory
        it.store = store
        it.budgetMs = budgetMs
        it.currentRegion = currentRegion
        it.networkKey = networkKey
        it.networkType = networkType
        it.okHttpClient = okHttpClient
        it.dispatcher = dispatcher
    }
}

/** Why [GravixRegionMeasurements.pickRegion] chose its region. */
enum class GravixRegionPickReason(val wireName: String) {
    /** The measured best region (or the fastest the room offers). */
    MEASURED("measured"),

    /** The pinned url, kept because the pick was not clearly faster. */
    PINNED("pinned"),

    /** The room's home region, within the home margin of the fastest. */
    HOME("home"),

    /**
     * GRAVIX (2026-10-21): no measured region the room offers allows the join's media and the pinned url's region
     * does not either: the first shortlist region allowing it (else the lowest `est_rtt_ms`, else the first offered).
     */
    FALLBACK("fallback"),
}

/**
 * A [GravixRegionMeasurements.pickRegion] answer: the region, why, and what the media filter did.
 *
 * @property media the join's media (null: no filter).
 * @property skipped measured regions the room offers that were passed over because they do not allow [media],
 *   fastest first.
 * @property mediaIgnored no offered region allows [media]: the pick ignored it, so the join still has a region.
 */
data class GravixRegionPick @JvmOverloads constructor(
    val entry: GravixRegionUrl,
    val reason: GravixRegionPickReason,
    val media: GravixRoomMedia? = null,
    val skipped: List<String> = emptyList(),
    val mediaIgnored: Boolean = false,
)

/** The process-wide start-up region measurement: the runner, the held answer and the join-time lookups. */
object GravixRegionMeasurements {
    /** Requests per region: one cold (dropped) + up to two warm. */
    const val SAMPLES = 3
    const val TTL_MS = 10L * 60 * 1000
    const val REFRESH_MS = 5L * 60 * 1000

    /** Per-request ceiling of the default sampler (each request is also cut when the run's budget ends). */
    const val MEASURE_TIMEOUT_MS = 1500L

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var held: GravixRegionMeasurementResult? = null
    private val memoryStates = ConcurrentHashMap<String, NetworkState>()
    private val defaultListStore = GravixMemoryRegionListStore()
    private val defaultHttp: OkHttpClient by lazy { OkHttpClient() }

    /** Time source; replaced by tests (virtual time). */
    @Volatile
    @VisibleForTesting
    var clock: GravixClock = GravixClock.SYSTEM

    /** The current answer, when fresh (within its TTL at [nowMs], wall clock). */
    @JvmStatic
    @JvmOverloads
    fun current(nowMs: Long = clock.wallMs()): GravixRegionMeasurementResult? {
        val h = held ?: return null
        return if (nowMs - h.measuredAtMs > h.ttlMs) null else h
    }

    /** Forgets the held answer and the per-network states of this process (tests). */
    @JvmStatic
    @VisibleForTesting
    fun clear() {
        held = null
        memoryStates.clear()
        GravixRegionMedia.clear()
    }

    /** Makes [result] the held answer (tests, and apps that measured on their own). */
    @JvmStatic
    @VisibleForTesting
    fun setCurrent(result: GravixRegionMeasurementResult?) {
        held = result
    }

    /**
     * The default sampler: one request on [client], timed; null when it failed, timed out ([timeoutMs]) or the
     * probe endpoint named another region (a misrouted region never wins).
     */
    @JvmStatic
    @JvmOverloads
    fun defaultSampler(client: GravixRegionProbeClient, timeoutMs: Long = MEASURE_TIMEOUT_MS): GravixRegionSampler =
        GravixRegionSampler { r ->
            val start = System.nanoTime()
            try {
                // null: timed out; false: the probe endpoint named another region
                withTimeoutOrNull(timeoutMs) {
                    val probeUrl = r.probeUrl
                    if (probeUrl != null) {
                        val named = client.verifiedProbe(probeUrl)
                        if (named != null && named != r.region) return@withTimeoutOrNull false
                    } else {
                        client.probe(r.url)
                    }
                    true
                }?.let { ok -> if (ok) (System.nanoTime() - start) / 1_000_000 else null }
            } catch (e: Exception) {
                e.rethrowIfCancellationSignal()
                null
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun defaultFetchDirectory(http: OkHttpClient, regionsUrl: String): GravixRegionDirectory =
        // withDeadline, not withTimeout: a timeout must be an ordinary failure (the saved-list fallback), not a
        // CancellationException that looks like the caller's cancellation
        withDeadline(5_000.milliseconds) {
            http.newCall(Request.Builder().url(regionsUrl).get().build()).executeAsync().use { response ->
                if (response.code == 404) return@use GravixRegionDirectory(emptyList()) // no list configured
                if (response.code !in 200..299) throw IOException("region list answered ${response.code}")
                GravixRegionDirectory.fromJson(json.parseToJsonElement(response.body?.string().orEmpty()))
            }
        }

    private class Track(val entry: GravixRegionUrl, val source: GravixRegionCandidateSource) {
        val samples = ArrayList<Long>()
        var failed = false
        var done = false
        var retried = false
        var client: GravixRegionProbeClient? = null
    }

    /**
     * Measures the planned regions in parallel within the total budget and remembers the answer for connects and
     * failover. Never throws (except for cancellation of the caller). A run with no list, or in which no region
     * answered, keeps a fresh MEASURED answer for this network until its TTL; a gateway guess is held only when
     * there is none.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun measure(options: GravixRegionMeasureOptions): GravixRegionMeasurementResult {
        val dispatcher = options.dispatcher ?: Dispatchers.Default.limitedParallelism(1)
        return withContext(dispatcher) { measureImpl(options) }
    }

    private suspend fun measureImpl(o: GravixRegionMeasureOptions): GravixRegionMeasurementResult {
        val clock = this.clock
        val http = o.okHttpClient ?: defaultHttp
        val netType = try {
            o.networkType?.read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: "unknown"
        val rawKey = try {
            (o.networkKey ?: o.networkType)?.read()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val netKey = if (rawKey.isNullOrEmpty()) netType else rawKey

        val listed: GravixRegionDirectory
        try {
            val regionUrls = o.regionUrls
            val regionsUrl = o.regionsUrl
            listed = if (regionUrls != null) {
                GravixRegionDirectory(regions = regionUrls, shortlist = o.shortlist)
            } else if (regionsUrl != null) {
                val st = o.store ?: defaultListStore
                val key = "gravix.regions:$regionsUrl"
                try {
                    val fetched = o.fetchDirectory?.fetch(regionsUrl) ?: defaultFetchDirectory(http, regionsUrl)
                    // the whole answer (shortlist, budget, ttl), so an offline start plans the same way
                    if (fetched.regions.isNotEmpty()) st.write(key, fetched.toJson().toString())
                    fetched
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // gateway unreachable: the list this device saw last; never on the join path
                    val saved = st.read(key)?.let { GravixRegionDirectory.fromJson(json.parseToJsonElement(it)) }
                    if (saved == null || saved.regions.isEmpty()) throw e
                    saved
                }
            } else {
                throw IllegalArgumentException("GravixRegionMeasurements.measure needs regionUrls or regionsUrl")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.toString()
            return GravixRegionMeasurementResult(measuredAtMs = clock.wallMs(), regions = emptyList(), error = msg.take(200))
        }
        // GRAVIX(retired servers): a retired Gravix server is never probed or picked, whoever listed it
        val dir = listed.withoutRetiredServers()
        // GRAVIX (2026-10-21): what each region allows (`media`), for the join's media filter: a lookup there, never
        // a fetch (GravixRegionMedia)
        GravixRegionMedia.noteDirectory(dir.regions, dir.shortlist)
        val mediaLimited = dir.regions.filter { it.media != null }.map { "${it.region}(${it.media!!.joinToString("+")})" }
        if (mediaLimited.isNotEmpty()) GravixRegionLog.step("directory", "media-limited ${mediaLimited.joinToString(" ")}")

        val sKey = "gravix.regionState:${o.regionsUrl ?: "app"}:$netKey"
        val state = memoryStates[sKey] ?: o.store?.read(sKey)?.let { NetworkState.decode(it) }
        val ttl = dir.shortlistTtlMs ?: TTL_MS
        val stateFresh = state != null && state.freshAt(clock.wallMs())

        // never block a join on this run: a fresh answer for THIS network is used now
        val heldNow = current()
        if (stateFresh && (heldNow == null || heldNow.networkKey != netKey)) {
            resultFromState(state!!, netKey)?.let { held = it }
        }
        val heldSame = current()
        // the anchor: the region joins use now on this network (kept for hours, not just while fresh)
        val liveAnchor = state?.liveAnchor(clock.wallMs())
        val currentRegion = o.currentRegion
            ?: liveAnchor?.region
            ?: (if (stateFresh) state?.best?.region else null)
            ?: (if (heldSame?.networkKey == netKey && heldSame.best?.ok == true) heldSame.best.region else null)

        val plan = GravixRegionShortlist.plan(
            dir,
            state = state?.let { GravixRegionPlanState(it.known, it.cursor) },
            currentRegion = currentRegion,
        )
        // on cellular the SDK's 3 s is a FLOOR under the gateway's budget (the console answers 1.5 s for everyone)
        val runBudget = o.budgetMs ?: if (netType == "cellular") {
            maxOf(dir.probeBudgetMs ?: 0L, GravixRegionPlanning.MEASURE_BUDGET_CELLULAR_MS)
        } else {
            dir.probeBudgetMs ?: GravixRegionPlanning.MEASURE_BUDGET_MS
        }

        val tracks = plan.candidates.map { Track(it.entry, it.source) }
        val startedAt = clock.elapsedMs()
        fun progress() = tracks.map { t -> GravixMeasureProgress(warm = t.samples.drop(1), done = t.done || t.failed) }

        val why: String = withTimeoutOrNull(runBudget) {
            coroutineScope {
                val end = CompletableDeferred<String>()
                fun finish(reason: String) {
                    end.complete(reason)
                }

                suspend fun measureOne(t: Track) {
                    val client = if (o.sampler == null) GravixRegionProbeClient(http) else null
                    t.client = client
                    try {
                        val sample = o.sampler ?: (o.samplerFor ?: GravixRegionSamplerFactory { defaultSampler(it) }).create(client!!)
                        var i = 0
                        while (i < SAMPLES && !end.isCompleted) {
                            // the budget is spent: END the run as a budget stop (the Flutter SDK's flaky "50 regions"
                            // case: workers skipping their regions must not end the run as 'complete')
                            if (clock.elapsedMs() - startedAt >= runBudget) {
                                finish("budget")
                                return
                            }
                            val d = try {
                                sample.sample(t.entry)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            if (end.isCompleted) return // an answer after the end is not part of this run
                            if (d == null) {
                                if (t.samples.isEmpty() && !t.retried) {
                                    // a lost SYN or TLS flight on a lossy link; the retry starts with DNS cached
                                    t.retried = true
                                    continue
                                }
                                t.failed = true
                                break
                            }
                            t.samples.add(d)
                            if (t.samples.size >= 2 && GravixRegionShortlist.shouldStopMeasuring(progress())) {
                                finish("early")
                                return
                            }
                            i++
                        }
                        t.done = true
                        if (GravixRegionShortlist.shouldStopMeasuring(progress())) finish("early")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        t.failed = true
                    }
                }

                var next = 0
                val workers = (0 until minOf(tracks.size, GravixRegionPlanning.MEASURE_CONCURRENCY)).map {
                    launch {
                        while (!end.isCompleted && next < tracks.size) measureOne(tracks[next++])
                    }
                }
                launch {
                    workers.joinAll()
                    finish("complete")
                }
                val reason = end.await()
                coroutineContext.cancelChildren() // aborts samples still in flight
                reason
            }
        } ?: "budget"
        val elapsed = clock.elapsedMs() - startedAt
        tracks.forEach { it.client?.close() }

        val measured = tracks.map { t ->
            val warm = t.samples.drop(1)
            val status = when {
                warm.isNotEmpty() -> GravixRegionMeasurementStatus.OK
                t.failed -> GravixRegionMeasurementStatus.FAILED
                else -> GravixRegionMeasurementStatus.UNMEASURED
            }
            GravixRegionMeasurement(
                entry = t.entry,
                rttMs = warm.minOrNull(),
                samplesMs = t.samples.toList(),
                connections = t.client?.connections,
                status = status,
                source = t.source,
                budgetHit = why == "budget" && !t.done && !t.failed,
                retried = t.retried,
            )
        }.sortedWith { a, b ->
            if (a.status != b.status) {
                a.status.ordinal.compareTo(b.status.ordinal)
            } else if (!a.ok) {
                0
            } else {
                a.rttMs!!.compareTo(b.rttMs!!)
            }
        }

        // the last-known RTT of the anchor, for keeping it through a run in which it got no answer in the budget
        fun lastKnown(region: String): RegionRtt? =
            liveAnchor?.takeIf { it.region == region }
                ?: state?.regions?.firstOrNull { it.region == region }
                ?: state?.best?.takeIf { it.region == region }
        val anchorLast = currentRegion?.let { lastKnown(it) }
        val selection = GravixRegionSelector.select(
            measured.map { GravixRegionSample(it.region, it.status.wireName, it.rttMs) },
            anchor = currentRegion,
            // the votes belong to the anchor they were cast against
            challenger = if (currentRegion != null && (liveAnchor == null || currentRegion == liveAnchor.region)) state?.challenger else null,
            anchorKnown = anchorLast != null,
        )
        var best: GravixRegionMeasurement? = null
        var bestSource: GravixRegionBestSource? = null
        if (selection != null) {
            val m = measured.first { it.region == selection.region }
            best = if (m.ok) {
                m
            } else {
                // `confirming` with an unmeasured anchor: its last-known number stands in
                m.copy(rttMs = anchorLast!!.rttMs, status = GravixRegionMeasurementStatus.OK, source = GravixRegionCandidateSource.CACHE)
            }
            bestSource = if (selection.reason == GravixRegionSelectReason.KEPT || selection.reason == GravixRegionSelectReason.CONFIRMING) {
                GravixRegionBestSource.KEPT
            } else {
                GravixRegionBestSource.MEASURED
            }
        } else {
            GravixRegionShortlist.fallbackRegion(dir)?.let { fb ->
                best = measured.firstOrNull { it.region == fb.entry.region } ?: GravixRegionMeasurement(
                    entry = fb.entry,
                    rttMs = null,
                    samplesMs = emptyList(),
                    status = GravixRegionMeasurementStatus.UNMEASURED,
                    source = fb.source,
                )
                bestSource = if (fb.source == GravixRegionCandidateSource.SHORTLIST) GravixRegionBestSource.SHORTLIST else GravixRegionBestSource.EST
            }
        }

        // GRAVIX (2026-10-21, per-region media): for each medium some measured region does not take, the same
        // hysteresis over the regions that do, with its own anchor, so joins of that medium do not flip between two
        // close regions either
        val media = selectPerMedium(measured, state)

        val now = clock.wallMs()
        val result = GravixRegionMeasurementResult(
            measuredAtMs = now,
            regions = measured,
            best = best,
            bestSource = bestSource,
            elapsedMs = elapsed,
            mode = plan.mode,
            budgetMs = runBudget,
            budgetHit = why == "budget",
            earlyExit = why == "early",
            networkKey = netKey,
            ttlMs = ttl,
            selectReason = selection?.reason,
            challenger = selection?.challenger,
            bestByMedia = media.bestByMedia,
            mediaSelectReason = media.reasons,
            mediaChallenger = media.challengers,
        )

        val measuredBest = best?.takeIf { it.ok }
        val okRtts = measured.filter { it.ok }.map { RegionRtt(it.region, it.url, it.rttMs!!) }
        val newState = NetworkState(
            known = GravixRegionShortlist.nextKnown(
                measured.filter { it.ok }.map { it.region },
                measured.map { it.region },
                state?.known.orEmpty(),
            ),
            cursor = plan.cursor,
            measuredAtMs = if (measuredBest != null) now else (state?.measuredAtMs ?: 0L),
            ttlMs = if (measuredBest != null) ttl else (state?.ttlMs ?: ttl),
            best = measuredBest?.let { RegionRtt(it.region, it.url, it.rttMs!!) } ?: (if (stateFresh) state?.best else null),
            regions = if (measuredBest != null) okRtts else (if (stateFresh) state?.regions.orEmpty() else emptyList()),
            // a run with no measured region keeps the anchor (and its age) as it was
            anchor = measuredBest?.let { RegionRtt(it.region, it.url, it.rttMs!!) } ?: liveAnchor,
            anchorAtMs = if (measuredBest != null) now else (if (liveAnchor != null) state?.anchorAtMs else null),
            challenger = if (selection != null) selection.challenger else state?.challenger,
            mediaAnchors = media.anchorsNext,
            mediaChallengers = media.challengersNext,
        )
        memoryStates[sKey] = newState
        try {
            o.store?.write(sKey, newState.encode())
        } catch (_: Exception) {
            // the memory copy still serves this process
        }

        val currentHeld = current()
        val currentIsMeasured = currentHeld?.networkKey == netKey && currentHeld.best?.ok == true
        if (best?.ok == true) {
            held = result
        } else if (!currentIsMeasured) {
            // a guess (or nothing) for this network: never over a fresh measured answer
            held = if (best != null) result else (if (currentHeld?.networkKey == netKey) currentHeld else null)
        }
        return result
    }

    private class MediaSelection(
        val bestByMedia: Map<GravixRoomMedia, GravixRegionMeasurement>,
        val reasons: Map<GravixRoomMedia, GravixRegionSelectReason>,
        val challengers: Map<GravixRoomMedia, GravixRegionChallenger>,
        val anchorsNext: Map<GravixRoomMedia, MediaAnchor>,
        val challengersNext: Map<GravixRoomMedia, GravixRegionChallenger>,
    )

    /** The per-medium selection of one run (see the call in [measureImpl]). */
    private fun selectPerMedium(measured: List<GravixRegionMeasurement>, state: NetworkState?): MediaSelection {
        val nowAtStart = clock.wallMs()
        val bestByMedia = LinkedHashMap<GravixRoomMedia, GravixRegionMeasurement>()
        val reasons = LinkedHashMap<GravixRoomMedia, GravixRegionSelectReason>()
        val challengers = LinkedHashMap<GravixRoomMedia, GravixRegionChallenger>()
        val anchorsNext = LinkedHashMap(state?.mediaAnchors.orEmpty())
        val challengersNext = LinkedHashMap(state?.mediaChallengers.orEmpty())
        for (media in GravixRoomMedia.entries) {
            val takes = measured.filter { GravixRegionMedia.regionAllows(it.entry, media) }
            if (takes.size == measured.size || takes.isEmpty()) continue
            val mAnchor = state?.liveMediaAnchor(media, nowAtStart)
            val sel = GravixRegionSelector.select(
                takes.map { GravixRegionSample(it.region, it.status.wireName, it.rttMs) },
                anchor = mAnchor?.region,
                challenger = state?.mediaChallengers?.get(media),
                anchorKnown = mAnchor != null,
            ) ?: continue // no evidence: the anchor and its votes stay
            val m = takes.first { it.region == sel.region }
            val chosen = if (m.ok) {
                m
            } else {
                m.copy(rttMs = mAnchor!!.rttMs, status = GravixRegionMeasurementStatus.OK, source = GravixRegionCandidateSource.CACHE)
            }
            bestByMedia[media] = chosen
            reasons[media] = sel.reason
            val ch = sel.challenger
            if (ch != null) {
                challengers[media] = ch
                challengersNext[media] = ch
            } else {
                challengersNext.remove(media)
            }
            anchorsNext[media] = MediaAnchor(RegionRtt(chosen.region, chosen.url, chosen.rttMs!!), clock.wallMs())
            GravixRegionLog.step(
                "select",
                "media=${media.wireName} -> ${chosen.region} rtt=${chosen.rttMs} reason=${sel.reason.wireName}" +
                    (mAnchor?.let { " anchor=${it.region}" } ?: "") +
                    (ch?.let { " challenger=${it.region}x${it.wins}" } ?: ""),
            )
        }
        return MediaSelection(bestByMedia, reasons, challengers, anchorsNext, challengersNext)
    }

    private fun resultFromState(s: NetworkState, networkKey: String): GravixRegionMeasurementResult? {
        val b = s.best ?: return null
        fun toM(r: RegionRtt) = GravixRegionMeasurement(
            entry = GravixRegionUrl(r.region, r.url),
            rttMs = r.rttMs,
            samplesMs = emptyList(),
            status = GravixRegionMeasurementStatus.OK,
            source = GravixRegionCandidateSource.CACHE,
        )
        val m = toM(b)
        // every region measured last time (fastest first), best first: the join's pinned / home rules need them
        val others = s.regions.filter { it.region != b.region }.map { toM(it) }
        return GravixRegionMeasurementResult(
            measuredAtMs = s.measuredAtMs,
            regions = listOf(m) + others,
            best = m,
            bestSource = GravixRegionBestSource.CACHE,
            networkKey = networkKey,
            ttlMs = s.ttlMs,
            bestByMedia = s.mediaAnchors.mapValues { (_, a) -> toM(a.rtt) },
        )
    }

    /**
     * The join path's choice: the measured best region if [candidates] offers it, else the fastest measured region
     * it does offer. A lookup, never a probe. Null -- connect as before -- when nothing fresh is measured or no
     * measured region is offered. Matched by url, else by region.
     *
     * The pinned url (the token's region: the app's own choice) is kept unless the pick is clearly faster (more
     * than max(30 ms, 20 %) lower). [homeRegion] (a viewer joining someone else's room) wins when it is offered,
     * measured `ok` and within max(25 ms, 30 %) of the fastest offered region.
     *
     * [media] (2026-10-21, [GravixRegionMedia]), with one `[GravixRegion] pick` line when given:
     *  1. the candidates that allow it are the offer (entry `media`, else the last `/v1/regions` answer, else
     *     allowed; a region that refused it lately is not) -- none does: every candidate,
     *     [GravixRegionPick.mediaIgnored];
     *  2. the measured pick, the pinned rule and the home rule run on that offer;
     *  3. nothing measured on it: the pinned url stays (null, as before) when its region allows [media] or is
     *     unknown; else the first shortlist region allowing it, the lowest `est_rtt_ms` allowing it, or the first
     *     offered one ([GravixRegionPickReason.FALLBACK]).
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun pickRegion(
        pinnedUrl: String,
        candidates: List<GravixRegionUrl>,
        homeRegion: String? = null,
        measurement: GravixRegionMeasurementResult? = current(),
        media: GravixRoomMedia? = null,
    ): GravixRegionPick? {
        if (candidates.isEmpty()) return null
        val m = measurement
        val allowed = if (media == null) candidates else candidates.filter { GravixRegionMedia.regionAllows(it, media) }
        val ignored = media != null && allowed.isEmpty()
        val offer = if (ignored) candidates else allowed
        val skipped = ArrayList<String>()
        val skipWhy = ArrayList<String>()
        if (m != null && media != null && !ignored) {
            val best = m.bestFor(media)
            val order = listOfNotNull(best) + m.regions.filter { it.region != best?.region }
            for (r in order) {
                if (!r.ok || r.region in skipped) continue
                val c = candidates.firstOrNull { it.url == r.url } ?: candidates.firstOrNull { it.region == r.region }
                if (c != null && c !in offer) {
                    skipped.add(r.region)
                    skipWhy.add("${r.region}(${GravixRegionMedia.skipWhy(c, media)})")
                }
            }
        }

        var pick = pickMeasured(pinnedUrl, offer, m, homeRegion, media)
            ?.copy(media = media, skipped = skipped.toList(), mediaIgnored = ignored)
        if (pick == null && media != null && !ignored) {
            // nothing measured on the offer: the pinned url stays unless its region is known not to take [media]
            val n = GravixRegionFailoverOrder.normalizeUrl(pinnedUrl)
            val pinnedEntry = candidates.firstOrNull { GravixRegionFailoverOrder.normalizeUrl(it.url) == n }
                ?: GravixRegionMedia.lastDirectory().firstOrNull { GravixRegionFailoverOrder.normalizeUrl(it.url) == n }
            if (pinnedEntry != null && !GravixRegionMedia.regionAllows(pinnedEntry, media)) {
                val fb = GravixRegionShortlist.fallbackRegion(
                    GravixRegionDirectory(GravixRegionMedia.lastDirectory(), GravixRegionMedia.lastShortlist()),
                    media,
                )
                val offered = fb?.let { f -> offer.firstOrNull { it.region == f.entry.region } }
                val fallback = GravixRegionPick(
                    offered ?: offer.first(),
                    GravixRegionPickReason.FALLBACK,
                    media,
                    skipped.toList(),
                )
                GravixRegionLog.step(
                    "pick",
                    "media=${media.wireName} nothing-measured pinned=${pinnedEntry.region}(${GravixRegionMedia.skipWhy(pinnedEntry, media)})" +
                        " -> ${fallback.entry.region} reason=fallback cause=${if (offered != null) fb.source.wireName else "first-offered"}",
                )
                return fallback
            }
        }
        if (media != null) {
            val p = pick
            val rtt = p?.let { q -> m?.regions?.firstOrNull { it.region == q.entry.region && it.ok }?.rttMs }
            val decision = if (p == null) {
                "token-url cause=${if (m == null) "no-measurement" else "nothing-measured"}"
            } else {
                "${p.entry.region} rtt=${rtt ?: "?"} reason=${p.reason.wireName}"
            }
            GravixRegionLog.step(
                "pick",
                "media=${media.wireName}" +
                    (if (skipWhy.isEmpty()) "" else " skip=${skipWhy.joinToString(",")}") +
                    (if (ignored) " mediaIgnored(no region allows ${media.wireName})" else "") +
                    " -> $decision",
            )
        }
        return pick
    }

    private fun pickMeasured(
        pinnedUrl: String,
        candidates: List<GravixRegionUrl>,
        m: GravixRegionMeasurementResult?,
        homeRegion: String?,
        media: GravixRoomMedia?,
    ): GravixRegionPick? {
        if (m == null) return null
        if (m.best == null) return null
        val best = m.bestFor(media) ?: return null
        if (candidates.isEmpty()) return null
        fun offeredFor(r: GravixRegionMeasurement): GravixRegionUrl? =
            candidates.firstOrNull { it.url == r.url } ?: candidates.firstOrNull { it.region == r.region }
        val order = listOf(best) + m.regions.filter { it.region != best.region }
        var pickedM: GravixRegionMeasurement? = null
        var picked: GravixRegionUrl? = null
        for (r in order) {
            if (!r.ok) continue
            val offered = offeredFor(r)
            if (offered != null) {
                pickedM = r
                picked = offered
                break
            }
        }
        if (picked == null || pickedM == null) return null

        if (!homeRegion.isNullOrEmpty()) {
            val homeM = order.firstOrNull { it.ok && it.region == homeRegion }
            val homeOffered = homeM?.let { offeredFor(it) }
            if (homeM != null && homeOffered != null) {
                var fastest: GravixRegionMeasurement = homeM
                for (r in order) {
                    if (r.ok && offeredFor(r) != null && r.rttMs!! < fastest.rttMs!!) fastest = r
                }
                return if (GravixRegionSelector.homeRegionWithinMargin(homeM.rttMs!!, fastest.rttMs!!)) {
                    GravixRegionPick(homeOffered, GravixRegionPickReason.HOME)
                } else {
                    GravixRegionPick(picked, GravixRegionPickReason.MEASURED)
                }
            }
        }

        fun norm(u: String) = u.trimEnd('/')
        if (norm(picked.url) == norm(pinnedUrl)) return GravixRegionPick(picked, GravixRegionPickReason.MEASURED)
        val pinnedM = m.regions.firstOrNull { it.ok && norm(it.url) == norm(pinnedUrl) }
        val pinned = candidates.firstOrNull { norm(it.url) == norm(pinnedUrl) }
        if (pinnedM == null || pinned == null) return GravixRegionPick(picked, GravixRegionPickReason.MEASURED)
        val gain = pinnedM.rttMs!! - pickedM.rttMs!!
        val needed = maxOf(GravixRegionDecision.SWITCH_MIN_GAIN_MS.toDouble(), pinnedM.rttMs * GravixRegionDecision.SWITCH_MIN_GAIN_RATIO)
        return if (gain > needed) {
            GravixRegionPick(picked, GravixRegionPickReason.MEASURED)
        } else {
            GravixRegionPick(pinned, GravixRegionPickReason.PINNED)
        }
    }

    /**
     * The measured regions as join candidates, for a connect whose token came with no region list -- only when
     * [connectUrl] is itself a measured region (a url outside them, e.g. the app's own server, is never
     * redirected). Else empty.
     *
     * [media] (2026-10-27, Flutter gravixMeasuredCandidates): [connectUrl] is a fleet region of the last
     * `/v1/regions` answer that was not measured (outside this caller's shortlist) and does not allow [media]
     * (doh1 audio-only, a video join): the candidates are the measured regions plus the directory, so [pickRegion]
     * moves the join to the best measured region allowing [media], else the first shortlist region allowing it. No
     * region allows [media]: empty (the token url stays) and the log says why. A lookup, nothing sent. Field
     * 2026-10-27: such a video join stayed on doh1 and its camera publish was refused.
     */
    @JvmStatic
    @JvmOverloads
    fun measuredCandidates(
        connectUrl: String,
        measurement: GravixRegionMeasurementResult? = current(),
        media: GravixRoomMedia? = null,
    ): List<GravixRegionUrl> {
        fun norm(u: String) = u.trimEnd('/')
        val m = measurement
        if (m != null && m.regions.any { norm(it.url) == norm(connectUrl) }) return m.regions.map { it.entry }
        if (media == null) return emptyList()
        val dir = GravixRegionMedia.lastDirectory()
        val n = GravixRegionFailoverOrder.normalizeUrl(connectUrl)
        val token = dir.firstOrNull { GravixRegionFailoverOrder.normalizeUrl(it.url) == n } ?: return emptyList()
        if (GravixRegionMedia.regionAllows(token, media)) return emptyList()
        val why = "${token.region}(${GravixRegionMedia.skipWhy(token, media)})"
        if (dir.none { GravixRegionMedia.regionAllows(it, media) }) {
            GravixRegionLog.step("candidates", "media=${media.wireName} pinned=$why kept cause=no-region-allows-${media.wireName}")
            return emptyList()
        }
        val out = ArrayList<GravixRegionUrl>(m?.regions?.map { it.entry } ?: emptyList())
        for (c in dir) {
            if (out.none { it.region == c.region || norm(it.url) == norm(c.url) }) out.add(c)
        }
        GravixRegionLog.step(
            "candidates",
            "media=${media.wireName} pinned=$why unmeasured -> directory+measured cause=media-not-allowed",
        )
        return out
    }

    /**
     * The url a connect should use: the measured pick among [candidates] (default, null: [measuredCandidates] for
     * [connectUrl] and [media]), else [connectUrl] unchanged.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveConnectUrl(
        connectUrl: String,
        candidates: List<GravixRegionUrl>? = null,
        homeRegion: String? = null,
        media: GravixRoomMedia? = null,
    ): String {
        val m = current()
        val list = candidates ?: measuredCandidates(connectUrl, m, media)
        return pickRegion(connectUrl, list, homeRegion, m, media)?.entry?.url ?: connectUrl
    }

    /**
     * The measurement as the analytics / connection reports carry it: a JSON object (wire names shared with the
     * other SDKs: `measured_at`, `mode`, `network`, `best`, `regions[]`...), ready to embed in the app's own
     * report. Null when nothing was sampled (e.g. only a cached answer). Structured access: [current].
     *
     * GRAVIX(Phase 4): a String, not a kotlinx JsonObject -- kotlinx-serialization is an `implementation`
     * dependency, so apps could not compile a call that returned its type (found in the first real-fleet run).
     */
    @JvmStatic
    @JvmOverloads
    fun regionsMeasuredReport(result: GravixRegionMeasurementResult? = current()): String? =
        regionsMeasuredReportJson(result)?.toString()

    internal fun regionsMeasuredReportJson(result: GravixRegionMeasurementResult? = current()): JsonObject? {
        val r = result ?: return null
        if (r.regions.none { it.samplesMs.isNotEmpty() }) return null
        return buildJsonObject {
            put("measured_at", isoMs(r.measuredAtMs))
            put("mode", (r.mode ?: GravixRegionPlanMode.ALL).wireName)
            put("network", r.networkKey ?: "unknown")
            put("budget_ms", r.budgetMs ?: 0L)
            put("budget_hit", r.budgetHit)
            put("early_exit", r.earlyExit)
            put("elapsed_ms", r.elapsedMs ?: 0L)
            put("best", r.best?.region)
            put("best_source", r.bestSource?.wireName)
            r.selectReason?.let { put("select_reason", it.wireName) }
            put(
                "regions",
                buildJsonArray {
                    for (m in r.regions) {
                        add(
                            buildJsonObject {
                                put("region", m.region)
                                put("rtt_ms", m.rttMs)
                                put("status", m.status.wireName)
                                put("samples_ms", buildJsonArray { m.samplesMs.forEach { add(JsonPrimitive(it)) } })
                                put("conns", m.connections)
                                put("source", m.source.wireName)
                                put("budget_hit", m.budgetHit)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun isoMs(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(ms))

    // ---------------------------------------------------------------------------------------------- state

    internal data class RegionRtt(val region: String, val url: String, val rttMs: Long) {
        fun toJson(): JsonObject = buildJsonObject {
            put("region", region)
            put("url", url)
            put("rttMs", rttMs)
        }

        companion object {
            fun from(e: JsonElement?): RegionRtt? {
                val m = e as? JsonObject ?: return null
                val region = (m["region"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val url = (m["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                val rtt = (m["rttMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
                return RegionRtt(region, url, rtt)
            }
        }
    }

    /** GRAVIX (2026-10-21): a per-medium anchor and when it was set (wall ms). */
    internal data class MediaAnchor(val rtt: RegionRtt, val atMs: Long)

    /** Per-network state (in memory, and in the store when there is one). Same JSON as the Flutter SDK. */
    internal data class NetworkState(
        val known: List<String>,
        val cursor: Int,
        val measuredAtMs: Long,
        val ttlMs: Long,
        val best: RegionRtt? = null,
        val regions: List<RegionRtt> = emptyList(),
        val anchor: RegionRtt? = null,
        val anchorAtMs: Long? = null,
        val challenger: GravixRegionChallenger? = null,
        // GRAVIX (2026-10-21): per-medium anchors / challengers, wire `mediaAnchors: {video: {region, url, rttMs,
        // atMs}}` and `mediaChallengers: {video: {region, wins}}` (the same keys in every SDK)
        val mediaAnchors: Map<GravixRoomMedia, MediaAnchor> = emptyMap(),
        val mediaChallengers: Map<GravixRoomMedia, GravixRegionChallenger> = emptyMap(),
    ) {
        fun freshAt(nowMs: Long) = nowMs - measuredAtMs <= ttlMs

        fun liveMediaAnchor(media: GravixRoomMedia, nowMs: Long): RegionRtt? =
            mediaAnchors[media]?.takeIf { nowMs - it.atMs <= GravixRegionSelector.ANCHOR_LIFE_MS }?.rtt

        fun liveAnchor(nowMs: Long): RegionRtt? =
            if (anchor != null && anchorAtMs != null && nowMs - anchorAtMs <= GravixRegionSelector.ANCHOR_LIFE_MS) anchor else null

        fun encode(): String = buildJsonObject {
            put("known", buildJsonArray { known.forEach { add(JsonPrimitive(it)) } })
            put("cursor", cursor)
            put("measuredAtMs", measuredAtMs)
            put("ttlMs", ttlMs)
            best?.let { put("best", it.toJson()) }
            if (regions.isNotEmpty()) put("regions", buildJsonArray { regions.forEach { add(it.toJson()) } })
            if (anchor != null && anchorAtMs != null) {
                put("anchor", JsonObject(anchor.toJson() + ("atMs" to JsonPrimitive(anchorAtMs))))
            }
            challenger?.let { put("challenger", it.toJson()) }
            if (mediaAnchors.isNotEmpty()) {
                put(
                    "mediaAnchors",
                    buildJsonObject {
                        for ((k, a) in mediaAnchors) put(k.wireName, JsonObject(a.rtt.toJson() + ("atMs" to JsonPrimitive(a.atMs))))
                    },
                )
            }
            if (mediaChallengers.isNotEmpty()) {
                put("mediaChallengers", buildJsonObject { for ((k, c) in mediaChallengers) put(k.wireName, c.toJson()) })
            }
        }.toString()

        companion object {
            fun decode(raw: String): NetworkState? = try {
                val m = Json.parseToJsonElement(raw) as? JsonObject
                val measuredAt = (m?.get("measuredAtMs") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                val knownArr = m?.get("known") as? JsonArray
                if (m == null || measuredAt == null || knownArr == null) {
                    null
                } else {
                    val a = m["anchor"] as? JsonObject
                    val anchor = RegionRtt.from(a)
                    val atMs = (a?.get("atMs") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                    NetworkState(
                        known = knownArr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                        cursor = (m["cursor"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt() ?: 0,
                        measuredAtMs = measuredAt,
                        ttlMs = (m["ttlMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: TTL_MS,
                        best = RegionRtt.from(m["best"]),
                        regions = (m["regions"] as? JsonArray).orEmpty().mapNotNull { RegionRtt.from(it) },
                        anchor = if (anchor != null && atMs != null) anchor else null,
                        anchorAtMs = if (anchor != null && atMs != null) atMs else null,
                        challenger = GravixRegionChallenger.fromJson(m["challenger"]),
                        mediaAnchors = (m["mediaAnchors"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                            val medium = GravixRoomMedia.fromWireName(k) ?: return@mapNotNull null
                            val rtt = RegionRtt.from(v) ?: return@mapNotNull null
                            val at = ((v as JsonObject)["atMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                                ?: return@mapNotNull null
                            medium to MediaAnchor(rtt, at)
                        }.toMap(),
                        mediaChallengers = (m["mediaChallengers"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                            val medium = GravixRoomMedia.fromWireName(k) ?: return@mapNotNull null
                            medium to (GravixRegionChallenger.fromJson(v) ?: return@mapNotNull null)
                        }.toMap(),
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
