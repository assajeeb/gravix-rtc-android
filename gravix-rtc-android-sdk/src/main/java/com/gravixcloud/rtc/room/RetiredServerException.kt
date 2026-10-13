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

package com.gravixcloud.rtc.room

/**
 * GRAVIX: the url names a retired Gravix server: the stock media server deployment (and its token endpoint) that ran
 * before the Gravix Cloud fleet. It is not the fleet (no region list, no failover, not billed), so the SDK refuses it
 * instead of connecting: connect with the url your token response returns.
 *
 * Thrown by [Room.connect] and [Room.prepareConnection] before anything else happens (no state change, no request),
 * by the token sources when one is created for such an endpoint, and it is the failure of
 * [com.gravixcloud.rtc.service.GravixRoomService.connect]'s future. An [IllegalArgumentException]: it is a
 * configuration error, and retrying cannot fix it.
 *
 * @property host the retired host the url named (lower case).
 */
class RetiredServerException(val host: String) :
    IllegalArgumentException("retired Gravix server ($host): use the url from your token response")
