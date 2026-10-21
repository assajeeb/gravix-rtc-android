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

/**
 * GRAVIX (2026-10-21): how the Android call foreground service behaves ([GravixForegroundService]); the Flutter SDK's
 * `GravixForegroundServiceOptions`, same names. **Off by default** ([enabled] false): the SDK never starts it.
 *
 * @property enabled start the service for every room that uses these options (connect -> start, microphone/camera
 *   publish -> type upgrade, final disconnect / release -> stop after a grace period). False: no notification, no
 *   behaviour change.
 * @property notificationTitle notification title; null = the app's label.
 * @property notificationText notification text; null = "In a room".
 * @property notificationIcon small icon (a drawable resource id); 0 = the manifest meta-data
 *   `com.gravixcloud.rtc.call_notification_icon`, else the app icon.
 * @property showLeaveAction a "Leave" action; a tap reaches [GravixForegroundService.leaveRequests] (the SDK does not
 *   disconnect by itself).
 * @property leaveActionLabel label of the Leave action; null = "Leave".
 * @property includeCamera add the `camera` type when the camera is published (CAMERA granted). Off: the camera stops in
 *   the background as before; microphone and playback keep going.
 * @property claimMicrophoneAtStart ask for the `microphone` type when the service starts (at connect, while the app is
 *   visible), for listeners too: Android 14+ refuses to ADD it from the background later. Granted only while
 *   RECORD_AUDIO is granted. False: `microphone` is added on the first microphone publish.
 * @property backgroundKeepAlive hold a partial wake lock and a low-latency Wi-Fi lock while the service runs, so the
 *   CPU and Wi-Fi do not doze under a call with the screen off.
 */
data class GravixForegroundServiceOptions @JvmOverloads constructor(
    val enabled: Boolean = false,
    val notificationTitle: String? = null,
    val notificationText: String? = null,
    val notificationIcon: Int = 0,
    val showLeaveAction: Boolean = false,
    val leaveActionLabel: String? = null,
    val includeCamera: Boolean = false,
    val claimMicrophoneAtStart: Boolean = true,
    val backgroundKeepAlive: Boolean = true,
)
