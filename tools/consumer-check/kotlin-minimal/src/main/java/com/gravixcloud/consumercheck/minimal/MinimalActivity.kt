// Copyright 2026 Gravity Compile
// SPDX-License-Identifier: Apache-2.0
//
// Public SDK calls a Kotlin app makes with nothing but the SDK on its classpath. Each block is a call whose
// signature once named an implementation-only dependency (found by the Phase 4 API scan):
//  - regions report -> kotlinx-serialization JsonObject (now a JSON String)
//  - Room.events / connect -> kotlinx-coroutines Flow / suspend (coroutines is now `api`)
//  - E2EE options and DataReceived -> protobuf enums (protobuf-javalite is now `api`)
//  - token claims (TokenPayload.jwt -> jwtdecode; now internal, the claim getters stay)
package com.gravixcloud.consumercheck.minimal

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.e2ee.E2EEOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.token.TokenPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MinimalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val room by lazy { GravixRtc.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The start-up region measurement as JSON, ready for the app's own analytics upload.
        val report: String? = GravixRegionMeasurements.regionsMeasuredReport()
        Log.i(TAG, "regions report: ${report?.length ?: 0} chars")

        room.e2eeOptions = E2EEOptions(sharedKey = "consumer-check-only")
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.DataReceived -> Log.i(TAG, "data ${event.data.size} B, ${event.encryptionType.name}")
                    else -> Unit
                }
            }
        }
        val token = intent.getStringExtra("token") ?: return
        val claims = TokenPayload(token)
        Log.i(TAG, "token for ${claims.identity}, expires ${claims.expiresAt}")
        val url = intent.getStringExtra("url") ?: return
        scope.launch { room.connect(url, token) }
    }

    override fun onDestroy() {
        scope.cancel()
        room.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "GravixMinimal"
    }
}
