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

import com.gravixcloud.rtc.room.requireNotRetiredGravixServer
import com.gravixcloud.rtc.token.ConfigurableTokenSource
import com.gravixcloud.rtc.token.GravixTokenBuilder
import com.gravixcloud.rtc.token.GravixTokenOptions
import com.gravixcloud.rtc.token.TokenPayload
import com.gravixcloud.rtc.token.TokenRequestOptions
import com.gravixcloud.rtc.util.TimeoutException
import com.gravixcloud.rtc.util.launchFuture
import com.gravixcloud.rtc.util.withDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.function.Function
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/**
 * GRAVIX (0.1.2): your server's join-token call, as [GravixJoinTokenProvider] calls it. Kotlin:
 * `GravixJoinTokenFetcher { request -> GravixJoinCredentials(url, myBackend.joinToken(request.room)) }`; Java:
 * [fromFuture].
 */
interface GravixJoinTokenFetcher {
    /** The url and join token for [request]. Throw when there is none (a refused room, no network). */
    suspend fun fetch(request: GravixJoinRequest): GravixJoinCredentials

    companion object {
        /** Kotlin: a fetcher from a suspend lambda. */
        @JvmSynthetic
        operator fun invoke(block: suspend (GravixJoinRequest) -> GravixJoinCredentials): GravixJoinTokenFetcher =
            object : GravixJoinTokenFetcher {
                override suspend fun fetch(request: GravixJoinRequest): GravixJoinCredentials = block(request)
            }

        /** Java: a fetcher whose call returns a [CompletableFuture] (cancelled when the fetch is no longer wanted). */
        @JvmStatic
        fun fromFuture(fetch: Function<GravixJoinRequest, CompletableFuture<GravixJoinCredentials>>): GravixJoinTokenFetcher =
            object : GravixJoinTokenFetcher {
                override suspend fun fetch(request: GravixJoinRequest): GravixJoinCredentials = fetch.apply(request).awaitGravix()
            }

        /**
         * A fetcher over one of the SDK's token sources (e.g. `TokenSource.fromEndpoint(url)`): the request's room and
         * identity become its [TokenRequestOptions] (`canPublish` is your token server's decision).
         */
        @JvmStatic
        fun fromTokenSource(source: ConfigurableTokenSource): GravixJoinTokenFetcher =
            object : GravixJoinTokenFetcher {
                override suspend fun fetch(request: GravixJoinRequest): GravixJoinCredentials {
                    val r = source.fetch(TokenRequestOptions(roomName = request.room, participantIdentity = request.identity))
                        .getOrThrow()
                    return GravixJoinCredentials(r.serverUrl, r.participantToken)
                }
            }
    }
}

/**
 * GRAVIX (0.1.2): why [GravixJoinTokenProvider] has no token. Its message never contains a token or a url.
 */
class GravixJoinTokenException internal constructor(
    val reason: Reason,
    cause: Throwable?,
) : Exception("join token: ${reason.name.lowercase()}${cause?.let { " (${it.javaClass.simpleName})" } ?: ""}", cause) {
    enum class Reason {
        /** No answer within the provider's timeout. */
        TIMEOUT,

        /** Your fetcher threw (its exception is the [cause]). */
        FETCHER,

        /** The answer had no url or no token. */
        MALFORMED,
    }
}

/**
 * GRAVIX (0.1.2): join tokens for [GravixJoin], cached per [GravixJoinRequest] (16 entries, least recently used out),
 * one fetch per request at a time (callers share it), a failed fetch never cached, and a cached token presented only
 * while [reuse] allows it ([GravixTokenReuse.FAST_JOIN] by default). Fetches time out after 15 s.
 *
 * Thread-safe. One per app or per signed-in user ([invalidateAll] when another account signs in).
 */
