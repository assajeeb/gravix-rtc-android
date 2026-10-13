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

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gravixcloud.rtc.util.GxLog
import java.util.concurrent.ConcurrentHashMap

/**
 * GRAVIX: where the last region list and the per-network answers are kept, so a start-up with the gateway
 * unreachable still measures and a start-up on a known network has an answer at once.
 */
interface GravixRegionListStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** This process only. */
class GravixMemoryRegionListStore : GravixRegionListStore {
    private val m = ConcurrentHashMap<String, String>()
    override fun read(key: String): String? = m[key]
    override fun write(key: String, value: String) {
        m[key] = value
    }
}

/**
 * Kept across launches in the app's private shared preferences (`gravix_regions`): a cold start with the gateway
 * unreachable still measures the regions it saw last time. Writes are applied in the background and never throw.
 */
class GravixSharedPrefsRegionListStore(private val prefs: SharedPreferences) : GravixRegionListStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override fun read(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        null // a non-string value under our key: treat as absent
    }

    override fun write(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            GxLog.w(e) { "region list not persisted" }
        }
    }

    companion object {
        const val PREFS_NAME = "gravix_regions"
    }
}

/** Reads a network property (type or cache key) for the measurement; null = unknown. */
fun interface GravixNetworkReader {
    suspend fun read(): String?
}

/** Time sources of the measurement (replaceable in tests). */
interface GravixClock {
    /** Wall clock, ms since the epoch (answer timestamps, TTLs, persisted state). */
    fun wallMs(): Long

    /** Monotonic, ms (elapsed time of a run). */
    fun elapsedMs(): Long

    companion object {
        @JvmField
        val SYSTEM: GravixClock = object : GravixClock {
            override fun wallMs(): Long = System.currentTimeMillis()
            override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
        }
    }
}

/** The Android network type for the budget and cache key: `wifi`, `cellular`, `ethernet` or `unknown`. */
object GravixRegionNetwork {

    @JvmStatic
    fun type(context: Context): String = read(context).first

    /** The per-network cache key: the type, plus `+vpn` while a VPN is up (VPN on/off cache apart). */
    @JvmStatic
    fun key(context: Context): String = read(context).let { (type, vpn) -> if (vpn) "$type+vpn" else type }

    private fun read(context: Context): Pair<String, Boolean> = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) {
            "unknown" to false
        } else {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
            type to caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    } catch (e: Exception) {
        GxLog.d(e) { "network type unknown" }
        "unknown" to false
    }
}
