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

package com.gravixcloud.rtc.util

import org.webrtc.IceCandidate
import java.net.URI

/**
 * GRAVIX (0.1.2): where the step lines of [GravixPubLog] and `GravixJoinLog` go when an app wants them in its own
 * logger (crash log, file, analytics). A line is one secret-free text line starting with its prefix.
 */
fun interface GravixLogSink {
    /** One step line at [level] ([LoggingLevel.INFO] or [LoggingLevel.VERBOSE]). Must not throw. */
    fun onLine(level: LoggingLevel, line: String)
}

/**
 * GRAVIX (0.1.2): one secret-free line per publisher negotiation step, so a device log shows which offer was made
 * from which state and why libwebrtc refused one.
 *
 * ```
 * [GravixPub] <step> pc=<label> [<detail>]
 * [GravixPub] offerCreate pc=pub#1 restart=false mlines=audio:0/sendonly,application:1
 * [GravixPub] offerDefer pc=pub#1 have-local-offer: replayed after the answer
 * [GravixPub] remoteSet pc=pub#1 answer ok id=2 replay=true mlines=audio:0/recvonly,application:1
 * ```
 *
 * Never in a line: an SDP body (ice-pwd, ice-ufrag, fingerprints), a token, a url query, libwebrtc error text beyond
 * its fixed reason. An m-line list is `<kind>:<mid>/<direction>` per section, in order (`/rejected` = port 0).
 *
 * On by default at [LoggingLevel.INFO] (a few lines per negotiation), independent of `GravixRtc.loggingLevel`. The
 * lines go to [sink], or when it is null to `GravixRtc.logger` (logcat by default) at INFO.
 */
object GravixPubLog {
    /** The stable prefix of every line (grep for it). */
    const val PREFIX = "[GravixPub]"

    /** The lowest level written: [LoggingLevel.INFO] (default), [LoggingLevel.VERBOSE] (more), or [LoggingLevel.OFF]. */
    @JvmStatic
    @Volatile
    var level: LoggingLevel = LoggingLevel.INFO

    /** Where the lines go (null: `GravixRtc.logger`). */
    @JvmStatic
    @Volatile
    var sink: GravixLogSink? = null

    internal fun step(step: String, pc: String, detail: String? = null, at: LoggingLevel = LoggingLevel.INFO) {
        if (!GravixStepLogs.writes(level, at)) return
        val line = buildString {
            append(PREFIX).append(' ').append(step).append(" pc=").append(pc)
            if (!detail.isNullOrEmpty()) append(' ').append(GravixStepLogs.scrub(detail))
        }
        GravixStepLogs.emit(sink, at, line)
    }
}

/** GRAVIX (0.1.2): the shared parts of the step logs ([GravixPubLog], `GravixJoinLog`). */
@Suppress("SwallowedException", "TooManyFunctions") // log helpers never throw: a fallback value instead
internal object GravixStepLogs {

    fun writes(configured: LoggingLevel, at: LoggingLevel): Boolean =
        configured != LoggingLevel.OFF && at != LoggingLevel.OFF && at >= configured

    /** Writes [line] (its detail already [scrub]bed; GxRedact again here) to [sink], else to GxLog's logger. Never throws. */
    fun emit(sink: GravixLogSink?, at: LoggingLevel, line: String) {
        try {
            val safe = GxRedact.redact(line)
            if (sink != null) {
                sink.onLine(at, safe)
            } else {
                GxLog.logger?.log(LoggingLevel.INFO, null, safe)
            }
        } catch (e: Exception) {
            // a log line must never break a negotiation or a join
        }
    }

    private val jwt = Regex("""eyJ[A-Za-z0-9_-]*(\.[A-Za-z0-9_-]*)*""")
    private val secretParam = Regex("""(?i)((?:access_token|token|key|secret|sig|signature)=)[^&\s]+""")

    /** Defence in depth on every detail: GxRedact's rules, any JWT shape, secret query parameters. */
    fun scrub(s: String): String {
        var out = GxRedact.redact(s)
        if (out.contains("eyJ")) out = jwt.replace(out, "<jwt>")
        if (out.contains('=')) out = secretParam.replace(out) { it.groupValues[1] + GxRedact.REDACTED }
        return out
    }

