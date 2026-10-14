/*
 * Copyright 2024 LiveKit, Inc.
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

package com.gravixcloud.rtc.test.mock

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import com.gravixcloud.rtc.room.network.NetworkCallbackRegistry

class MockNetworkCallbackRegistry : NetworkCallbackRegistry {
    val networkCallbacks = mutableSetOf<NetworkCallback>()

    /** GRAVIX: how many onAvailable calls the registrations delivered for networks that already existed. */
    var initialAvailableCalls = 0
        private set

    override fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback) {
        networkCallbacks.add(networkCallback)
        // GRAVIX: as ConnectivityManager does, a new registration gets onAvailable at once for every network that
        // already matches (here: the Robolectric ConnectivityManager's networks)
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        cm?.allNetworks?.forEach { network ->
            initialAvailableCalls++
            networkCallback.onAvailable(network)
        }
    }

    override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
        networkCallbacks.remove(networkCallback)
    }
}
