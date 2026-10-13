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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** GRAVIX: helpers for the region tests (virtual time: no wall-clock timing, so no flaky budget tests). */
internal object RegionTestKit {
    const val WALL_BASE_MS = 1_760_000_000_000L

    fun resource(name: String): JsonElement {
        val text = requireNotNull(RegionTestKit::class.java.getResource("/regions/$name")) { "missing fixture $name" }.readText()
        return Json.parseToJsonElement(text)
    }

    /** The measurement's clocks follow the test scheduler's virtual time (wall = a fixed base + virtual time). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestScope.useVirtualClock(wallOffsetMs: () -> Long = { 0L }) {
        GravixRegionMeasurements.clock = object : GravixClock {
            override fun wallMs(): Long = WALL_BASE_MS + wallOffsetMs() + testScheduler.currentTime
            override fun elapsedMs(): Long = testScheduler.currentTime
        }
    }

    fun resetClock() {
        GravixRegionMeasurements.clock = GravixClock.SYSTEM
    }

    /** [o], running on the test scheduler. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestScope.onTestScheduler(o: GravixRegionMeasureOptions): GravixRegionMeasureOptions =
        o.also { it.dispatcher = StandardTestDispatcher(testScheduler) }

    /**
     * Samples from a table: rtt[region][i] (the last value repeats); a null value or an absent region = failure.
     * [delayMs] of virtual time per sample.
     */
    class Table(private val rtt: Map<String, List<Long?>?>, private val delayMs: Long = 0) : GravixRegionSampler {
        val calls = mutableListOf<String>()
        private val seen = HashMap<String, Int>()
        override suspend fun sample(region: GravixRegionUrl): Long? {
            calls.add(region.region)
            val i = seen[region.region] ?: 0
            seen[region.region] = i + 1
            if (delayMs > 0) delay(delayMs)
            val list = rtt[region.region] ?: return null
            return list[if (i < list.size) i else list.size - 1]
        }
    }

    fun unknownNet() = GravixNetworkReader { "unknown" }
    fun net(name: String) = GravixNetworkReader { name }
}
