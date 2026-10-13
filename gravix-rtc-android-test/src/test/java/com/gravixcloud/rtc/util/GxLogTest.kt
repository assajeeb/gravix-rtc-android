/*
 * Copyright 2024-2026 LiveKit, Inc.
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

package com.gravixcloud.rtc.util

import com.gravixcloud.rtc.GravixRtc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GxLogTest {

    @Test
    fun log() {
        var called = false
        GravixRtc.loggingLevel = LoggingLevel.INFO
        GxLog.log(LoggingLevel.ERROR) {
            called = true
            ""
        }
        assertTrue(called)
    }

    @Test
    fun noLog() {
        var called = false
        GravixRtc.loggingLevel = LoggingLevel.OFF
        GxLog.log(LoggingLevel.VERBOSE) {
            called = true
            ""
        }
        assertFalse(called)
    }
}
