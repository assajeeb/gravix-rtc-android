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

package com.gravixcloud.rtc.token

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GRAVIX (2026-10-27): what a device-signed join token grants. Field for field the `gravix-rtc-token` npm package's
 * `TokenOptions`, with the same defaults. Java: [builder].
 */
@Suppress("LongParameterList") // the npm package's option set, field for field
class GravixTokenOptions @JvmOverloads constructor(
    /** The room to join; the `<AppID>_` prefix is added when missing. */
    val room: String,
    /** The user's unique id within the room. */
    val identity: String,
    /** The display name other participants see. */
    val name: String? = null,
    /** An opaque string visible to other participants. */
    val metadata: String? = null,
    /** Key/value pairs visible to others (insertion order is kept; `gravix.app` is reserved: the server sets it). */
    val attributes: Map<String, String>? = null,
    /** How long the token can be used to join, in whole seconds: default 6 h, at most 24 h. */
    val ttlSeconds: Long = GravixTokenBuilder.DEFAULT_TTL_SECONDS,
    /** False makes a listener. */
    val canPublish: Boolean = true,
    val canSubscribe: Boolean = true,
    /** Data messages and chat. */
    val canPublishData: Boolean = true,
    /** The user may change its own name, metadata and attributes (the SDKs publish e.g. the camera facing). */
    val canUpdateOwnMetadata: Boolean = true,
    /** Limits publishing to these sources ("camera", "microphone", "screen_share", "screen_share_audio"). */
    val canPublishSources: List<String>? = null,
    /** Invisible to others (recorders, bots). */
    val hidden: Boolean = false,
    /** May mute / remove others in THIS room only. */
    val roomAdmin: Boolean = false,
) {
    /** Java: `GravixTokenOptions.builder("room", "uid").canPublish(false).build()`. */
    @Suppress("TooManyFunctions") // one setter per option
    class Builder internal constructor(private val room: String, private val identity: String) {
        private var name: String? = null
        private var metadata: String? = null
        private var attributes: Map<String, String>? = null
        private var ttlSeconds = GravixTokenBuilder.DEFAULT_TTL_SECONDS
        private var canPublish = true
        private var canSubscribe = true
        private var canPublishData = true
        private var canUpdateOwnMetadata = true
        private var canPublishSources: List<String>? = null
        private var hidden = false
        private var roomAdmin = false

        fun name(v: String?) = apply { name = v }
        fun metadata(v: String?) = apply { metadata = v }
        fun attributes(v: Map<String, String>?) = apply { attributes = v?.let { LinkedHashMap(it) } }
        fun ttlSeconds(v: Long) = apply { ttlSeconds = v }
        fun canPublish(v: Boolean) = apply { canPublish = v }
        fun canSubscribe(v: Boolean) = apply { canSubscribe = v }
        fun canPublishData(v: Boolean) = apply { canPublishData = v }
        fun canUpdateOwnMetadata(v: Boolean) = apply { canUpdateOwnMetadata = v }
        fun canPublishSources(v: List<String>?) = apply { canPublishSources = v?.let { ArrayList(it) } }
        fun hidden(v: Boolean) = apply { hidden = v }
        fun roomAdmin(v: Boolean) = apply { roomAdmin = v }

        fun build() = GravixTokenOptions(
            room, identity, name, metadata, attributes, ttlSeconds, canPublish, canSubscribe, canPublishData,
            canUpdateOwnMetadata, canPublishSources, hidden, roomAdmin,
        )
    }

    companion object {
        @JvmStatic
        fun builder(room: String, identity: String): Builder = Builder(room, identity)
    }
}

/**
 * GRAVIX (2026-10-27): thrown for inputs the server would refuse anyway (empty / malformed App ID, certificate under
 * 32 characters, empty room or identity, ttl outside (0, 24 h]). The message never contains the certificate.
 */
class GravixTokenBuildException internal constructor(message: String) : IllegalArgumentException(message)

/**
 * GRAVIX (2026-10-27): signs a Gravix join token (HS256 JWT) ON THE DEVICE, no network. The Kotlin twin of the
 * `gravix-rtc-token` npm package and of the Flutter `GravixTokenBuilder`: same claims, same key order, same
 * signature, so a token made here is byte-for-byte the token the npm `createToken` makes for the same inputs and
 * clock.
 *
 * WARNING: using this puts the App Certificate ON THE DEVICE. Anyone who extracts it (from the APK, from memory, from
 * the request that delivered it) can sign a token for ANY identity and ANY room of your app, publisher and room-admin
 * grants included, until you rotate the certificate in the console (which breaks every installed copy holding the old
 * one). Deliver it from your own server to signed-in users only, and never commit it to your app's source. If that is
 * not acceptable, keep signing on your server and use [com.gravixcloud.rtc.join.GravixJoinTokenFetcher].
 */
object GravixTokenBuilder {
    /** The default token lifetime: 6 hours. */
    const val DEFAULT_TTL_SECONDS: Long = 6 * 3600L

    /** The longest lifetime the server accepts: 24 hours. */
    const val MAX_TTL_SECONDS: Long = 24 * 3600L

    /** Separator between the App ID and the room in the server-side room name. */
    const val ROOM_SEPARATOR: String = "_"

