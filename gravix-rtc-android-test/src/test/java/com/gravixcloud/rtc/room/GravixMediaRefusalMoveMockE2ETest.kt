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

import android.Manifest
import android.app.Application
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.regions.GravixRegionLog
import com.gravixcloud.rtc.regions.GravixRegionMeasurement
import com.gravixcloud.rtc.regions.GravixRegionMeasurementResult
import com.gravixcloud.rtc.regions.GravixRegionMeasurements
import com.gravixcloud.rtc.regions.GravixRegionMedia
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.regions.GravixRoomMedia
import com.gravixcloud.rtc.test.MockE2ETest
import com.gravixcloud.rtc.test.mock.MockWebSocket
import com.gravixcloud.rtc.test.mock.TestData
import com.gravixcloud.rtc.test.mock.camera.MockCameraProvider
import com.gravixcloud.rtc.util.GravixLogSink
import gravixcloud.GravixcloudModels
import gravixcloud.GravixcloudRtc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/**
 * GRAVIX (2026-10-21, per-region media): the SFU refuses a video publish on a video-off node with a RequestResponse
 * NOT_ALLOWED whose message is exactly `media_not_allowed`; the session moves ONCE to the next region taking video
 * (port of the Flutter SDK's test/room/media_refusal_move_test.dart). The session is on blr1 (TestData.EXAMPLE_URL,
 * audio-only here); its region list is [doh1, sgp1, blr1]; the measurement says sgp1 61 ms, blr1 120 ms, doh1 260 ms.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GravixMediaRefusalMoveMockE2ETest : MockE2ETest() {

    private val blr = TestData.EXAMPLE_URL
    private val sgp = "wss://sgp1.gravixcloud.test"
    private val doh = "wss://doh1.gravixcloud.test"
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUpRegions() {
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
        val now = GravixRegionMeasurements.clock.wallMs()
        fun m(region: String, url: String, rtt: Long) = GravixRegionMeasurement(GravixRegionUrl(region, url), rtt, listOf(rtt + 200, rtt))
        GravixRegionMeasurements.setCurrent(
            GravixRegionMeasurementResult(now, listOf(m("sgp1", sgp, 61), m("blr1", blr, 120), m("doh1", doh, 260)), best = m("sgp1", sgp, 61)),
        )
        directory(blrMedia = listOf("audio"))
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
    }

    @After
    fun tearDownRegions() {
        GravixRegionLog.sink = null
        setRegionUrlProviderTesting(false)
        GravixRegionMeasurements.clear()
    }

    private fun directory(blrMedia: List<String>?, others: List<String>? = null) = GravixRegionMedia.noteDirectory(
        listOf(GravixRegionUrl("doh1", doh, media = others), GravixRegionUrl("sgp1", sgp, media = others), GravixRegionUrl("blr1", blr, media = blrMedia)),
    )

    private fun seedRegionList() {
        setRegionUrlProviderTesting(true)
        component.rtcEngine().regionUrlProvider =
            RegionUrlProvider(java.net.URI(blr), "token", okhttp3.OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }).apply {
                setServerReportedRegions(RegionSettings(listOf(RegionInfo("doh1", doh, 0), RegionInfo("sgp1", sgp, 1), RegionInfo("blr1", blr, 2))))
            }
    }

    private fun join(serverRegion: String = "") = TestData.JOIN.toBuilder().apply {
        join = join.toBuilder().apply { serverInfo = serverInfo.toBuilder().setRegion(serverRegion).build() }.build()
    }.build()

    private fun refuse(message: String = "media_not_allowed") = simulateMessageFromServer(
        GravixcloudRtc.SignalResponse.newBuilder().setRequestResponse(
            GravixcloudRtc.RequestResponse.newBuilder()
                .setRequestId(7)
                .setReason(GravixcloudRtc.RequestResponse.Reason.NOT_ALLOWED)
                .setMessage(message),
        ).build(),
    )

    private fun TestScope.settle() {
        repeat(20) {
            Thread.sleep(2)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
    }

    private fun TestScope.nextDial(previous: MockWebSocket): MockWebSocket {
        repeat(400) {
            if (wsFactory.ws !== previous) return wsFactory.ws
            Thread.sleep(2)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
        }
        fail("the engine did not dial again; t=${testScheduler.currentTime}")
        throw IllegalStateException()
    }

    private fun TestScope.succeedJoin() {
        wsFactory.listener.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        simulateMessageFromServer(join())
        testScheduler.runCurrent()
        connectPeerConnection()
        testScheduler.advanceUntilIdle()
    }

    private fun mediaLines() = synchronized(lines) { lines.filter { it.startsWith("[GravixRegion] media") } }

    @Test
    fun theExactSfuRefusalMovesTheSessionOnceToTheNextRegionTakingVideo() = runTest {
        connect(join())
        assertEquals("blr1", room.connectedRegion)
        assertEquals("nothing declared", GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        seedRegionList()
        val before = wsFactory.ws

        refuse()
        val moved = nextDial(before)
        assertEquals("the next region taking video", "sgp1.gravixcloud.test", wsFactory.request.url.host)
        assertFalse("a full rejoin, not a resume", wsFactory.request.url.queryParameter(SignalClient.CONNECT_QUERY_RECONNECT) == "1")
        succeedJoin()
        settle()

        assertEquals(sgp, room.connectedUrl)
        assertEquals("sgp1", room.connectedRegion)
        val media = mediaLines()
        assertTrue(media.toString(), "[GravixRegion] mediaRefused region=blr1 media=video avoid=10min" in media)
        assertTrue(media.toString(), "[GravixRegion] mediaFailover media=video from=blr1 -> sgp1 reason=moved cause=media_not_allowed" in media)
        assertTrue(media.toString(), "[GravixRegion] mediaRepublish camera skip reason=not-wanted" in media)
        assertFalse(GravixRegionMedia.regionAllows(GravixRegionUrl("blr1", blr), GravixRoomMedia.VIDEO))

        // a second refusal in the same session is the app's
        refuse()
        settle()
        assertSame("no other dial", moved, wsFactory.ws)
        assertTrue(
            mediaLines().toString(),
            "[GravixRegion] mediaFailover media=video from=sgp1 stay reason=already-moved-once cause=media_not_allowed" in mediaLines(),
        )
    }

    @Test
    fun afterTheMoveTheWantedCameraIsPublishedOnTheNewRegion() = runTest {
        connect(join())
        seedRegionList()
        MockCameraProvider.register()
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
        // the app asked for the camera; blr1 refused its publish (no publication)
        room.localParticipant.gravixCameraWanted = true
        val before = wsFactory.ws
        refuse()
        nextDial(before)
        succeedJoin()
        settle()
        val media = mediaLines()
        assertTrue(media.toString(), "[GravixRegion] mediaRepublish camera start region=sgp1" in media)
        assertTrue(media.toString(), "[GravixRegion] mediaRepublish camera ok" in media)
        assertTrue(room.localParticipant.getTrackPublication(com.gravixcloud.rtc.room.track.Track.Source.CAMERA) != null)
    }

    /** blr1 takes the camera's AddTrack and answers nothing yet: what the SFU does before its refusal arrives. */
    private fun holdVideoAddTracks(): MutableList<Any> {
        val held = Collections.synchronizedList(mutableListOf<Any>())
        val first = wsFactory.ws
        wsFactory.registerSignalRequestHandler { r ->
            val hold = r.hasAddTrack() && r.addTrack.type == GravixcloudModels.TrackType.VIDEO && wsFactory.ws === first
            if (hold) held.add(r.addTrack.cid)
            hold
        }
        return held
    }

    private fun grantCamera() {
        MockCameraProvider.register()
        shadowOf(context as Application).grantPermissions(Manifest.permission.CAMERA)
    }

    /** Owner 2026-11-04: on a region known not to carry video the session moves first; nothing is tried there. */
    @Test
    fun aCameraOnARegionWithoutVideoMovesFirstAndPublishesThere() = runTest {
        connect(join())
        seedRegionList()
        grantCamera()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        val enable = launch { enabled = room.localParticipant.setCameraEnabled(true) }
        nextDial(before)
        assertEquals("no publish was tried on blr1", 0, held.size)
        succeedJoin()
        settle()
        assertTrue("the enable still waits; ${mediaLines()}", enable.isCompleted)
        assertEquals(true, enabled)
        assertEquals(sgp, room.connectedUrl)
        assertTrue(room.localParticipant.getTrackPublication(com.gravixcloud.rtc.room.track.Track.Source.CAMERA) != null)
        assertTrue(mediaLines().toString(), "[GravixRegion] mediaFailover media=video from=blr1 -> sgp1 reason=moved cause=camera-on" in mediaLines())
        assertFalse(mediaLines().toString(), mediaLines().any { it.startsWith("[GravixRegion] mediaRepublish") })
    }

    @Test
    fun aMediaMoveDialsWithoutTheFirstRetryDelay() = runTest {
        connect(join())
        seedRegionList()
        grantCamera()
        val before = wsFactory.ws
        val enable = launch { room.localParticipant.setCameraEnabled(true) }
        val startedAt = testScheduler.currentTime
        repeat(400) {
            if (wsFactory.ws === before) {
                Thread.sleep(2)
                testScheduler.runCurrent()
            }
        }
        assertTrue("no dial without advancing the clock", wsFactory.ws !== before)
        assertEquals("no back-off before the first dial", startedAt, testScheduler.currentTime)
        succeedJoin()
        settle()
        enable.join()
    }

    @Test
    fun aCameraWithNoRegionCarryingVideoFailsAtOnceWithoutTrying() = runTest {
        directory(blrMedia = listOf("audio"), others = listOf("audio"))
        connect(join())
        seedRegionList()
        grantCamera()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        val startedAt = testScheduler.currentTime
        val enable = launch { enabled = room.localParticipant.setCameraEnabled(true) }
        settle()
        assertTrue("still waiting ${testScheduler.currentTime - startedAt}ms", enable.isCompleted)
        assertEquals(false, enabled)
        assertEquals(0, held.size)
        assertSame(before, wsFactory.ws)
        assertTrue(mediaLines().toString(), mediaLines().any { it.endsWith("stay reason=no-region-allows cause=camera-on") })
    }

    @Test
    fun optionOffOnARegionWithoutVideoFailsAtOnceWithoutTrying() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(mediaRefusalFailover = false)
        connect(join())
        seedRegionList()
        grantCamera()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        val enable = launch { enabled = room.localParticipant.setCameraEnabled(true) }
        settle()
        assertTrue(enable.isCompleted)
        assertEquals(false, enabled)
        assertEquals(0, held.size)
        assertSame(before, wsFactory.ws)
    }

    @Test
    fun aRegionOfUnknownMediaPublishesAsToday() = runTest {
        directory(blrMedia = null)
        connect(join())
        seedRegionList()
        grantCamera()
        val before = wsFactory.ws
        assertEquals(true, room.localParticipant.setCameraEnabled(true))
        assertSame("no move", before, wsFactory.ws)
    }

    @Test
    fun aScreenShareOnARegionWithoutVideoMovesFirstToo() = runTest {
        connect(join())
        seedRegionList()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        val track = com.gravixcloud.rtc.room.track.LocalVideoTrack(
            capturer = com.gravixcloud.rtc.test.mock.MockVideoCapturer(),
            source = org.mockito.Mockito.mock(org.webrtc.VideoSource::class.java),
            name = "",
            options = com.gravixcloud.rtc.room.track.LocalVideoTrackOptions(
                isScreencast = true,
                deviceId = null,
                position = null,
                captureParams = com.gravixcloud.rtc.room.track.VideoCaptureParameter(width = 1280, height = 720, maxFps = 15),
            ),
            rtcTrack = com.gravixcloud.rtc.test.mock.MockVideoStreamTrack(),
            peerConnectionFactory = component.peerConnectionFactory(),
            context = context,
            eglBase = com.gravixcloud.rtc.test.mock.MockEglBase(),
            defaultsManager = DefaultsManager(),
            trackFactory = org.mockito.Mockito.mock(com.gravixcloud.rtc.room.track.LocalVideoTrack.Factory::class.java),
            rtcThreadToken = com.gravixcloud.rtc.test.mock.MockRTCThreadToken(),
        )
        var published: Boolean? = null
        val publish = launch { published = room.localParticipant.publishVideoTrack(track) }
        nextDial(before)
        assertEquals(0, held.size)
        succeedJoin()
        settle()
        assertTrue(publish.isCompleted)
        assertEquals(true, published)
        assertTrue(mediaLines().toString(), "[GravixRegion] mediaFailover media=video from=blr1 -> sgp1 reason=moved cause=screen-on" in mediaLines())
    }

    /** The safety net (a stale region list). Field 2026-11-04: first camera RTP 20.8 s after the enable, which returned false. */
    @Test
    fun aRefusalOnAStaleListEndsThePublishAtOnceAndTheEnableReturnsTheRepublish() = runTest {
        directory(blrMedia = null)
        connect(join())
        seedRegionList()
        grantCamera()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        val enable = launch { enabled = room.localParticipant.setCameraEnabled(true) }
        settle()
        assertEquals("the AddTrack went out and is unanswered", 1, held.size)
        val refusedAt = testScheduler.currentTime
        refuse()
        nextDial(before)
        succeedJoin()
        settle()
        val took = testScheduler.currentTime - refusedAt
        assertTrue("the enable still waits ${took}ms after the refusal; ${mediaLines()}", enable.isCompleted)
        assertTrue("took $took ms: the addTrack deadline is 20 s", took < 20_000)
        assertEquals("the republish is the enable's answer", true, enabled)
        assertTrue(room.localParticipant.getTrackPublication(com.gravixcloud.rtc.room.track.Track.Source.CAMERA) != null)
        assertTrue(mediaLines().toString(), "[GravixRegion] mediaRepublish camera ok" in mediaLines())
    }

    @Test
    fun aRefusalWithOptionOffFailsAtOnce() = runTest {
        room.gravixRecovery = GravixRecoveryOptions(mediaRefusalFailover = false)
        directory(blrMedia = null)
        connect(join())
        seedRegionList()
        grantCamera()
        val held = holdVideoAddTracks()
        val before = wsFactory.ws
        var enabled: Boolean? = null
        val enable = launch { enabled = room.localParticipant.setCameraEnabled(true) }
        settle()
        assertEquals(1, held.size)
        refuse()
        settle()
        assertTrue("the option off must not sit out the 20 s", enable.isCompleted)
        assertEquals(false, enabled)
        assertSame(before, wsFactory.ws)
    }

    @Test
    fun aVideoRefusalLeavesAPendingMicrophonePublishAlone() = runTest {
        directory(blrMedia = listOf("audio"), others = listOf("audio")) // no move: only the abort is under test
        connect(join())
        seedRegionList()
        val engine = component.rtcEngine()
        val audioHeld = Collections.synchronizedList(mutableListOf<String>())
        wsFactory.registerSignalRequestHandler { r ->
            val hold = r.hasAddTrack() && r.addTrack.type == GravixcloudModels.TrackType.AUDIO
            if (hold) audioHeld.add(r.addTrack.cid)
            hold
        }
        val mic = async { runCatching { engine.addTrack("mic-cid", "mic", GravixcloudModels.TrackType.AUDIO, null) } }
        settle()
        assertEquals(listOf("mic-cid"), audioHeld.toList())
        refuse()
        settle()
        assertFalse("the mic publish was not the one refused", mic.isCompleted)
        simulateMessageFromServer(
            GravixcloudRtc.SignalResponse.newBuilder().setTrackPublished(
                GravixcloudRtc.TrackPublishedResponse.newBuilder().setCid("mic-cid").setTrack(TestData.LOCAL_AUDIO_TRACK),
            ).build(),
        )
        settle()
        assertTrue(mic.await().isSuccess)
    }

    @Test
    fun optionOffStaysAndTheRefusalSteersNothing() = runTest {
        // review 2026-10-21: off = upstream -- no move, no region note (later picks unchanged), no media change
        room.gravixRecovery = GravixRecoveryOptions(mediaRefusalFailover = false)
        directory(blrMedia = null) // blr1 takes video by the directory: only a refusal note could make it not
        connectWithMedia(GravixRoomMedia.AUDIO)
        seedRegionList()
        component.rtcEngine().regionUrlProvider?.gravixMedia = GravixRoomMedia.AUDIO // as Room.connect sets it
        val before = wsFactory.ws
        refuse()
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(mediaLines().toString(), mediaLines().any { it.endsWith("stay reason=option-off cause=media_not_allowed") })
        assertTrue("not noted", GravixRegionMedia.regionAllows(GravixRegionUrl("blr1", blr), GravixRoomMedia.VIDEO))
        assertTrue(mediaLines().toString(), mediaLines().none { it.startsWith("[GravixRegion] mediaRefused") })
        assertEquals(GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        assertEquals(GravixRoomMedia.AUDIO, component.rtcEngine().regionUrlProvider?.gravixMedia)
        assertEquals(false, GravixRecoveryOptions.UPSTREAM.mediaRefusalFailover)
        assertEquals(true, GravixRecoveryOptions().mediaRefusalFailover)
    }

    /** A connect that declares [media] (the default helper connects with the default, video). */
    private suspend fun TestScope.connectWithMedia(media: GravixRoomMedia) {
        val job = coroutineRule.scope.launch {
            room.connect(url = TestData.EXAMPLE_URL, token = "token", options = com.gravixcloud.rtc.ConnectOptions(media = media))
        }
        prepareSignal(join())
        job.join()
        connectPeerConnection()
    }

    @Test
    fun aRefusalMakesTheSessionVideoForEveryLaterPath() = runTest { // review 2026-10-21 (#7)
        connectWithMedia(GravixRoomMedia.AUDIO)
        assertEquals(GravixRoomMedia.AUDIO, room.gravixJoinMedia)
        seedRegionList()
        component.rtcEngine().regionUrlProvider?.gravixMedia = GravixRoomMedia.AUDIO
        val before = wsFactory.ws
        refuse()
        nextDial(before)
        succeedJoin()
        settle()
        assertEquals(GravixRoomMedia.VIDEO, room.gravixJoinMedia)
        assertEquals("later restarts / failovers order for video", GravixRoomMedia.VIDEO, component.rtcEngine().regionUrlProvider?.gravixMedia)
        assertTrue(mediaLines().toString(), "[GravixRegion] media media=video cause=refusal (was audio)" in mediaLines())
    }

    @Test
    fun promotionNeverDemotes() {
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.promote(GravixRoomMedia.AUDIO, GravixRoomMedia.VIDEO))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.promote(GravixRoomMedia.VIDEO, GravixRoomMedia.AUDIO))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.promote(null, GravixRoomMedia.VIDEO))
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.promote(GravixRoomMedia.AUDIO, GravixRoomMedia.AUDIO))
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.promote(null, GravixRoomMedia.AUDIO))
    }

    @Test
    fun noRegionListStays() = runTest {
        connect(join())
        val before = wsFactory.ws
        refuse()
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(mediaLines().toString(), mediaLines().any { it.endsWith("stay reason=no-region-list cause=media_not_allowed") })
    }

    @Test
    fun noOtherRegionTakingVideoStays() = runTest {
        directory(blrMedia = listOf("audio"), others = listOf("audio"))
        connect(join())
        seedRegionList()
        val before = wsFactory.ws
        refuse()
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(mediaLines().toString(), mediaLines().any { it.endsWith("stay reason=no-region-allows cause=media_not_allowed") })
    }

    @Test
    fun anOtherRefusalMessageMovesNothing() = runTest {
        connect(join())
        seedRegionList()
        val before = wsFactory.ws
        refuse("not allowed")
        settle()
        assertSame(before, wsFactory.ws)
        assertTrue(mediaLines().toString(), mediaLines().none { it.contains("mediaFailover") })
    }
}
