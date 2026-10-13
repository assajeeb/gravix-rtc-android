/*
 * Copyright 2025-2026 LiveKit, Inc.
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

package com.gravixcloud.rtc.token

import com.gravixcloud.rtc.dagger.globalOkHttpClient
import com.gravixcloud.rtc.room.requireNotRetiredGravixServer
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.rethrowIfCancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URL
import kotlin.coroutines.resume

internal class EndpointTokenSourceImpl(
    override val url: URL,
    override val method: String,
    override val headers: Map<String, String>,
) : EndpointTokenSource {
    // GRAVIX(retired servers): the retired token endpoint is refused here, so no request is ever sent to it
    init {
        requireNotRetiredGravixServer(url.toString())
    }

    constructor(
        url: String,
        method: String,
        headers: Map<String, String>,
    ) : this(URL(url), method, headers)
}

/**
 * Options for [TokenSource.fromDevelopmentTokenServer].
 *
 * @param baseUrl the base url of your development token server. Required (GRAVIX): Gravix Cloud hosts no
 *   shared development token server, so there is no default.
 */
data class DevelopmentTokenServerOptions(
    val baseUrl: String? = null,
)

/**
 * Options for the deprecated [TokenSource.fromSandboxTokenServer].
 *
 * @see DevelopmentTokenServerOptions
 */
@Deprecated("Use DevelopmentTokenServerOptions instead", ReplaceWith("DevelopmentTokenServerOptions(baseUrl)"))
data class SandboxTokenServerOptions(
    val baseUrl: String? = null,
)

internal class DevelopmentTokenSource(tokenServerId: String, options: DevelopmentTokenServerOptions) : EndpointTokenSource {
    // GRAVIX: upstream fell back to its vendor's hosted sandbox service when baseUrl was null, which would make a
    // customer app call a third-party cloud. Gravix Cloud has no such service: require the base url.
    override val url: URL = URL(
        "${requireNotNull(options.baseUrl) { "DevelopmentTokenServerOptions.baseUrl is required (no hosted development token server)" }}" +
            "/api/v2/sandbox/connection-details",
    ).also { requireNotRetiredGravixServer(it.toString()) } // GRAVIX(retired servers): see EndpointTokenSourceImpl
    override val headers: Map<String, String> = mapOf(
        "X-Sandbox-ID" to tokenServerId,
    )
}

internal interface EndpointTokenSource : ConfigurableTokenSource {
    /** The url to fetch the token from */
    val url: URL

    /** The HTTP method to use (defaults to "POST") */
    val method: String
        get() = "POST"

    /** Additional HTTP headers to include with the request */
    val headers: Map<String, String>

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun fetch(options: TokenRequestOptions): Result<TokenSourceResponse> = suspendCancellableCoroutine { continuation ->
        try {
            val okHttpClient = globalOkHttpClient

            val snakeCaseJson = Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                ignoreUnknownKeys = true
                explicitNulls = false
            }

            // v1 token server returns camelCase keys
            val camelCaseJson = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
            val body = snakeCaseJson.encodeToString(options.toRequest())

            val request = Request.Builder()
                .url(url)
                .method(method, body.toRequestBody())
                .addHeader("Content-Type", "application/json")
                .apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build()

            val call = okHttpClient.newCall(request)
            call.enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        val bodyStr = response.body?.string()
                        if (bodyStr == null) {
                            continuation.resume(Result.failure(NullPointerException("No response returned from server")))
                            return
                        }

                        var tokenResponse: TokenSourceResponse? = null
                        try {
                            tokenResponse = snakeCaseJson.decodeFromString<TokenSourceResponse>(bodyStr)
                        } catch (e: Exception) {
                            GxLog.d(e) { "Unable to decode token source json with snake_case, trying camelCase." }
                        }

                        if (tokenResponse == null) {
                            // snake_case decoding failed, try camelCase decoding for v1 back compatibility
                            try {
                                tokenResponse = camelCaseJson.decodeFromString<TokenSourceResponse>(bodyStr)
                            } catch (e: Exception) {
                                continuation.resume(Result.failure(IllegalArgumentException("Failed to decode response from token server", e)))
                                return
                            }
                        }

                        continuation.resume(Result.success(tokenResponse))
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(Result.failure(e))
                    }
                },
            )
        } catch (e: Exception) {
            e.rethrowIfCancellationSignal()
            continuation.resume(Result.failure(e))
        }
    }
}
