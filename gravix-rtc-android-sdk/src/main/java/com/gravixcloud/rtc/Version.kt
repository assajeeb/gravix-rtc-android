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

package com.gravixcloud.rtc

/**
 * Version information about Gravix
 */
object Version {
    /**
     * The current Gravix SDK version.
     */
    const val CLIENT_VERSION = BuildConfig.VERSION_NAME

    /**
     * The upstream engine version this SDK is built on (GRAVIX). This, not [CLIENT_VERSION], is what the
     * server receives as the client SDK version, so server-side rules keyed on the Android SDK version
     * evaluate against the engine that is actually running (the Flutter SDK does the same).
     */
    const val ENGINE_VERSION = BuildConfig.ENGINE_VERSION
}
