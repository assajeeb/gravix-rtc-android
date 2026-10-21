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

package com.gravixcloud.rtc.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Review 2026-10-21: a start() while a stop is still pending (stopService sent, onDestroy not yet run)
 * took the "already running -> update" path; onDestroy then ended the call service and nothing
 * restarted it (background mic silenced on Android 14). The start must go to the platform again.
 */
@RunWith(RobolectricTestRunner::class)
class GravixCallServiceRestartTest {
    private val cfg = GravixCallService.Config(null, null, 0, false, null, false, false, false)

    @Test
    fun startDuringAPendingStopStartsTheServiceAgain() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val old = Robolectric.buildService(GravixCallService::class.java).create().startCommand(0, 1)
        assertTrue(GravixCallService.running)
        shadowOf(app).clearStartedServices()

        GravixCallService.stop(app) // onDestroy not run yet
        val outcomes = mutableListOf<GravixCallService.Outcome>()
        GravixCallService.start(app, cfg) { outcomes.add(it) }
        assertNotNull("a new start intent goes to the platform", shadowOf(app).nextStartedService)
        assertTrue("no update on the dying instance", outcomes.isEmpty())

        old.destroy() // the pending stop lands
        assertTrue("the old instance's destroy does not fail the new start", outcomes.isEmpty())

        Robolectric.buildService(GravixCallService::class.java).create().startCommand(0, 2)
        assertEquals(1, outcomes.size)
        assertNull(outcomes[0].error)
        assertTrue(GravixCallService.running)
    }
}
