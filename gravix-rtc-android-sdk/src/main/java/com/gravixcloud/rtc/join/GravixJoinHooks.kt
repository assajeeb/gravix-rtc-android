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

package com.gravixcloud.rtc.join

import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.room.Room
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

/** GRAVIX (0.1.2): builds the Room of a join (your listeners bound before the connect). Called once per join. */
fun interface GravixJoinRoomFactory {
    fun create(): Room
}

/**
 * GRAVIX (0.1.2): the connect of a join -- your own steps around it (audio session, microphone in the first offer),
 * or the default `room.connect(credentials.url, credentials.token, connectOptions)`. Kotlin:
 * `GravixJoinConnect { room, c -> myService.connect(room, c.url, c.token) }`; Java: [fromFuture].
 */
interface GravixJoinConnect {
    suspend fun connect(room: Room, credentials: GravixJoinCredentials)

    companion object {
        @JvmSynthetic
        operator fun invoke(block: suspend (Room, GravixJoinCredentials) -> Unit): GravixJoinConnect =
            object : GravixJoinConnect {
                override suspend fun connect(room: Room, credentials: GravixJoinCredentials) = block(room, credentials)
            }

        /** Java: a connect that returns a future (complete it when connected; fail it when the connect failed). */
        @JvmStatic
        fun fromFuture(connect: BiFunction<Room, GravixJoinCredentials, CompletableFuture<*>>): GravixJoinConnect =
            object : GravixJoinConnect {
                override suspend fun connect(room: Room, credentials: GravixJoinCredentials) {
                    connect.apply(room, credentials).awaitGravix()
                }
            }
    }
}

/**
 * GRAVIX (0.1.2): the leave of a join's Room. Default: `room.disconnect()` then `room.release()` (the local tracks stop
 * with the session). Kotlin: `GravixJoinLeave { room -> myService.teardown(room) }`; Java: [fromFuture].
 */
interface GravixJoinLeave {
    suspend fun leave(room: Room)

    companion object {
        @JvmSynthetic
        operator fun invoke(block: suspend (Room) -> Unit): GravixJoinLeave =
            object : GravixJoinLeave {
                override suspend fun leave(room: Room) = block(room)
            }

        /** Java: a leave that returns a future. */
        @JvmStatic
        fun fromFuture(leave: Function<Room, CompletableFuture<*>>): GravixJoinLeave =
            object : GravixJoinLeave {
                override suspend fun leave(room: Room) {
                    leave.apply(room).awaitGravix()
                }
            }

        /** The default leave: `room.disconnect()`, then `room.release()`. */
        @JvmField
        val DEFAULT: GravixJoinLeave = object : GravixJoinLeave {
            override suspend fun leave(room: Room) {
                try {
                    room.disconnect()
                } catch (e: Exception) {
                    GravixJoinLog.step("error", detail = "defaultLeave: disconnect threw ${e.javaClass.simpleName}")
                }
                try {
                    room.release()
                } catch (e: Exception) {
                    GravixJoinLog.step("error", detail = "defaultLeave: release threw ${e.javaClass.simpleName}")
                }
            }
        }
    }
}

/**
 * GRAVIX (0.1.2): your work that must be done before a switched-to room connects (e.g. the old screen closed). False
 * cancels the switch (the old session is already left by then). Kotlin: `GravixSwitchBeforeConnect { closeOld(); true }`;
 * Java: [fromFuture].
 */
interface GravixSwitchBeforeConnect {
    suspend fun beforeConnect(): Boolean

    companion object {
        @JvmSynthetic
        operator fun invoke(block: suspend () -> Boolean): GravixSwitchBeforeConnect =
            object : GravixSwitchBeforeConnect {
                override suspend fun beforeConnect(): Boolean = block()
            }

        @JvmStatic
        fun fromFuture(beforeConnect: Supplier<CompletableFuture<Boolean>>): GravixSwitchBeforeConnect =
            object : GravixSwitchBeforeConnect {
                override suspend fun beforeConnect(): Boolean = beforeConnect.get().awaitGravix() == true
            }
    }
}

/**
 * GRAVIX (0.1.2): how one join (or, as [GravixJoin.Builder.setDefaults], every join) builds, connects and leaves its
 * Room. Everything is optional; what is not set falls back to the [GravixJoin]'s defaults, then the SDK's
 * ([com.gravixcloud.rtc.GravixRtc.create], `room.connect`, [GravixJoinLeave.DEFAULT]).
 */
class GravixJoinHooks private constructor(builder: Builder) {
    val createRoom: GravixJoinRoomFactory? = builder.createRoom
    val connect: GravixJoinConnect? = builder.connect
    val leave: GravixJoinLeave? = builder.leave

    /** For the default connect. */
    val connectOptions: ConnectOptions? = builder.connectOptions

    /** Whether going to the background before the screen attached abandons the join (null: the [GravixJoin]'s). */
    internal val abandonInBackground: Boolean? = builder.abandonInBackground

    /** For [GravixJoin.attach]: leave when the app goes to the background before the screen was shown (default true). */
    val leaveIfNeverShown: Boolean = builder.leaveIfNeverShown

    class Builder {
        internal var createRoom: GravixJoinRoomFactory? = null
        internal var connect: GravixJoinConnect? = null
        internal var leave: GravixJoinLeave? = null
        internal var connectOptions: ConnectOptions? = null
        internal var abandonInBackground: Boolean? = null
        internal var leaveIfNeverShown: Boolean = true

        fun setCreateRoom(factory: GravixJoinRoomFactory): Builder = apply { createRoom = factory }

        fun setConnect(connect: GravixJoinConnect): Builder = apply { this.connect = connect }

        fun setLeave(leave: GravixJoinLeave): Builder = apply { this.leave = leave }

        fun setConnectOptions(options: ConnectOptions): Builder = apply { connectOptions = options }

        /**
         * False for a join made in the background on purpose (a call answered from a notification) or a join whose
         * room screen opens only after more of your own flow: a Home press in that window keeps it.
         */
        fun setAbandonInBackground(abandon: Boolean): Builder = apply { abandonInBackground = abandon }

        fun setLeaveIfNeverShown(leave: Boolean): Builder = apply { leaveIfNeverShown = leave }

        fun build(): GravixJoinHooks = GravixJoinHooks(this)
    }
}
