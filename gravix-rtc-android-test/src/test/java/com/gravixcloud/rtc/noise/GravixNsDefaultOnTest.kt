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

import com.gravixcloud.rtc.RoomOptions
import org.junit.Assert.assertEquals
import org.junit.Test

/** GRAVIX (owner 2026-10-21): noise suppression is on (auto) by default; explicit off stays off. */
class GravixNsDefaultOnTest {
    @Test
    fun roomOptionsDefaultIsAuto() {
        assertEquals(GravixNoiseSuppressionMode.AUTO, RoomOptions().noiseSuppression?.mode)
        assertEquals(GravixNoiseSuppressionOptions(), RoomOptions().noiseSuppression)
    }

    @Test
    fun theOldJvmConstructorIsOnToo() {
        val o = RoomOptions(false, false, null, null, null, null, null, null, null, null, null)
        assertEquals(GravixNoiseSuppressionMode.AUTO, o.noiseSuppression?.mode)
    }

    @Test
    fun explicitOffStaysOff() {
        val off = RoomOptions(noiseSuppression = GravixNoiseSuppressionOptions(mode = GravixNoiseSuppressionMode.OFF))
        assertEquals(GravixNoiseSuppressionMode.OFF, off.noiseSuppression?.mode)
        assertEquals(GravixNoiseSuppressionMode.OFF, RoomOptions(noiseSuppression = GravixNoiseSuppressionOptions.LEGACY).noiseSuppression?.mode)
        // off resolves to no engine of ours: WebRTC's own processing
        val r = GravixNsResolver.resolve(GravixNoiseSuppressionMode.OFF, GravixNsDeviceFacts(platform = "android"))
        assertEquals(GravixNsEngine.NONE, r.engine)
    }

    /** Review 2026-10-21: a Room with default options (connection check, a second Room) never undoes the app's off. */
    @Test
    fun joinWithDefaultsNeverOverridesAnEarlierConfigure() {
        val def = GravixNoiseSuppressionOptions()
        val off = GravixNoiseSuppressionOptions.LEGACY
        val studio = GravixNoiseSuppressionOptions(mode = GravixNoiseSuppressionMode.STUDIO)
        assertEquals(true, GravixNoiseSuppression.joinShouldConfigure(null, def)) // first join
        assertEquals(false, GravixNoiseSuppression.joinShouldConfigure(off, def))
        assertEquals(false, GravixNoiseSuppression.joinShouldConfigure(off, null))
        assertEquals(false, GravixNoiseSuppression.joinShouldConfigure(off, off)) // a runtime setMode survives the join
        assertEquals(true, GravixNoiseSuppression.joinShouldConfigure(off, studio)) // explicit options apply
        assertEquals(false, GravixNoiseSuppression.joinShouldConfigure(studio, def))
    }
}