class GravixJoinTokenProvider internal constructor(
    private val fetcher: GravixJoinTokenFetcher,
    /** When a cached token is presented. */
    val reuse: GravixTokenReuse,
    dispatcher: CoroutineDispatcher,
) {
    constructor(fetcher: GravixJoinTokenFetcher) : this(fetcher, GravixTokenReuse.FAST_JOIN, Dispatchers.IO)

    constructor(fetcher: GravixJoinTokenFetcher, reuse: GravixTokenReuse) : this(fetcher, reuse, Dispatchers.IO)

    private class Entry(val credentials: GravixJoinCredentials, val fetchedAtMs: Long, val expiresAtMs: Long)

    private val lock = Any()
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, Deferred<GravixJoinCredentials>>()
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    internal var clock: () -> Long = System::currentTimeMillis
    internal var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    /**
     * The credentials for [request]: a cached token [reuse] allows (`fromCache` true), else a fetch (shared with any
     * caller waiting for the same request). Throws [GravixJoinTokenException].
     */
    @JvmSynthetic
    suspend fun getCredentials(request: GravixJoinRequest): GravixJoinCredentials {
        val key = request.sessionKey
        val pending: Deferred<GravixJoinCredentials>
        synchronized(lock) {
            usableLocked(key)?.let { return it }
            pending = inFlight[key] ?: startFetchLocked(key, request)
        }
        return pending.await()
    }

    /** Java: [getCredentials] as a future (completes on the fetching thread). */
    fun getCredentialsAsync(request: GravixJoinRequest): CompletableFuture<GravixJoinCredentials> =
        launchFuture(scope, DIRECT) { getCredentials(request) }

    /** A cached token [reuse] allows for [request] now, or null (never fetches). */
    fun cached(request: GravixJoinRequest): GravixJoinCredentials? = synchronized(lock) { usableLocked(request.sessionKey) }

    /** Drops [request]'s cached token (e.g. the server refused it). */
    fun invalidate(request: GravixJoinRequest) {
        synchronized(lock) { cache.remove(request.sessionKey) }
    }

    /** Drops every cached token; a fetch running now is not cached (e.g. another account signed in). */
    fun invalidateAll() {
        synchronized(lock) {
            cache.clear()
            generation++
        }
    }

    private fun usableLocked(key: String): GravixJoinCredentials? {
        val e = cache[key] ?: return null
        if (reuse.allows(e.fetchedAtMs, e.expiresAtMs, clock())) {
            return GravixJoinCredentials(e.credentials.url, e.credentials.token, true)
        }
        cache.remove(key)
        return null
    }

    private fun startFetchLocked(key: String, request: GravixJoinRequest): Deferred<GravixJoinCredentials> {
        val gen = generation
        val d = scope.async { fetch(key, request, gen) }
        inFlight[key] = d
        d.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === d) inFlight.remove(key) } }
        return d
    }

    @Suppress("SwallowedException") // the reason is the exception; an opaque token has no expiry
    private suspend fun fetch(key: String, request: GravixJoinRequest, gen: Int): GravixJoinCredentials {
        val c = try {
            withDeadline(timeoutMs.milliseconds) { fetcher.fetch(request) }
        } catch (e: TimeoutException) {
            throw GravixJoinTokenException(GravixJoinTokenException.Reason.TIMEOUT, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw GravixJoinTokenException(GravixJoinTokenException.Reason.FETCHER, e)
        }
        if (c.url.isBlank() || c.token.isBlank()) throw GravixJoinTokenException(GravixJoinTokenException.Reason.MALFORMED, null)
        val expiresAt = try {
            TokenPayload(c.token).expiresAt?.time ?: 0L
        } catch (e: Exception) {
            0L // an opaque token: only maxAge applies
        }
        val fresh = GravixJoinCredentials(c.url, c.token, false)
        synchronized(lock) {
            if (gen == generation) {
                cache[key] = Entry(fresh, clock(), expiresAt)
                while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
            }
        }
        return fresh
    }

    companion object {
        /**
         * GRAVIX (2026-10-27): a provider that SIGNS the join token on the device ([GravixTokenBuilder]): no token round
         * trip at all, so a room tap costs one HMAC. Cached like any provider ([reuse], 16 entries). It has no region
         * list (the credentials never carry one on Android): the join dials [url], or the nearest measured region when
         * [url] is one of the measured regions, exactly as for a fetched token (start the measurement with
         * `GravixRegions.startMeasurement`); a video join whose [url] is an audio-only region (e.g. doh1) moves to a
         * region that allows video.
         *
         * WARNING: the App Certificate is then ON THE DEVICE. Anyone who extracts it (from the APK, from memory, from the
         * request that delivered it) can sign a token for ANY identity and ANY room / grant of your app until you rotate
         * it in the console (which breaks every installed copy holding the old one). Deliver it from your own server to
         * signed-in users only; never commit it to your source. If that is not acceptable, keep signing on your server
         * ([GravixJoinTokenFetcher]).
         *
         * [options] decides the grant for a request (default: the request's room and identity, [GravixJoinRequest.canPublish],
         * [ttlSeconds] default 6 h). A [com.gravixcloud.rtc.token.GravixTokenBuildException] (bad App ID, short
         * certificate, empty room or identity, ttl) surfaces as [GravixJoinTokenException] (`FETCHER`).
         *
         * @throws com.gravixcloud.rtc.room.RetiredServerException when [url] is a retired Gravix host.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("LongParameterList") // the url, the certificate and the grant rules
        fun local(
            appId: String,
            appCertificate: String,
            url: String,
            ttlSeconds: Long = GravixTokenBuilder.DEFAULT_TTL_SECONDS,
            options: Function<GravixJoinRequest, GravixTokenOptions>? = null,
            reuse: GravixTokenReuse = GravixTokenReuse.FAST_JOIN,
        ): GravixJoinTokenProvider = GravixJoinTokenProvider(localFetcher(appId, appCertificate, url, ttlSeconds, options), reuse)

        /** Kotlin: [local] with a lambda grant. */
        @JvmSynthetic
        @Suppress("LongParameterList")
        fun local(
            appId: String,
            appCertificate: String,
            url: String,
            ttlSeconds: Long = GravixTokenBuilder.DEFAULT_TTL_SECONDS,
            reuse: GravixTokenReuse = GravixTokenReuse.FAST_JOIN,
            options: (GravixJoinRequest) -> GravixTokenOptions,
        ): GravixJoinTokenProvider = local(appId, appCertificate, url, ttlSeconds, Function(options), reuse)

        @Suppress("LongParameterList")
        internal fun localFetcher(
            appId: String,
            appCertificate: String,
            url: String,
            ttlSeconds: Long,
            options: Function<GravixJoinRequest, GravixTokenOptions>?,
            nowMs: () -> Long = System::currentTimeMillis,
        ): GravixJoinTokenFetcher {
            requireNotRetiredGravixServer(url)
            return GravixJoinTokenFetcher { r ->
                val grant = options?.apply(r)
                    ?: GravixTokenOptions(room = r.room, identity = r.identity, ttlSeconds = ttlSeconds, canPublish = r.canPublish)
                GravixJoinCredentials(url, GravixTokenBuilder.create(appId, appCertificate, grant, nowMs()))
            }
        }

        private const val MAX_ENTRIES = 16
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private val DIRECT = Executor { it.run() }
    }
}

/** Awaits this future; cancelling the caller cancels the future. */
internal suspend fun <T> CompletableFuture<T>.awaitGravix(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (error == null) {
            cont.resume(value)
        } else {
            val cause = (error as? CompletionException)?.cause ?: error
            if (cause is java.util.concurrent.CancellationException) cont.cancel(cause) else cont.resumeWithException(cause)
        }
    }
    cont.invokeOnCancellation { cancel(false) }
}