    private const val MIN_CERTIFICATE_LENGTH = 32
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    /** The server-side name of an app's room (`<AppID>_<room>`; unchanged when already prefixed). */
    @JvmStatic
    fun roomName(appId: String, room: String): String =
        if (room.startsWith(appId + ROOM_SEPARATOR)) room else appId + ROOM_SEPARATOR + room

    /** Strips the app prefix, for showing a room name to users. */
    @JvmStatic
    fun displayName(appId: String, room: String): String = room.removePrefix(appId + ROOM_SEPARATOR)

    /**
     * A signed join token for [options], issued now (device clock). One HMAC, no I/O.
     *
     * @throws GravixTokenBuildException on an empty or malformed App ID, a certificate shorter than 32 characters, a
     *   missing room or identity, or a ttl outside (0, 24 h].
     */
    @JvmStatic
    fun create(appId: String, appCertificate: String, options: GravixTokenOptions): String =
        create(appId, appCertificate, options, System.currentTimeMillis())

    /** [create] at a given clock (epoch milliseconds): tests and byte-parity vectors. */
    @JvmStatic
    internal fun create(appId: String, appCertificate: String, options: GravixTokenOptions, nowMs: Long): String {
        val input = b64(HEADER.toByteArray(Charsets.UTF_8)) + "." + b64(claims(appId, appCertificate, options, nowMs / 1000).toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appCertificate.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return input + "." + b64(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url, no padding. Hand-rolled: java.util.Base64 needs API 26 and android.util.Base64 is not unit-testable. */
    private fun b64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) bytes[i + k].toInt() and 0xff else 0)
            for (k in 0..n) sb.append(B64URL[(v shr (18 - 6 * k)) and 0x3f])
            i += 3
        }
        return sb.toString()
    }

    @Suppress("CyclomaticComplexMethod") // one validation list + the claim list, flat on purpose (as the npm package)
    private fun claims(appId: String, cert: String, o: GravixTokenOptions, now: Long): String {
        if (appId.isEmpty()) throw GravixTokenBuildException("app ID is empty")
        if (appId.contains(ROOM_SEPARATOR)) throw GravixTokenBuildException("app ID must not contain \"$ROOM_SEPARATOR\"")
        if (cert.length < MIN_CERTIFICATE_LENGTH) throw GravixTokenBuildException("app certificate is shorter than $MIN_CERTIFICATE_LENGTH characters")
        if (o.room.isEmpty()) throw GravixTokenBuildException("room is required")
        if (o.identity.isEmpty()) throw GravixTokenBuildException("identity is required")
        if (o.ttlSeconds <= 0 || o.ttlSeconds > MAX_TTL_SECONDS) throw GravixTokenBuildException("ttl must be in (0, $MAX_TTL_SECONDS] seconds")
        // Key order is the npm package's: the signature covers the exact bytes.
        val sb = StringBuilder(256)
        sb.append("{\"iss\":").appendJson(appId).append(",\"sub\":").appendJson(o.identity)
        sb.append(",\"iat\":").append(now).append(",\"nbf\":").append(now).append(",\"exp\":").append(now + o.ttlSeconds)
        sb.append(",\"video\":{\"roomJoin\":true,\"room\":").appendJson(roomName(appId, o.room))
        sb.append(",\"canPublish\":").append(o.canPublish).append(",\"canSubscribe\":").append(o.canSubscribe)
        sb.append(",\"canPublishData\":").append(o.canPublishData).append(",\"canUpdateOwnMetadata\":").append(o.canUpdateOwnMetadata)
        if (o.roomAdmin) sb.append(",\"roomAdmin\":true")
        o.canPublishSources?.takeIf { it.isNotEmpty() }?.let { s ->
            sb.append(",\"canPublishSources\":[")
            for (i in s.indices) {
                if (i > 0) sb.append(',')
                sb.appendJson(s[i])
            }
            sb.append(']')
        }
        if (o.hidden) sb.append(",\"hidden\":true")
        sb.append('}')
        o.name?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"name\":").appendJson(it) }
        o.metadata?.takeIf { it.isNotEmpty() }?.let { sb.append(",\"metadata\":").appendJson(it) }
        o.attributes?.takeIf { it.isNotEmpty() }?.let { a ->
            sb.append(",\"attributes\":{")
            var first = true
            for ((k, v) in a) {
                if (!first) sb.append(',')
                first = false
                sb.appendJson(k).append(':').appendJson(v)
            }
            sb.append('}')
        }
        return sb.append('}').toString()
    }

    /**
     * A JSON string as JavaScript's `JSON.stringify` writes it (not org.json: that escapes "/"): only `"`, `\`,
     * control characters and lone surrogates are escaped; everything else, non-ASCII included, goes out as is.
     */
    private fun StringBuilder.appendJson(s: String): StringBuilder {
        append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val escaped = escapeOf(c)
            when {
                escaped != null -> append(escaped)
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> append("\\u%04x".format(c.code)) // lone: well-formed JSON.stringify
                else -> append(c)
            }
            i++
        }
        return append('"')
    }

    private fun escapeOf(c: Char): String? = when (c) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\u000c' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> if (c < ' ') "\\u%04x".format(c.code) else null
    }
}
