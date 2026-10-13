package com.gravixcloud.rtc.videoencodedecode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.participant.Participant

/**
 * Widget for showing the other participant in a connection.
 */
@Composable
fun ConnectionItem(viewModel: CallViewModel) {
    val room by viewModel.room.collectAsState()
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    if (room != null) {
        RoomItem(room = room!!, participants)
    }
}

@Composable
fun RoomItem(room: Room, participants: List<Participant>) {
    val remoteParticipant = participants.filterNot { it == room.localParticipant }.firstOrNull()
    if (remoteParticipant != null) {
        ParticipantItem(room = room, participant = remoteParticipant, isSpeaking = false)
    }
}
