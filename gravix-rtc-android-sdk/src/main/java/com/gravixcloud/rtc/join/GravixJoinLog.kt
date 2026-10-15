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

import com.gravixcloud.rtc.util.GravixLogSink
import com.gravixcloud.rtc.util.GravixStepLogs
import com.gravixcloud.rtc.util.LoggingLevel

/**
 * GRAVIX (0.1.2): one secret-free line per step of a join, so a device log shows where a join spent its time or why
 * it stopped.
 *
 * ```
 * [GravixJoin] <step> key=<room>|u<id hash>|pub=<bool> [+<ms since tap>ms] [<detail>]
 * [GravixJoin] prepare key=r1|u5c1e2a07|pub=false +0ms new
 * [GravixJoin] tokenOk key=r1|u5c1e2a07|pub=false +312ms via=fetched
 * [GravixJoin] connectOk key=r1|u5c1e2a07|pub=false +905ms in=590ms
 * ```
 *
 * Never in a line: a token, a JWT, a url's path or query (hosts only), an error message (types only), a full identity
 * (a short stable hash, the same as the Flutter SDK's). Written by [GravixJoin] and its [GravixPrejoin]s and by
 * `Room.connect` (`roomConnect` / `roomConnectOk` / `roomConnectFail`, keyed from the token's grant).
 *
 * On by default at [LoggingLevel.INFO] (a few lines per join), independent of `GravixRtc.loggingLevel`;
 * [LoggingLevel.VERBOSE] adds the warm starts and defensive catches. The lines go to the [GravixJoin]'s own sink, else
 * [sink], else `GravixRtc.logger` (logcat by default).
 */
object GravixJoinLog {
    /** The stable prefix of every line (grep for it). */
    const val PREFIX = "[GravixJoin]"

    /** The lowest level written: [LoggingLevel.INFO] (default), [LoggingLevel.VERBOSE] (more), or [LoggingLevel.OFF]. */
    @JvmStatic
    @Volatile
    var level: LoggingLevel = LoggingLevel.INFO

    /** Where the lines go when the writer has no sink of its own (null: `GravixRtc.logger`). */
    @JvmStatic
    @Volatile
    var sink: GravixLogSink? = null

    /** A short stable id for [identity]: `u` + FNV-1a 32 bit, hex (as the Flutter SDK, so two phones' lines match). */
    @JvmStatic
    fun shortId(identity: String): String = GravixStepLogs.shortId(identity)

    /** The host (and port) of [url], never its path or query; `-` when there is none. */
    @JvmStatic
    fun host(url: String?): String = GravixStepLogs.host(url)

    internal fun writes(at: LoggingLevel): Boolean = GravixStepLogs.writes(level, at)

    /** `<room>|u<hash>|pub=<bool>` of a session key (room \u0000 identity \u0000 publish right). */
    internal fun keyOf(sessionKey: String?): String {
        if (sessionKey == null) return "-"
        val p = sessionKey.split('\u0000')
        if (p.size != 3) return sessionKey.replace('\u0000', '|')
        return "${p[0]}|${shortId(p[1])}|pub=${p[2]}"
    }

    internal fun format(step: String, key: String?, sinceTapMs: Long?, detail: String?): String = buildString {
        append(PREFIX).append(' ').append(step).append(" key=").append(keyOf(key))
        if (sinceTapMs != null) append(" +").append(sinceTapMs).append("ms")
        if (!detail.isNullOrEmpty()) append(' ').append(GravixStepLogs.scrub(detail))
    }

    /** Writes one step line to [to] (else [sink]). Never throws. */
    @Suppress("LongParameterList")
    internal fun step(
        step: String,
        key: String? = null,
        sinceTapMs: Long? = null,
        detail: String? = null,
        at: LoggingLevel = LoggingLevel.INFO,
        to: GravixLogSink? = null,
    ) {
        if (!writes(at)) return
        GravixStepLogs.emit(to ?: sink, at, format(step, key, sinceTapMs, detail))
    }

    /** What a line says about an error: its type (and its cause's), never its message. */
    internal fun errorOf(e: Throwable): String = GravixStepLogs.errorOf(e)
}
