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

package com.gravixcloud.rtc.noise

import com.gravixcloud.rtc.room.track.LocalAudioTrackOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GRAVIX (2026-10-21): the noise-suppression core against the fixture shared with the Flutter and React SDKs
 * (test/fixtures/ns_policy_cases.json, copied byte-identical to resources/noise): defaults, resolver, policy parser,
 * `[GravixNS]` log lines, benchmark cache key, iOS chip. Every case of every section runs; the counts are asserted
 * so a fixture section cannot silently go missing.
 */
class GravixNsPolicyFixtureTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/noise/ns_policy_cases.json")) { "missing fixture" }.readText(),
    ).jsonObject

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it != JsonNull }?.content

    private val policies: Map<String, GravixNsPolicy> by lazy {
        fixture["policies"]!!.jsonObject.mapValues { (_, v) -> GravixNsPolicy.parse(v).policy!! }
    }

    @Test
    fun defaultsMatchTheFixture() {
        val d = fixture["defaults"]!!.jsonObject
        val o = GravixNoiseSuppressionOptions()
        assertEquals(d["mode"].str(), o.mode.wireName)
        assertEquals(d["agcOrder"].str(), o.agcOrder.wireName)
        assertEquals(d["disableWebRtcNs"]!!.jsonPrimitive.boolean, o.disableWebRtcNs)
        assertEquals(d["hardwareNs"].str(), o.hardwareNs.wireName)
        assertEquals(d["headsetNs"].str(), o.headsetNs.wireName)
        assertEquals(d["musicBypass"]!!.jsonPrimitive.boolean, o.musicBypass)
        assertEquals(d["studioDownload"].str(), o.studioDownload.wireName)
        assertEquals(d["studioSource"].str(), o.studioSource.wireName)
        assertEquals(d["benchmarkP95Us"]!!.jsonPrimitive.int, o.benchmarkP95Us)
        assertEquals(d["watchdogFrameMs"]!!.jsonPrimitive.int * 1000, o.watchdogFrameUs)
        assertEquals(d["watchdogConsecutive"]!!.jsonPrimitive.int, o.watchdogConsecutive)
        assertEquals(d["thermalFallback"]!!.jsonPrimitive.boolean, o.thermalFallback)
        assertEquals(d["batterySaverFallback"]!!.jsonPrimitive.boolean, o.batterySaverFallback)
        assertEquals(d["studioAttenLimitDb"]!!.jsonPrimitive.double, o.studioAttenLimitDb, 0.0)
        assertEquals(d["studioP95BudgetUs"]!!.jsonPrimitive.int, o.studioP95BudgetUs)
        assertEquals(d["studioP95WindowMs"]!!.jsonPrimitive.int, o.studioP95WindowMs)
        assertEquals(d["studioLowLatency"]!!.jsonPrimitive.boolean, o.studioLowLatency)
        assertEquals(d["agcOrder"].str(), GravixNoiseSuppressionOptions.DEFAULT_AGC_ORDER.wireName)
        assertEquals(GravixNoiseSuppressionMode.OFF, GravixNoiseSuppressionOptions.LEGACY.mode)
        // owner 2026-10-21: on by default (RoomOptions carries GravixNoiseSuppressionOptions(), mode auto)
        val on = com.gravixcloud.rtc.RoomOptions().noiseSuppression
        assertEquals(d["enabledByDefault"]!!.jsonPrimitive.boolean, on != null && on.mode != GravixNoiseSuppressionMode.OFF)
        assertNull(o.policy)
        assertNull(o.policyUrl)
    }

    @Test
    fun namesRoundTrip() {
        for (m in GravixNoiseSuppressionMode.entries) assertEquals(m, GravixNoiseSuppressionMode.fromWireName(m.wireName))
        for (e in GravixNsEngine.entries) assertEquals(e, GravixNsEngine.fromWireName(e.wireName))
        for (r in GravixNsRoute.entries) assertEquals(r, GravixNsRoute.fromWireName(r.wireName))
        assertEquals("off", GravixNsEngine.NONE.wireName)
        assertNull(GravixNoiseSuppressionMode.fromWireName("loud"))
        // gx_ns_reason_name(): GX_NS_REASON_EXT_SLOW / GX_NS_REASON_EXT_SUPPRESS
        assertEquals("studio_slow", GravixNsReason.STUDIO_SLOW)
        assertEquals("studio_suppress", GravixNsReason.STUDIO_SUPPRESS)
    }

    @Test
    fun resolverCases() {
        val cases = fixture["resolve"]!!.jsonArray
        assertEquals(47, cases.size)
        for (c in cases.map { it.jsonObject }) {
            val name = c["name"].str()
            val r = GravixNsResolver.resolve(
                mode = GravixNoiseSuppressionMode.fromWireName(c["mode"].str())!!,
                facts = GravixNsDeviceFacts.fromJson(c["facts"]!!.jsonObject),
                policy = c["policy"].str()?.let { policies.getValue(it) },
                benchmark = (c["benchmark"] as? JsonObject)?.let { GravixNsBenchmark.fromJson(it) },
                benchmarkKey = c["benchmarkKey"].str(),
                studioAvailable = c["studioAvailable"]!!.jsonPrimitive.boolean,
                benchmarkP95Us = c["benchmarkP95Us"].str()?.toInt() ?: 3000,
            )
            assertEquals(name, c["expect"], r.toJson())
        }
    }

    @Test
    fun parserCases() {
        val cases = fixture["parse"]!!.jsonArray
        assertEquals(9, cases.size)
        for (c in cases.map { it.jsonObject }) {
            val name = c["name"].str()
            val src = c["source"]!!
            // a JSON-string source is a document to parse; anything else is an already decoded value
            val r = GravixNsPolicy.parse(if (src is JsonPrimitive && src.isString) src.content else src)
            val e = c["expect"]!!.jsonObject
            if (e["ok"]!!.jsonPrimitive.boolean.not()) {
                assertNull(name, r.policy)
                assertTrue("$name: ${r.error}", r.error!!.startsWith(e["error"].str()!!))
                continue
            }
            assertNull(name, r.error)
            val p = r.policy!!
            assertEquals(name, e["killAll"]!!.jsonPrimitive.boolean, p.killAll)
            assertEquals(name, e["killStudio"]!!.jsonPrimitive.boolean, p.killStudio)
            assertEquals(name, e["studioAllow"]!!.jsonPrimitive.int, p.studioAllow.size)
            assertEquals(name, e["studioDeny"]!!.jsonPrimitive.int, p.studioDeny.size)
            assertEquals(name, e["standardDeny"]!!.jsonPrimitive.int, p.standardDeny.size)
            assertEquals(name, e["skipped"]!!.jsonPrimitive.int, r.skippedRules)
            assertEquals(name, e["benchmarkP95Us"].str()?.toInt(), p.thresholds?.benchmarkP95Us)
        }
    }

    @Test
    fun policyToJsonRoundTrips() {
        val p = policies.getValue("fleet")
        val again = GravixNsPolicy.parse(p.toJson().toString()).policy!!
        assertEquals(p.toJson(), again.toJson())
        assertEquals(p, again)
    }

    @Test
    fun logLineCases() {
        val cases = fixture["log"]!!.jsonArray
        assertEquals(7, cases.size)
        for (c in cases.map { it.jsonObject }) {
            var detail = c["detail"].str()
            (c["switch"] as? JsonObject)?.let { s ->
                detail = GravixNsLog.switchDetail(
                    GravixNsEngine.fromWireName(s["from"].str())!!,
                    GravixNsEngine.fromWireName(s["to"].str())!!,
                    s["reason"].str()!!,
                )
            }
            (c["summary"] as? JsonObject)?.let { s ->
                detail = GravixNsLog.summaryDetail(
                    s["p50Us"]!!.jsonPrimitive.int,
                    s["p95Us"]!!.jsonPrimitive.int,
                    s["p99Us"]!!.jsonPrimitive.int,
                    s["maxUs"]!!.jsonPrimitive.int,
                    s["overBudget"]!!.jsonPrimitive.int,
                    s["cpuPercent"].str()?.toDouble(),
                )
            }
            val line = GravixNsLog.line(
                c["step"].str()!!,
                GravixNsEngine.fromWireName(c["engine"].str())!!,
                GravixNsRoute.fromWireName(c["route"].str()),
                c["rate"].str()?.toInt(),
                c["elapsedMs"].str()?.toLong(),
                detail,
            )
            assertEquals(c["expect"].str(), line)
        }
    }

    @Test
    fun benchmarkKeyAndIosChipCases() {
        val keys = fixture["benchmarkKey"]!!.jsonArray
        assertEquals(2, keys.size)
        for (c in keys.map { it.jsonObject }) {
            val i = c["input"]!!.jsonObject
            assertEquals(
                c["expect"].str(),
                GravixNsResolver.benchmarkKey(i["sdkVersion"].str()!!, i["modelSha256"].str()!!, i["runtimeVersion"].str()!!, i["osBuild"].str()!!),
            )
        }
        val chips = fixture["iosChip"]!!.jsonArray
        assertEquals(11, chips.size)
        for (c in chips) {
            val a = c as JsonArray
            assertEquals(a[0].str(), a[1].str()?.toInt(), GravixNsResolver.iosChipFromModel(a[0].str()))
        }
    }

    @Test
    fun ruleGlobCaseAndVersions() {
        val r = GravixNsRule(platform = "android", model = "SM-S9*", osMin = "13", osMax = "14.1")
        assertTrue(r.matches(platform = "android", model = "sm-s918b", osVersion = "14"))
        assertTrue(r.matches(platform = "android", model = "sm-s918b", osVersion = "14.1.0"))
        assertFalse(r.matches(platform = "android", model = "sm-s918b", osVersion = "14.2"))
        assertFalse(r.matches(platform = "android", model = "sm-s918b", osVersion = "12"))
        assertFalse("os range set, os unknown", r.matches(platform = "android", model = "sm-s918b"))
        assertFalse(r.matches(platform = "ios", model = "sm-s918b", osVersion = "14"))
        assertTrue(GravixNsRule(platform = "*").matches(platform = "web"))
        assertEquals(-1, GravixNsPolicy.compareVersions("14", "14.1"))
        assertNull(GravixNsPolicy.compareVersions("x", "1"))
    }

    @Test
    fun captureOptionsWhileOursRuns() {
        val base = LocalAudioTrackOptions()
        val ns = GravixNoiseSuppressionOptions()
        assertEquals(base, GravixNsCapture.options(base, ns, GravixNsEngine.NONE))
        val ours = GravixNsCapture.options(base, ns, GravixNsEngine.RNNOISE)
        assertFalse(ours.noiseSuppression)
        assertFalse(ours.autoGainControl)
        assertTrue(ours.echoCancellation)
        assertTrue(ours.highPassFilter)
        val webrtcAgc = GravixNsCapture.options(base, ns.copy(agcOrder = GravixNsAgcOrder.WEBRTC_BEFORE_NS), GravixNsEngine.DFN3)
        assertTrue(webrtcAgc.autoGainControl)
        assertTrue(GravixNsCapture.options(base, ns.copy(hardwareNs = GravixHwNs.ON), GravixNsEngine.RNNOISE).noiseSuppression)
        assertTrue(GravixNsCapture.options(base, ns.copy(disableWebRtcNs = false), GravixNsEngine.RNNOISE).noiseSuppression)
        // an app that turned them off keeps them off
        val appOff = LocalAudioTrackOptions(noiseSuppression = false, autoGainControl = false)
        assertEquals(appOff, GravixNsCapture.options(appOff, ns.copy(agcOrder = GravixNsAgcOrder.WEBRTC_BEFORE_NS, hardwareNs = GravixHwNs.ON), GravixNsEngine.RNNOISE))
        assertNotNull(GravixNoiseSuppressionOptions.LEGACY)
    }
}
