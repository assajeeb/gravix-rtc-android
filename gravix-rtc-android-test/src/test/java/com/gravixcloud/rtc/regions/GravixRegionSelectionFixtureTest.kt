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

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GRAVIX: region selection with hysteresis and the home-region margin, driven by the fixture shared with the
 * Flutter and React SDKs (test/fixtures/region_selection_cases.json, copied unchanged): same fixture, same choice.
 */
class GravixRegionSelectionFixtureTest {

    private val fixture = RegionTestKit.resource("region_selection_cases.json").jsonObject

    @Test
    fun selectCases() {
        val cases = fixture["select"]!!.jsonArray
        assertEquals(15, cases.size)
        for (e in cases) {
            val c = e.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val samples = c["samples"]!!.jsonArray.map { s ->
                val o = s.jsonObject
                GravixRegionSample(
                    region = o["region"]!!.jsonPrimitive.content,
                    status = o["status"]!!.jsonPrimitive.content,
                    rttMs = o["rtt_ms"]?.jsonPrimitive?.longOrNull,
                )
            }
            val out = GravixRegionSelector.select(
                samples,
                anchor = c["anchor"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                challenger = GravixRegionChallenger.fromJson(c["challenger"]),
                anchorKnown = c["anchorKnown"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            val ex = c["expect"]
            if (ex == null || ex == JsonNull) {
                assertNull(name, out)
                continue
            }
            ex as JsonObject
            assertEquals(name, ex["region"]!!.jsonPrimitive.content, out?.region)
            assertEquals(name, ex["reason"]!!.jsonPrimitive.content, out?.reason?.wireName)
            val exCh = ex["challenger"]?.takeIf { it != JsonNull }
            assertEquals(name, exCh, out?.challenger?.toJson())
        }
    }

    @Test
    fun homeMarginCases() {
        val cases = fixture["home"]!!.jsonArray
        assertEquals(7, cases.size)
        for (e in cases) {
            val c = e.jsonObject
            assertEquals(
                c["name"]!!.jsonPrimitive.content,
                c["expect"]!!.jsonPrimitive.boolean,
                GravixRegionSelector.homeRegionWithinMargin(c["homeMs"]!!.jsonPrimitive.long, c["fastestMs"]!!.jsonPrimitive.long),
            )
        }
    }
}
