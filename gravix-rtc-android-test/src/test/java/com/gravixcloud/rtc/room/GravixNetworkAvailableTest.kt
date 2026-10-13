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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.stats.AndroidNetworkInfo
import com.gravixcloud.rtc.stats.NetworkInfo
import com.gravixcloud.rtc.stats.NetworkType
import com.gravixcloud.rtc.test.mock.MockWebSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * GRAVIX (review finding 2026-10-11 19:51Z): the failover's evidence asked "is there a network?" through the
 * transport mapping of [AndroidNetworkInfo.getNetworkType], whose UNKNOWN also covers a CONNECTED network on a
 * transport outside the mapped list (satellite, thread, future ones). On such a network the session could never
 * leave a failed region. "Available" must mean "an active network exists", and an unknown answer must not block
 * the failover.
 */
@RunWith(RobolectricTestRunner::class)
class GravixNetworkAvailableTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun client(networkInfo: NetworkInfo) = SignalClient(
        MockWebSocketFactory(),
        Json,
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.Unconfined,
        networkInfo = networkInfo,
    )

    /** The active network has [transport] and INTERNET. */
    private fun activeNetworkOn(transport: Int) {
        val network = cm.activeNetwork!!
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(network, caps)
    }

    @Test
    fun aConnectedNetworkOnAnUnmappedTransportIsAvailable() {
        activeNetworkOn(TRANSPORT_SATELLITE)
        val info = AndroidNetworkInfo(context)
        assertEquals("the transport mapping does not know satellite", NetworkType.UNKNOWN, info.getNetworkType())
        assertTrue("an active network exists: available", client(info).gravixNetworkAvailable())
    }

    @Test
    fun aMappedNetworkIsAvailable() {
        activeNetworkOn(NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(client(AndroidNetworkInfo(context)).gravixNetworkAvailable())
    }

    @Test
    fun noActiveNetworkIsNotAvailable() {
        shadowOf(cm).setDefaultNetworkActive(false)
        shadowOf(cm).setActiveNetworkInfo(null)
        assertFalse(client(AndroidNetworkInfo(context)).gravixNetworkAvailable())
    }

    @Test
    fun anUnknownAnswerDoesNotBlockTheFailover() {
        // a NetworkInfo that cannot tell (an app/test replacement): not evidence that the device is offline
        val unknown = object : NetworkInfo {
            override fun getNetworkType() = NetworkType.UNKNOWN
        }
        assertTrue(client(unknown).gravixNetworkAvailable())
    }

    @Test
    fun theStrategyLeavesAFailedRegionWhenTheNetworkIsUnmappedButUp() = runBlocking {
        activeNetworkOn(TRANSPORT_SATELLITE)
        val signal = client(AndroidNetworkInfo(context))
        val evidence = object : GravixFailoverEvidence {
            override fun networkChangedSince(sinceMs: Long) = false
            override fun networkAvailable() = signal.gravixNetworkAvailable()
            override suspend fun reachable(url: String, timeoutMs: Long) = !url.contains("blr1")
        }
        setRegionUrlProviderTesting(true)
        try {
            val provider = RegionUrlProvider(java.net.URI(BLR), "token", OkHttpClient(), Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("blr1", BLR, 0), RegionInfo("sgp1", SGP, 1))))
            }
            com.gravixcloud.rtc.regions.GravixRegionMeasurements.clear() // no measurement: server order, the failed url last
            val strategy = GravixRegionFailover(GravixRecoveryOptions(), provider, evidence)
            val attempt = GravixRegionAttempt(retry = 2, full = true, sessionUrl = BLR, lastUrl = BLR, lastRefused = false, startedAtMs = 0)
            strategy.reconnectUrl(attempt.copy(retry = 1)) // the same-url retry
            assertEquals("blr1 is down, sgp1 answers, the network is up: leave", SGP, strategy.reconnectUrl(attempt))
        } finally {
            setRegionUrlProviderTesting(false)
        }
    }

    companion object {
        /** NetworkCapabilities.TRANSPORT_SATELLITE (API 35); not in the SDK's transport mapping. */
        const val TRANSPORT_SATELLITE = 10
        const val BLR = "wss://blr1.gravixcloud.test"
        const val SGP = "wss://sgp1.gravixcloud.test"
    }
}
