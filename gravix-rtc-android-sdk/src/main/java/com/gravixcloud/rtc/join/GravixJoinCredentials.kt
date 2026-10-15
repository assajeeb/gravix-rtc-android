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

/**
 * GRAVIX (0.1.2): what a join dials: the server [url] (e.g. `wss://<region>.gravixcloud.com`; [GravixJoin] dials the
 * measured nearest region instead when this url is one of the measured regions) and the join [token] your server
 * minted.
 */
class GravixJoinCredentials internal constructor(
    val url: String,
    val token: String,
    /** True when [GravixJoinTokenProvider] answered from its cache (no fetch). */
    val fromCache: Boolean,
) {
    constructor(url: String, token: String) : this(url, token, false)

    /** Never the token. */
    override fun toString(): String =
        "GravixJoinCredentials(${GravixJoinLog.host(url)}${if (fromCache) ", cached" else ""})"
}
