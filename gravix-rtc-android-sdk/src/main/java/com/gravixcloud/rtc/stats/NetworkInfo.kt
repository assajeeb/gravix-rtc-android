/*
 * Copyright 2023-2024 LiveKit, Inc.
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

package com.gravixcloud.rtc.stats

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.gravixcloud.rtc.util.GxLog

interface NetworkInfo {
    fun getNetworkType(): NetworkType
}

internal class AndroidNetworkInfo(private val context: Context) : NetworkInfo {
    /**
     * GRAVIX (region failover evidence): true when an active network with internet exists, false when there is none,
     * null when the system gives no answer. Deliberately not [getNetworkType]: its UNKNOWN also covers a CONNECTED
     * network on a transport outside the mapping below (satellite, thread, future ones).
     */
    internal fun gravixHasActiveNetwork(): Boolean? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val nw = cm?.activeNetwork
        when {
            cm == null -> null
            nw == null -> false
            else -> cm.getNetworkCapabilities(nw)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
        }
    } catch (e: SecurityException) {
        GxLog.d(e) { "no network state permission: network availability unknown" }
        null
    }

    override fun getNetworkType(): NetworkType {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkType.UNKNOWN

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
            val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return NetworkType.UNKNOWN
            return when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) -> NetworkType.OTHER
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) -> NetworkType.OTHER
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> NetworkType.OTHER
                else -> NetworkType.UNKNOWN
            }
        } else {
            val info = connectivityManager.activeNetworkInfo
            if (info == null || !info.isConnected) return NetworkType.UNKNOWN
            return when (info.type) {
                ConnectivityManager.TYPE_BLUETOOTH -> NetworkType.BLUETOOTH
                ConnectivityManager.TYPE_DUMMY -> NetworkType.UNKNOWN
                ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_MOBILE_DUN -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_MOBILE_HIPRI -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_MOBILE_MMS -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_MOBILE_SUPL -> NetworkType.CELLULAR
                ConnectivityManager.TYPE_VPN -> NetworkType.VPN
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_WIMAX -> NetworkType.CELLULAR
                else -> NetworkType.UNKNOWN
            }
        }
    }
}

enum class NetworkType(val protoName: String) {
    WIFI("wifi"),
    ETHERNET("ethernet"),
    CELLULAR("cellular"),
    VPN("vpn"),
    BLUETOOTH("bluetooth"),
    OTHER("other"),
    UNKNOWN(""),
}
