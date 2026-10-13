/*
 * Copyright 2023-2026 LiveKit, Inc.
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

import android.os.Build
import com.gravixcloud.rtc.BuildConfig
import com.gravixcloud.rtc.room.ClientProtocolVersion
import com.gravixcloud.rtc.room.SignalClient
import gravixcloud.GravixcloudModels

internal fun getClientInfo(
    clientProtocol: ClientProtocolVersion = ClientProtocolVersion.DATA_STREAM_RPC,
) = with(GravixcloudModels.ClientInfo.newBuilder()) {
    sdk = GravixcloudModels.ClientInfo.SDK.ANDROID
    // GRAVIX: report the upstream engine version (Version.ENGINE_VERSION), not the Gravix SDK version.
    version = BuildConfig.ENGINE_VERSION
    os = SignalClient.SDK_TYPE
    osVersion = Build.VERSION.RELEASE ?: ""

    val vendor = Build.MANUFACTURER ?: ""
    val model = Build.MODEL ?: ""
    deviceModel = ("$vendor $model").trim()
    this.clientProtocol = clientProtocol.value
    build()
}
