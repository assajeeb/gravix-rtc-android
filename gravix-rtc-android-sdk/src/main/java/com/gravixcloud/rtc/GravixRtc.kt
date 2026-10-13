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

package com.gravixcloud.rtc

import android.app.Application
import android.content.Context
import com.gravixcloud.rtc.GravixRtc.loggingLevel
import com.gravixcloud.rtc.dagger.DaggerGravixRtcComponent
import com.gravixcloud.rtc.dagger.RTCModule
import com.gravixcloud.rtc.dagger.create
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.util.GxLog
import com.gravixcloud.rtc.util.LoggingLevel

/**
 * The main entry point into using Gravix.
 *
 * @see [GravixRtc.create]
 */
object GravixRtc {
    /**
     * [LoggingLevel] to use for Gravix logs. Set to [LoggingLevel.OFF] to turn off logs.
     *
     * Defaults to [LoggingLevel.OFF]
     */
    @JvmStatic
    var loggingLevel: LoggingLevel
        get() = GxLog.loggingLevel
        set(value) {
            GxLog.loggingLevel = value
        }

    /**
     * The [GxLog.Logger] to use for Gravix logs.
     *
     * Default implementation prints to logcat.
     */
    @JvmStatic
    var logger: GxLog.Logger?
        get() = GxLog.logger
        set(value) {
            GxLog.logger = value
        }

    /**
     * Enables logs for the underlying WebRTC sdk logging. Used in conjunction with [loggingLevel].
     *
     * Note: WebRTC logging is very noisy and should only be used to diagnose native WebRTC issues.
     */
    @JvmStatic
    var enableWebRTCLogging: Boolean = false

    /**
     * Certain WebRTC classes need to be initialized prior to use.
     *
     * This does not need to be called under normal circumstances, as [GravixRtc.create]
     * will handle this for you.
     */
    fun init(appContext: Context) {
        RTCModule.libWebrtcInitialization(appContext)
    }

    /**
     * Create a Room object.
     */
    @JvmStatic // GRAVIX: Java calls GravixRtc.create(context) (was GravixRtc.INSTANCE.create with every argument)
    @JvmOverloads
    fun create(
        appContext: Context,
        options: RoomOptions = RoomOptions(),
        overrides: GravixRtcOverrides = GravixRtcOverrides(),
    ): Room {
        val ctx = appContext.applicationContext

        if (ctx !is Application) {
            GxLog.w { "Application context was not found, this may cause memory leaks." }
        }

        val component = DaggerGravixRtcComponent
            .factory()
            .create(ctx, overrides)

        val room = component.roomFactory().create(ctx)
        room.setRoomOptions(options)

        return room
    }
}
