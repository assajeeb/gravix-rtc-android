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

/**
 * GRAVIX (2026-10-21): the publisher's answer keeps our microphone sections on DTX (the JS SDK's mungeOpusAnswerFmtp,
 * React fba6034). The SFU sometimes answers the mic section by mirroring the offer (`stereo=1`, no `usedtx`), so the
 * encoder ran without DTX (a silent mic sent ~37 kbps / 50 packets per second) and in stereo. Before
 * setRemoteDescription(answer) every registered opus section gets `usedtx=1` unless the publication turned DTX off,
 * and `stereo=1` only for a stereo publication (this SDK publishes mono). Other sections and attributes are untouched.
 */
internal object GravixOpusAnswer {
    /** What one published opus section must say. */
    data class Prefs(val dtx: Boolean, val stereo: Boolean)

    /** The fmtp config with [p] applied (`usedtx` / `stereo` replaced, the rest kept in order). */
    fun fmtp(config: String, p: Prefs): String {
        val attrs = config.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { a -> a.substringBefore('=').trim().let { it != "usedtx" && it != "stereo" } }
            .toMutableList()
        if (p.dtx) attrs.add("usedtx=1")
        if (p.stereo) attrs.add("stereo=1")
        return attrs.joinToString(";")
    }

    /** [sdp] with the opus fmtp of every section whose `a=mid` is in [byMid] pinned; unchanged when nothing matches. */
    fun munge(sdp: String, byMid: Map<String, Prefs>): String {
        if (byMid.isEmpty()) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol).toMutableList()
        // sections: [start, end) of each m= block
        val starts = lines.indices.filter { lines[it].startsWith("m=") }
        for ((i, start) in starts.withIndex()) {
            val end = if (i + 1 < starts.size) starts[i + 1] else lines.size
            if (!lines[start].startsWith("m=audio")) continue
            val mid = (start until end).map { lines[it] }.firstOrNull { it.startsWith("a=mid:") }?.substringAfter("a=mid:")?.trim()
            val prefs = byMid[mid ?: continue] ?: continue
            val opusPt = (start until end).map { lines[it] }
                .firstOrNull { it.startsWith("a=rtpmap:") && it.substringAfter(' ').lowercase().startsWith("opus/") }
                ?.substringAfter("a=rtpmap:")?.substringBefore(' ') ?: continue
            val fmtpIdx = (start until end).firstOrNull { lines[it].startsWith("a=fmtp:$opusPt ") }
            if (fmtpIdx != null) {
                lines[fmtpIdx] = "a=fmtp:$opusPt " + fmtp(lines[fmtpIdx].substringAfter("a=fmtp:$opusPt "), prefs)
            } else {
                val config = fmtp("", prefs)
                if (config.isNotEmpty()) {
                    val rtpmapIdx = (start until end).first { lines[it].startsWith("a=rtpmap:$opusPt ") }
                    lines.add(rtpmapIdx + 1, "a=fmtp:$opusPt $config")
                    return munge(lines.joinToString(eol), byMid - mid) // indices moved: redo the rest
                }
            }
        }
        return lines.joinToString(eol)
    }
}
