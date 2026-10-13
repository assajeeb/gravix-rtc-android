/*
 * Copyright 2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.room.datastream

import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudModels.DataStream.ByteHeader
import gravixcloud.GravixcloudModels.DataStream.Header
import gravixcloud.GravixcloudModels.DataStream.TextHeader

sealed class StreamInfo(
    open val id: String,
    open val topic: String,
    open val timestampMs: Long,
    open val totalSize: Long?,
    open val attributes: Map<String, String>,
    open val encryptionType: GravixcloudModels.Encryption.Type,
)

data class TextStreamInfo(
    override val id: String,
    override val topic: String,
    override val timestampMs: Long,
    override val totalSize: Long?,
    override val attributes: Map<String, String>,
    val operationType: OperationType,
    val version: Int,
    val replyToStreamId: String?,
    val attachedStreamIds: List<String>,
    val generated: Boolean,
    override val encryptionType: GravixcloudModels.Encryption.Type,
) : StreamInfo(id, topic, timestampMs, totalSize, attributes, encryptionType) {
    constructor(header: Header, textHeader: TextHeader, encryptionType: GravixcloudModels.Encryption.Type) : this(
        id = header.streamId,
        topic = header.topic,
        timestampMs = header.timestamp,
        totalSize = if (header.hasTotalLength()) {
            header.totalLength
        } else {
            null
        },
        attributes = header.attributesMap.toMap(),
        operationType = OperationType.fromProto(textHeader.operationType),
        version = textHeader.version,
        replyToStreamId = if (!textHeader.replyToStreamId.isNullOrEmpty()) {
            textHeader.replyToStreamId
        } else {
            null
        },
        attachedStreamIds = textHeader.attachedStreamIdsList ?: emptyList(),
        generated = textHeader.generated,
        encryptionType = encryptionType,
    )

    enum class OperationType {
        CREATE,
        UPDATE,
        DELETE,
        REACTION;

        /**
         * @throws IllegalArgumentException [operationType] is unrecognized
         */
        fun toProto(): GravixcloudModels.DataStream.OperationType {
            return when (this) {
                CREATE -> GravixcloudModels.DataStream.OperationType.CREATE
                UPDATE -> GravixcloudModels.DataStream.OperationType.UPDATE
                DELETE -> GravixcloudModels.DataStream.OperationType.DELETE
                REACTION -> GravixcloudModels.DataStream.OperationType.REACTION
            }
        }

        companion object {
            /**
             * @throws IllegalArgumentException [operationType] is unrecognized
             */
            fun fromProto(operationType: GravixcloudModels.DataStream.OperationType): OperationType {
                return when (operationType) {
                    GravixcloudModels.DataStream.OperationType.CREATE -> CREATE
                    GravixcloudModels.DataStream.OperationType.UPDATE -> UPDATE
                    GravixcloudModels.DataStream.OperationType.DELETE -> DELETE
                    GravixcloudModels.DataStream.OperationType.REACTION -> REACTION
                    GravixcloudModels.DataStream.OperationType.UNRECOGNIZED -> throw IllegalArgumentException("Unrecognized operation type!")
                }
            }
        }
    }
}

data class ByteStreamInfo(
    override val id: String,
    override val topic: String,
    override val timestampMs: Long,
    override val totalSize: Long?,
    override val attributes: Map<String, String>,
    val mimeType: String,
    val name: String?,
    override val encryptionType: GravixcloudModels.Encryption.Type,
) : StreamInfo(id, topic, timestampMs, totalSize, attributes, encryptionType) {
    constructor(header: Header, byteHeader: ByteHeader, encryptionType: GravixcloudModels.Encryption.Type) : this(
        id = header.streamId,
        topic = header.topic,
        timestampMs = header.timestamp,
        totalSize = if (header.hasTotalLength()) {
            header.totalLength
        } else {
            null
        },
        attributes = header.attributesMap.toMap(),
        mimeType = header.mimeType,
        name = byteHeader.name,
        encryptionType = encryptionType,
    )
}
