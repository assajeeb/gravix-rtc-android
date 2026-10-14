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

package org.webrtc

/** GRAVIX: libwebrtc's selected-candidate-pair event (its constructor is package-private) for the observer tests. */
object MockCandidatePairChangeEvent {
    const val UDP_HOST = "candidate:1 1 udp 2122260223 192.168.1.20 54321 typ host generation 0"
    const val TCP_HOST = "candidate:2 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active generation 0"
    const val RELAY = "candidate:3 1 udp 41885439 203.0.113.7 61000 typ relay raddr 0.0.0.0 rport 0 generation 0"

    fun create(localSdp: String): CandidatePairChangeEvent =
        CandidatePairChangeEvent(IceCandidate("0", 0, localSdp), IceCandidate("0", 0, localSdp), 0, "test", 0)
}
