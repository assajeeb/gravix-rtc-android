/*
 * Copyright 2023-2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.test.mock

import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudModels.ParticipantInfo.Kind
import gravixcloud.GravixcloudRtc
import java.util.UUID

object TestData {

    const val EXAMPLE_URL = "ws://www.example.com"

    val LOCAL_AUDIO_TRACK = with(GravixcloudModels.TrackInfo.newBuilder()) {
        sid = "TR_local_audio_track_sid"
        type = GravixcloudModels.TrackType.AUDIO
        source = GravixcloudModels.TrackSource.MICROPHONE
        build()
    }
    val LOCAL_VIDEO_TRACK = with(GravixcloudModels.TrackInfo.newBuilder()) {
        sid = "TR_local_video_track_sid"
        type = GravixcloudModels.TrackType.VIDEO
        source = GravixcloudModels.TrackSource.CAMERA
        build()
    }

    val REMOTE_AUDIO_TRACK = with(GravixcloudModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_audio_track_sid"
        type = GravixcloudModels.TrackType.AUDIO
        source = GravixcloudModels.TrackSource.MICROPHONE
        build()
    }

    val REMOTE_VIDEO_TRACK = with(GravixcloudModels.TrackInfo.newBuilder()) {
        sid = "TR_remote_video_track_sid"
        type = GravixcloudModels.TrackType.VIDEO
        source = GravixcloudModels.TrackSource.CAMERA
        build()
    }

    val LOCAL_PARTICIPANT = with(GravixcloudModels.ParticipantInfo.newBuilder()) {
        sid = "local_participant_sid"
        identity = "local_participant_identity"
        state = GravixcloudModels.ParticipantInfo.State.ACTIVE
        metadata = "local_metadata"
        permission = with(GravixcloudModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData = true

            hidden = false
            recorder = false
            build()
        }
        joinedAt = 0
        joinedAtMs = 0
        putAttributes("attribute", "value")
        build()
    }

    val REMOTE_PARTICIPANT = with(GravixcloudModels.ParticipantInfo.newBuilder()) {
        sid = "remote_participant_sid"
        identity = "remote_participant_identity"
        state = GravixcloudModels.ParticipantInfo.State.ACTIVE
        metadata = "remote_metadata"
        isPublisher = true
        permission = with(GravixcloudModels.ParticipantPermission.newBuilder()) {
            canPublish = true
            canSubscribe = true
            canPublishData
            build()
        }
        addTracks(REMOTE_AUDIO_TRACK)
        addTracks(REMOTE_VIDEO_TRACK)
        putAttributes("attribute", "value")
        build()
    }

    val REMOTE_SPEAKER_INFO = with(GravixcloudModels.SpeakerInfo.newBuilder()) {
        sid = REMOTE_PARTICIPANT.sid
        level = 1.0f
        active = true
        build()
    }

    val ENABLED_CODECS = listOf(
        codecForMime("video/VP8"),
        codecForMime("video/VP9"),
        codecForMime("video/H264"),
        codecForMime("video/AV1"),
        codecForMime("video/H265"),
        codecForMime("audio/red"),
        codecForMime("audio/opus"),
        codecForMime("audio/PCMU"),
        codecForMime("audio/PCMA"),
    )
    // Signal Responses
    // /////////////////////////////////

    val JOIN = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        join = with(GravixcloudRtc.JoinResponse.newBuilder()) {

            addAllEnabledPublishCodecs(ENABLED_CODECS)
            // fastPublish = true

            room = with(GravixcloudModels.Room.newBuilder()) {
                name = "roomname"
                creationTime = 0
                creationTimeMs = 0
                departureTimeout = 20
                emptyTimeout = 300
                addAllEnabledCodecs(ENABLED_CODECS)
                build()
            }
            participant = LOCAL_PARTICIPANT
            subscriberPrimary = true
            addIceServers(
                with(GravixcloudRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.example.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            serverInfo = with(GravixcloudModels.ServerInfo.newBuilder()) {
                edition = GravixcloudModels.ServerInfo.Edition.Cloud
                protocol = 16
                region = "Earth"
                version = "1.9.3"
                build()
            }
            serverVersion = "1.9.3"
            build()
        }
        build()
    }

    val RECONNECT = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        reconnect = with(GravixcloudRtc.ReconnectResponse.newBuilder()) {
            addIceServers(
                with(GravixcloudRtc.ICEServer.newBuilder()) {
                    addUrls("stun:stun.reconnect.com:19302")
                    username = "username"
                    credential = "credential"
                    build()
                },
            )
            clientConfiguration = with(GravixcloudModels.ClientConfiguration.newBuilder()) {
                forceRelay = GravixcloudModels.ClientConfigSetting.ENABLED
                build()
            }
            lastMessageSeq = 1
            build()
        }
        build()
    }

    val OFFER = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        offer = with(GravixcloudRtc.SessionDescription.newBuilder()) {
            sdp = "remote_offer"
            type = "offer"
            id = 99
            build()
        }
        build()
    }

    val ROOM_UPDATE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        roomUpdate = with(GravixcloudRtc.RoomUpdate.newBuilder()) {
            room = with(GravixcloudModels.Room.newBuilder()) {
                sid = "room_sid"
                metadata = "metadata"
                activeRecording = true
                build()
            }
            build()
        }
        build()
    }

    val LOCAL_TRACK_PUBLISHED = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        trackPublished = with(GravixcloudRtc.TrackPublishedResponse.newBuilder()) {
            cid = "local_cid"
            track = LOCAL_AUDIO_TRACK
            build()
        }
        build()
    }

    val LOCAL_TRACK_UNPUBLISHED = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        trackUnpublished = with(GravixcloudRtc.TrackUnpublishedResponse.newBuilder()) {
            trackSid = LOCAL_AUDIO_TRACK.sid
            build()
        }
        build()
    }

    val PERMISSION_CHANGE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        update = with(GravixcloudRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(
                with(LOCAL_PARTICIPANT.toBuilder()) {
                    permission = with(GravixcloudModels.ParticipantPermission.newBuilder()) {
                        canPublish = true
                        canSubscribe = false
                        canPublishData = false
                        addCanPublishSources(GravixcloudModels.TrackSource.CAMERA)
                        canUpdateMetadata = false
                        canSubscribeMetrics = false
                        hidden = false
                        recorder = false
                        build()
                    }
                    build()
                },
            )
            build()
        }
        build()
    }

    val PARTICIPANT_JOIN = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        update = with(GravixcloudRtc.ParticipantUpdate.newBuilder()) {
            addParticipants(REMOTE_PARTICIPANT)
            build()
        }
        build()
    }

    val PARTICIPANT_DISCONNECT = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        update = with(GravixcloudRtc.ParticipantUpdate.newBuilder()) {
            val disconnectedParticipant = REMOTE_PARTICIPANT.toBuilder()
                .setState(GravixcloudModels.ParticipantInfo.State.DISCONNECTED)
                .build()

            addParticipants(disconnectedParticipant)
            build()
        }
        build()
    }

    val AGENT_JOIN = with(TestData.PARTICIPANT_JOIN.toBuilder()) {
        update = with(update.toBuilder()) {
            clearParticipants()
            val agent = with(TestData.REMOTE_PARTICIPANT.toBuilder()) {
                kind = Kind.AGENT
                clearAttributes()
                putAttributes("lk.agent.state", "listening")
                build()
            }
            addParticipants(agent)
            build()
        }
        build()
    }

    val ACTIVE_SPEAKER_UPDATE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        speakersChanged = with(GravixcloudRtc.SpeakersChanged.newBuilder()) {
            addSpeakers(REMOTE_SPEAKER_INFO)
            build()
        }
        build()
    }

    val LOCAL_PARTICIPANT_METADATA_CHANGED = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        update = with(GravixcloudRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = LOCAL_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .putAttributes("attribute", "changed_value")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val REMOTE_PARTICIPANT_METADATA_CHANGED = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        update = with(GravixcloudRtc.ParticipantUpdate.newBuilder()) {
            val participantMetadataChanged = REMOTE_PARTICIPANT.toBuilder()
                .setMetadata("changed_metadata")
                .setName("changed_name")
                .build()

            addParticipants(participantMetadataChanged)
            build()
        }
        build()
    }

    val CONNECTION_QUALITY = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        connectionQuality = with(GravixcloudRtc.ConnectionQualityUpdate.newBuilder()) {
            addUpdates(
                with(GravixcloudRtc.ConnectionQualityInfo.newBuilder()) {
                    participantSid = JOIN.join.participant.sid
                    quality = GravixcloudModels.ConnectionQuality.EXCELLENT
                    build()
                },
            )
            build()
        }
        build()
    }

    val STREAM_STATE_UPDATE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        streamStateUpdate = with(GravixcloudRtc.StreamStateUpdate.newBuilder()) {
            addStreamStates(
                with(GravixcloudRtc.StreamStateInfo.newBuilder()) {
                    participantSid = REMOTE_PARTICIPANT.sid
                    trackSid = REMOTE_AUDIO_TRACK.sid
                    state = GravixcloudRtc.StreamState.ACTIVE
                    build()
                },
            )
            build()
        }
        build()
    }

    val SUBSCRIPTION_PERMISSION_UPDATE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        subscriptionPermissionUpdate = with(GravixcloudRtc.SubscriptionPermissionUpdate.newBuilder()) {
            participantSid = REMOTE_PARTICIPANT.sid
            trackSid = REMOTE_AUDIO_TRACK.sid
            allowed = false
            build()
        }
        build()
    }

    val REFRESH_TOKEN = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        refreshToken = "refresh_token"
        build()
    }

    val PONG = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        pong = 1L
        build()
    }

    val LEAVE = with(GravixcloudRtc.SignalResponse.newBuilder()) {
        leave = with(GravixcloudRtc.LeaveRequest.newBuilder()) {
            reason = GravixcloudModels.DisconnectReason.SERVER_SHUTDOWN
            build()
        }
        build()
    }

    // Data packets

    val DATA_PACKET_TRANSCRIPTION = with(GravixcloudModels.DataPacket.newBuilder()) {
        transcription = with(GravixcloudModels.Transcription.newBuilder()) {
            transcribedParticipantIdentity = JOIN.join.participant.identity // Local participant's identity
            addSegments(
                with(GravixcloudModels.TranscriptionSegment.newBuilder()) {
                    id = "id"
                    language = "enUS"
                    text = "This is a transcription."
                    startTime = 1
                    endTime = 10
                    final = true
                    trackId = LOCAL_AUDIO_TRACK.sid
                    build()
                },
            )
            build()
        }
        build()
    }
    val DATA_PACKET_RPC_REQUEST = with(GravixcloudModels.DataPacket.newBuilder()) {
        participantIdentity = REMOTE_PARTICIPANT.identity
        rpcRequest = with(GravixcloudModels.RpcRequest.newBuilder()) {
            id = UUID.randomUUID().toString()
            method = "hello"
            payload = "hello world"
            responseTimeoutMs = 10000
            version = 1
            build()
        }
        build()
    }
}

private fun codecForMime(mime: String, fmtpLine: String? = null) = with(GravixcloudModels.Codec.newBuilder()) {
    setMime(mime)
    build()
}
