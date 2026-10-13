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

import com.gravixcloud.rtc.util.executeAsync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GRAVIX: one region's probe connection for the duration of a measurement (port of the Flutter SDK's
 * `GravixRegionProbeClient`).
 *
 * Field 2026-10-01 (Flutter, same phone, same Wi-Fi): every "warm" sample went through a new HTTP client, so each
 * paid DNS + TCP + TLS + HTTP -- a handshake race, not an RTT (sgp1 190-265 / blr1 200-333 ms against the web
 * SDK's sgp1 61 / blr1 ~120). This client keeps ONE pooled connection for all the samples of one region: the first
 * request opens the socket (the cold sample, dropped), requests 2..N reuse it and measure one round trip.
 * [close] after the region.
 *
 * Each instance has its own [ConnectionPool] (one idle socket), so regions never share or coalesce sockets.
 * [connections] counts the sockets this client opened (1 after a healthy measurement).
 */
class GravixRegionProbeClient @JvmOverloads constructor(
    base: OkHttpClient = defaultBase,
    idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
) {
    private val opened = AtomicInteger(0)
    private val inFlight: MutableSet<Call> = Collections.synchronizedSet(HashSet())

    @Volatile
    var isClosed: Boolean = false
        private set

    private val client: OkHttpClient = base.newBuilder()
        .connectionPool(ConnectionPool(1, idleTimeoutMs, TimeUnit.MILLISECONDS))
        .eventListener(
            object : EventListener() {
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                    opened.incrementAndGet()
                }
            },
        )
        .build()

    /** Sockets this client opened. */
    val connections: Int get() = opened.get()

    /**
     * HEAD the region's HTTP origin (`wss://` becomes `https://`). Throws unless the edge answers 2xx; a 405/501
     * (the method, not the region) is asked once more with GET.
     */
    suspend fun probe(url: String) {
        val httpUrl = toHttpUrl(url)
        var status = send(Request.Builder().url(httpUrl).head().build())
        if (status == 405 || status == 501) {
            status = send(Request.Builder().url(httpUrl).get().build())
        }
        if (status !in 200..299) throw IOException("region probe answered $status")
    }

    /**
     * GET the region's dedicated probe endpoint and return the region it names (null when the body does not say).
     * Throws on a non-2xx.
     */
    suspend fun verifiedProbe(probeUrl: String): String? {
        var body: String? = null
        val status = send(Request.Builder().url(probeUrl).get().build()) { body = it }
        if (status !in 200..299) throw IOException("region probe answered $status")
        return try {
            val obj = Json.parseToJsonElement(body ?: return null) as? JsonObject
            (obj?.get("region") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null // not JSON: nothing to verify against, which is not a mismatch
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun send(request: Request, readBody: ((String) -> Unit)? = null): Int {
        if (isClosed) throw IOException("GravixRegionProbeClient is closed")
        val call = client.newCall(request)
        inFlight.add(call)
        try {
            return call.executeAsync().use { response ->
                // the body is read (and so the connection returned to the pool) before the next sample
                val text = response.body?.string()
                if (readBody != null && text != null) readBody(text)
                response.code
            }
        } finally {
            inFlight.remove(call)
        }
    }

    /** Closes the pooled socket(s); requests still in flight (a timed-out sample) are aborted. Idempotent. */
    fun close() {
        if (isClosed) return
        isClosed = true
        synchronized(inFlight) { inFlight.toList() }.forEach { it.cancel() }
        client.connectionPool.evictAll()
    }

    companion object {
        /** How long an idle probe socket stays pooled (covers one region's sampling window). */
        const val IDLE_TIMEOUT_MS = 30_000L

        private val defaultBase: OkHttpClient by lazy { OkHttpClient() }

        /** `ws(s)://` -> `http(s)://`; other schemes unchanged. */
        @JvmStatic
        fun toHttpUrl(url: String): String = when {
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
            else -> url
        }
    }
}