    /**
     * The m-sections of [sdp] in order, as `kind:mid/direction`; reads only `m=`, `a=mid` and direction lines.
     * `-` for none.
     */
    @Suppress("CyclomaticComplexMethod") // a small line parser
    fun mlines(sdp: String?): String {
        if (sdp.isNullOrEmpty()) return "-"
        val out = mutableListOf<String>()
        var kind: String? = null
        var mid: String? = null
        var dir: String? = null
        var rejected = false
        fun flush() {
            val k = kind ?: return
            val d = if (rejected) "rejected" else if (k == "application") null else (dir ?: "sendrecv")
            out.add("$k:${mid ?: "?"}${if (d == null) "" else "/$d"}")
        }
        for (raw in sdp.split('\n')) {
            val line = raw.trimEnd()
            when {
                line.startsWith("m=") -> {
                    flush()
                    val parts = line.substring(2).split(' ')
                    kind = parts.firstOrNull() ?: "?"
                    rejected = parts.size > 1 && parts[1] == "0"
                    mid = null
                    dir = null
                }
                kind != null && line.startsWith("a=mid:") -> mid = line.substring(6)
                kind != null && line in DIRECTIONS -> dir = line.substring(2)
            }
        }
        flush()
        return if (out.isEmpty()) "-" else out.joinToString(",")
    }

    private val DIRECTIONS = setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive")

    private val sdpStart = Regex("""v=0\r?\n|\r?\nm=|\r?\na=|^a=""", RegexOption.MULTILINE)
    private val secretAttr = Regex(
        """(?i)(ice-pwd|ice-ufrag|fingerprint|crypto|ufrag|pwd)(?::|\s)\s*[^\s,;)\]]+(?:\s+[0-9A-Fa-f:]{8,})?""",
    )

    /**
     * [error] as text that is safe for a log line: an SDP body in it is cut off (`<sdp omitted>`), ice-pwd / ufrag /
     * fingerprint / crypto values are redacted, line breaks folded, the length capped at [max]. Never throws.
     */
    fun safeError(error: Any?, max: Int = 300): String {
        return try {
            var s = error?.toString() ?: "-"
            val start = sdpStart.find(s)
            if (start != null) s = s.substring(0, start.range.first).trimEnd() + " <sdp omitted>"
            s = secretAttr.replace(s) { it.groupValues[1] + ":<redacted>" }
            s = s.replace(Regex("[\r\n]+"), " ")
            if (s.length > max) s.substring(0, max) + "..." else s
        } catch (e: Exception) {
            error?.javaClass?.simpleName ?: "-"
        }
    }

    /** libwebrtc's fixed reason in [error], shortened (never an SDP). */
    fun reason(error: String?): String {
        val s = error ?: return "unknown"
        return when {
            s.contains("order of m-lines") -> "m-line-order"
            s.contains("wrong state", ignoreCase = true) -> "wrong-state"
            s.contains("Incompatible send direction") -> "send-direction"
            s.contains("codec collision") || s.contains("payload type") -> "payload-type"
            s.isBlank() -> "unknown"
            else -> safeError(s, max = 80)
        }
    }

    /** A session description as a log value: its type and m-line list, never its body. */
    fun describe(type: String?, sdp: String?): String = "${type ?: "-"} mlines=${mlines(sdp)}"

    /**
     * An ICE candidate as a log value: mid, type and protocol (never its ufrag; no address).
     * e.g. `mid=0 typ=host proto=udp`.
     */
    fun candidate(c: IceCandidate?): String {
        if (c == null) return "-"
        return try {
            val parts = c.sdp.orEmpty().split(' ')
            val typ = parts.indexOf("typ").let { if (it >= 0 && it + 1 < parts.size) parts[it + 1] else "?" }
            val proto = parts.getOrNull(2)?.lowercase() ?: "?"
            "mid=${c.sdpMid ?: "-"} typ=$typ proto=$proto"
        } catch (e: Exception) {
            "mid=${c.sdpMid ?: "-"}"
        }
    }

    /** The scheme, host and port of [url] (never its path or query); `-` when there is none. */
    fun host(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return try {
            val u = URI(url.trim())
            val h = u.host ?: return "?"
            if (u.port >= 0) "$h:${u.port}" else h
        } catch (e: Exception) {
            "?"
        }
    }

    /**
     * A short stable id for [identity]: `u` + FNV-1a 32 bit, hex (the same on every device and run, and the same as
     * the Flutter SDK's `GravixJoinLog.shortId`, so two phones' lines can be matched).
     */
    fun shortId(identity: String): String {
        var h = 0x811c9dc5L
        for (c in identity) {
            h = h xor c.code.toLong()
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return "u" + h.toString(16).padStart(8, '0')
    }

    /** What a line says about an error: its type (never its message, which may carry a url or a token). */
    fun errorOf(e: Throwable): String {
        val cause = e.cause
        return if (cause != null && cause !== e) "${e.javaClass.simpleName}, cause ${cause.javaClass.simpleName}" else e.javaClass.simpleName
    }
}
