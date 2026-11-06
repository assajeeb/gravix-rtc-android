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

package com.gravixcloud.rtc.regions

import com.gravixcloud.rtc.ConnectOptions
import com.gravixcloud.rtc.join.GravixJoinRequest
import com.gravixcloud.rtc.join.GravixJoinTokenProvider
import com.gravixcloud.rtc.regions.RegionTestKit.Table
import com.gravixcloud.rtc.regions.RegionTestKit.onTestScheduler
import com.gravixcloud.rtc.regions.RegionTestKit.unknownNet
import com.gravixcloud.rtc.regions.RegionTestKit.useVirtualClock
import com.gravixcloud.rtc.util.GravixLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * GRAVIX (2026-10-21): per-region media (port of the Flutter SDK's test/connect/region_media_test.dart, where it
 * applies: no token region list parser, no standby and no decision cache on Android). doh1 is audio-only; a join that
 * uses video goes to the nearest region allowing video. The pick itself runs on the shared fixture
 * (GravixRegionMeasurementTest.mediaFixture); this file covers the parsing, the join's media, the join url, the
 * failover order and the refusals. Robolectric: the token hint reads a JWT (android.util.Base64).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GravixRegionMediaTest {

    private val lines = mutableListOf<String>()

    private fun r(slug: String, media: String? = null) =
        """{"region":"$slug","url":"wss://$slug.example.test"${if (media == null) "" else ""","media":$media"""}}"""

    private val gulf = """{"regions":[${r("doh1", """["audio"]""")},${r("blr1")},${r("sgp1")}],"shortlist":["doh1","blr1","sgp1"]}"""

    private fun jwt(claims: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." +
            enc.encodeToString(claims.toByteArray()) + ".c2ln"
    }

    private suspend fun TestScope.measure(body: String, rtt: Map<String, Long?>) {
        GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionsUrl = "https://gw.example.test/v1/regions"
                    fetchDirectory = GravixRegionDirectoryFetcher { GravixRegionDirectory.fromJson(body) }
                    sampler = Table(rtt.mapValues { (_, v) -> v?.let { listOf(it) } })
                    store = GravixMemoryRegionListStore()
                    budgetMs = 5_000
                    networkType = unknownNet()
                },
            ),
        )
    }

    @Before
    fun setUp() {
        GravixRegionMeasurements.clear()
        lines.clear()
        GravixRegionLog.sink = GravixLogSink { _, line -> lines.add(line) }
    }

    @After
    fun tearDown() {
        GravixRegionLog.sink = null
        GravixRegionMedia.autoDetect = true
        GravixRegionMeasurements.clear()
        RegionTestKit.resetClock()
    }

    // ------------------------------------------------------------------------------------------------ parsing

    @Test
    fun regionsMediaAbsentIsEveryMediumAndSurvivesTheStoredCopy() {
        val dir = GravixRegionDirectory.fromJson(
            """{"regions":[${r("doh1", """["audio"]""")},${r("blr1")},${r("x1", """["Audio","VIDEO",7]""")},${r("x2", "\"audio\"")}]}""",
        )
        assertEquals(listOf(listOf("audio"), null, listOf("audio", "video"), null), dir.regions.map { it.media })
        assertEquals(dir.regions, GravixRegionDirectory.fromJson(dir.toJsonString()).regions)
        assertTrue(GravixRegionMedia.allows(null, GravixRoomMedia.VIDEO))
        assertFalse(GravixRegionMedia.allows(listOf("audio"), GravixRoomMedia.VIDEO))
        assertFalse(GravixRegionMedia.allows(emptyList(), GravixRoomMedia.AUDIO))
        assertEquals(GravixRoomMedia.AUDIO, GravixRoomMedia.fromWireName("Audio"))
        assertNull(GravixRoomMedia.fromWireName("screen"))
    }

    @Test
    fun theMediaIsNotPartOfTheJoinRequestKey() {
        val a = GravixJoinRequest("r", "u", false, GravixRoomMedia.AUDIO)
        val b = GravixJoinRequest("r", "u")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a.toString().contains("media=audio"))
        assertFalse(b.toString().contains("media"))
        assertNull(ConnectOptions().media)
        assertEquals(GravixRoomMedia.AUDIO, ConnectOptions(media = GravixRoomMedia.AUDIO).media)
    }

    // ---------------------------------------------------------------------------------------- the join media

    @Test
    fun autoWhenNothingDeclaredAudioWhenDeclaredPromotedNeverDemoted() {
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia())
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO, enableVideo = true))
        val camera = jwt("""{"video":{"room":"r","roomJoin":true,"canPublishSources":["microphone","camera"]}}""")
        val micOnly = jwt("""{"video":{"room":"r","roomJoin":true,"canPublishSources":["microphone"]}}""")
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.hintFromToken(camera))
        assertNull("a mic-only publisher may still watch video", GravixRegionMedia.hintFromToken(micOnly))
        assertNull(GravixRegionMedia.hintFromToken("not a jwt"))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO, token = camera))
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO, token = micOnly))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(GravixRoomMedia.VIDEO, token = micOnly))
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=token-grant-camera (declared audio)" in lines)
        assertTrue(lines.toString(), "[GravixRegion] media media=audio cause=auto" in lines)
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=enableVideo (declared audio)" in lines)
    }

    // --------------------------------------------------------------------------------------------- join url

    @Test
    fun aVideoJoinLeavesTheAudioOnlyBestRegionAnAudioJoinTakesIt() = runTest {
        useVirtualClock()
        measure(gulf, mapOf("doh1" to 20L, "blr1" to 60L, "sgp1" to 90L))
        val token = "wss://doh1.example.test"
        assertEquals("wss://blr1.example.test", GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.VIDEO))
        assertEquals(token, GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.AUDIO))
        assertEquals("no media: as before", token, GravixRegionMeasurements.resolveConnectUrl(token))
        assertTrue(lines.toString(), "[GravixRegion] pick media=video skip=doh1(audio-only) -> blr1 rtt=60 reason=measured" in lines)
        assertTrue(lines.toString(), "[GravixRegion] directory media-limited doh1(audio)" in lines)
    }

    @Test
    fun nothingMeasuredAVideoJoinWithAnAudioOnlyTokenUrlGoesToTheFirstAllowedShortlistRegion() = runTest {
        useVirtualClock()
        measure(gulf, emptyMap())
        val token = "wss://doh1.example.test"
        assertEquals("wss://blr1.example.test", GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.VIDEO))
        assertTrue(
            lines.toString(),
            "[GravixRegion] pick media=video nothing-measured pinned=doh1(audio-only) -> blr1 reason=fallback cause=shortlist" in lines,
        )
        assertEquals(token, GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.AUDIO))
        assertEquals("[GravixRegion] pick media=audio -> token-url cause=nothing-measured", lines.last())
    }

    // Field 2026-10-27 (Flutter region_media_test.dart, same cases): token url doh1 (audio-only), no region list,
    // doh1 not in this caller's shortlist (never measured): the video join stayed on doh1 and the camera was
    // refused (media_not_allowed).
    private val unmeasuredDoh1 =
        """{"regions":[${r("doh1", """["audio"]""")},${r("dac1")},${r("blr1")},${r("sgp1")}],"shortlist":["dac1","blr1","sgp1"]}"""

    @Test
    fun anUnmeasuredAudioOnlyTokenUrlAVideoJoinTakesTheBestMeasuredRegionAllowingVideo() = runTest {
        useVirtualClock()
        measure(unmeasuredDoh1, mapOf("doh1" to 12L, "dac1" to 140L, "blr1" to 70L, "sgp1" to 95L))
        val token = "wss://doh1.example.test"
        assertEquals("wss://blr1.example.test", GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.VIDEO))
        assertTrue(
            lines.toString(),
            "[GravixRegion] candidates media=video pinned=doh1(audio-only) unmeasured -> directory+measured cause=media-not-allowed" in lines,
        )
        assertEquals(token, GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.AUDIO))
        assertEquals("no media: as before", token, GravixRegionMeasurements.resolveConnectUrl(token))
    }

    @Test
    fun anUnmeasuredAudioOnlyTokenUrlNothingMeasuredTheFirstShortlistRegionAllowingVideo() = runTest {
        useVirtualClock()
        measure(unmeasuredDoh1, emptyMap())
        assertEquals(
            "wss://dac1.example.test",
            GravixRegionMeasurements.resolveConnectUrl("wss://doh1.example.test", media = GravixRoomMedia.VIDEO),
        )
    }

    @Test
    fun noRegionAllowsTheMediaTheTokenUrlStaysAndTheLogSaysWhy() = runTest {
        useVirtualClock()
        measure("""{"regions":[${r("doh1", """["audio"]""")},${r("blr1", """["audio"]""")}],"shortlist":["blr1"]}""", mapOf("blr1" to 70L))
        val token = "wss://doh1.example.test"
        assertEquals(token, GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.VIDEO))
        assertTrue(
            lines.toString(),
            "[GravixRegion] candidates media=video pinned=doh1(audio-only) kept cause=no-region-allows-video" in lines,
        )
    }

    @Test
    fun aDeviceSignedTokenOnDoh1WithNoRegionListStillMovesAVideoJoinToARegionAllowingVideo() = runTest {
        useVirtualClock()
        measure(unmeasuredDoh1, mapOf("doh1" to 12L, "dac1" to 140L, "blr1" to 70L, "sgp1" to 95L))
        val provider = GravixJoinTokenProvider.local("APPlocal", "x".repeat(32), "wss://doh1.example.test")
        val creds = provider.getCredentials(GravixJoinRequest("r1", "u1", true))
        assertEquals("wss://doh1.example.test", creds.url)
        // GravixJoin picks the dial url from (credentials url, join media). A grant with no source list says nothing:
        // nothing declared is audio, and only a declared video skips the audio-only region
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(null, false, creds.token))
        val media = GravixRegionMedia.joinMedia(GravixRoomMedia.VIDEO, false, creds.token)
        assertEquals("wss://blr1.example.test", GravixRegionMeasurements.resolveConnectUrl(creds.url, media = media))
        assertEquals(
            "an audio join stays",
            creds.url,
            GravixRegionMeasurements.resolveConnectUrl(creds.url, media = GravixRoomMedia.AUDIO),
        )
    }

    @Test
    fun nothingDeclaredOnAnAudioOnlyTokenUrlTakesTheFastestRegion() = runTest {
        useVirtualClock()
        measure(unmeasuredDoh1, mapOf("doh1" to 12L, "dac1" to 140L, "blr1" to 70L, "sgp1" to 95L))
        val media = GravixRegionMedia.joinMedia()
        assertEquals(
            "the fastest measured region, audio-only included",
            "wss://doh1.example.test",
            GravixRegionMeasurements.resolveConnectUrl("wss://doh1.example.test", media = media)
        )
    }

    @Test
    fun theOffSwitchRestoresTheOldDefault() {
        GravixRegionMedia.autoDetect = false
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia())
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=default" in lines)
        assertEquals("a declared audio still counts", GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO))
    }

    @Test
    fun aCameraOrACameraGrantIsVideoWithNothingDeclared() {
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(enableVideo = true))
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=enableVideo (auto)" in lines)
        val screen = jwt("""{"video":{"room":"r","canPublishSources":["screen_share"]}}""")
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(token = screen))
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=token-grant-camera (auto)" in lines)
    }

    @Test
    fun aRoomSeenWithVideoGoesStraightToARegionThatCarriesIt() {
        val token = jwt("""{"video":{"room":"APP1_r1","roomJoin":true,"canPublishSources":["microphone"]}}""")
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(token = token))
        GravixRegionMedia.noteRoomHasVideo("APP1_r1")
        assertTrue(GravixRegionMedia.roomHadVideo("APP1_r1"))
        assertFalse(GravixRegionMedia.roomHadVideo("APP1_r2"))
        assertFalse(GravixRegionMedia.roomHadVideo(null))
        assertEquals("by the token's room", GravixRoomMedia.VIDEO, GravixRegionMedia.joinMedia(token = token))
        assertTrue(lines.toString(), "[GravixRegion] media media=video cause=room-had-video" in lines)
        val other = jwt("""{"video":{"room":"APP1_r2","roomJoin":true}}""")
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(token = other))
        assertEquals("a declaration ignores the memory", GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(GravixRoomMedia.AUDIO, token = token))
        GravixRegionMedia.clear()
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.joinMedia(token = token))
    }

    @Test
    fun theRoomMemoryStaysBoundedAndSurvivesThreads() {
        val threads = (0 until 8).map { t ->
            Thread {
                for (i in 0 until 500) {
                    GravixRegionMedia.noteRoomHasVideo("t$t-$i")
                    GravixRegionMedia.roomHadVideo("t$t-$i")
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(500, GravixRegionMedia.roomsWithVideoCount())
        for (i in 0 until 1000) GravixRegionMedia.noteRoomHasVideo("room$i")
        assertEquals(500, GravixRegionMedia.roomsWithVideoCount())
        assertTrue(GravixRegionMedia.roomHadVideo("room999"))
        assertFalse("the oldest goes first", GravixRegionMedia.roomHadVideo("room0"))
    }

    @Test
    fun aUrlOutsideTheFleetIsStillNeverRedirected() = runTest {
        useVirtualClock()
        measure(unmeasuredDoh1, mapOf("blr1" to 70L))
        val own = "wss://own.server.test"
        assertEquals(own, GravixRegionMeasurements.resolveConnectUrl(own, media = GravixRoomMedia.VIDEO))
        assertTrue(GravixRegionMeasurements.measuredCandidates(own, media = GravixRoomMedia.VIDEO).isEmpty())
    }

    @Test
    fun aUrlIsCheckedByItsRegion() = runTest {
        useVirtualClock()
        measure(gulf, mapOf("doh1" to 20L))
        assertFalse(GravixRegionMedia.urlAllows("wss://doh1.example.test/", GravixRoomMedia.VIDEO))
        assertTrue(GravixRegionMedia.urlAllows("wss://doh1.example.test", GravixRoomMedia.AUDIO))
        assertTrue(GravixRegionMedia.urlAllows("wss://own.server.test", GravixRoomMedia.VIDEO))
    }

    // ---------------------------------------------------------------------------------- hysteresis per medium

    @Test
    fun videoJoinsDoNotFlipBetweenTwoCloseVideoRegionsAcrossRuns() = runTest {
        useVirtualClock()
        val store = GravixMemoryRegionListStore()
        suspend fun run(rtt: Map<String, Long>) = GravixRegionMeasurements.measure(
            onTestScheduler(
                GravixRegionMeasureOptions().apply {
                    regionsUrl = "https://gw.example.test/v1/regions"
                    fetchDirectory = GravixRegionDirectoryFetcher { GravixRegionDirectory.fromJson(gulf) }
                    sampler = Table(rtt.mapValues { (_, v) -> listOf(v) })
                    this.store = store
                    budgetMs = 5_000
                    networkType = unknownNet()
                },
            ),
        )
        val first = run(mapOf("doh1" to 20L, "blr1" to 60L, "sgp1" to 70L))
        assertEquals("blr1", first.bestFor(GravixRoomMedia.VIDEO)?.region)
        val second = run(mapOf("doh1" to 20L, "blr1" to 70L, "sgp1" to 60L))
        assertEquals("blr1", second.bestFor(GravixRoomMedia.VIDEO)?.region)
        assertEquals(GravixRegionSelectReason.KEPT, second.mediaSelectReason[GravixRoomMedia.VIDEO])
        assertEquals(
            "wss://blr1.example.test",
            GravixRegionMeasurements.resolveConnectUrl("wss://doh1.example.test", media = GravixRoomMedia.VIDEO),
        )
        assertTrue(lines.toString(), "[GravixRegion] select media=video -> blr1 rtt=70 reason=kept anchor=blr1" in lines)
        assertEquals("doh1", second.bestFor(GravixRoomMedia.AUDIO)?.region)
        assertEquals("doh1", second.bestFor(null)?.region)
    }

    // ---------------------------------------------------------------------------------- failover and refusals

    @Test
    fun regionsNotTakingTheMediaGoLastNeverDropped() = runTest {
        useVirtualClock()
        measure(gulf, mapOf("doh1" to 20L, "blr1" to 60L, "sgp1" to 90L))
        val entries = listOf("doh1", "blr1", "sgp1").map { GravixRegionUrl(it, "wss://$it.example.test") }
        val order = GravixRegionFailoverOrder.order(entries, GravixRegionMeasurements.current(), { it.region }, { it.url }, GravixRoomMedia.VIDEO)
        assertEquals(listOf("blr1", "sgp1", "doh1"), order.map { it.region })
        assertEquals("[GravixRegion] failoverOrder media=video last=doh1 first=blr1,sgp1", lines.last())
        val audio = GravixRegionFailoverOrder.order(entries, GravixRegionMeasurements.current(), { it.region }, { it.url }, GravixRoomMedia.AUDIO)
        assertEquals(listOf("doh1", "blr1", "sgp1"), audio.map { it.region })
        val none = GravixRegionFailoverOrder.order(entries, GravixRegionMeasurements.current(), { it.region }, { it.url })
        assertEquals(listOf("doh1", "blr1", "sgp1"), none.map { it.region })
    }

    @Test
    fun aMediaNotAllowedRefusalAvoidsTheRegionForThatMedium() = runTest {
        var offsetMs = 0L
        useVirtualClock { offsetMs }
        measure(gulf, mapOf("doh1" to 20L, "blr1" to 60L, "sgp1" to 90L))
        assertTrue(GravixRegionMedia.isMediaNotAllowed("Signal request failed: NOT_ALLOWED - media_not_allowed: video"))
        assertTrue(GravixRegionMedia.isMediaNotAllowed(IllegalStateException("MEDIA_NOT_ALLOWED")))
        assertFalse(GravixRegionMedia.isMediaNotAllowed("not allowed"))
        assertFalse(GravixRegionMedia.isMediaNotAllowed(null))
        GravixRegionMedia.noteRefused("blr1", GravixRoomMedia.VIDEO)
        assertEquals("[GravixRegion] mediaRefused region=blr1 media=video avoid=10min", lines.last())
        val token = "wss://doh1.example.test"
        assertEquals("wss://sgp1.example.test", GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.VIDEO))
        assertTrue(lines.toString(), lines.last().contains("skip=blr1(refused),doh1(audio-only) -> sgp1"))
        assertEquals(token, GravixRegionMeasurements.resolveConnectUrl(token, media = GravixRoomMedia.AUDIO))
        val blr1 = GravixRegionUrl("blr1", "wss://blr1.example.test")
        assertFalse(GravixRegionMedia.regionAllows(blr1, GravixRoomMedia.VIDEO))
        assertTrue(GravixRegionMedia.regionAllows(blr1, GravixRoomMedia.AUDIO))
        offsetMs = GravixRegionMedia.REFUSAL_LIFE_MS + 1_000
        assertTrue(GravixRegionMedia.regionAllows(blr1, GravixRoomMedia.VIDEO))
    }

    @Test
    fun aRefusalTextNamesItsMedium() {
        assertEquals(GravixRoomMedia.AUDIO, GravixRegionMedia.refusedMediaOf("media_not_allowed: audio"))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.refusedMediaOf("media_not_allowed: video"))
        assertEquals(GravixRoomMedia.VIDEO, GravixRegionMedia.refusedMediaOf("media_not_allowed"))
    }

    /** GRAVIX (2026-11-05, R3): the memory lasts 10 minutes at most. */
    @Test
    fun aRememberedRoomEndsAfterTenMinutes() = kotlinx.coroutines.test.runTest {
        with(RegionTestKit) { useVirtualClock() }
        GravixRegionMedia.noteRoomHasVideo("r1")
        testScheduler.advanceTimeBy(GravixRegionMedia.ROOM_VIDEO_MEMORY_LIFE_MS - 1000)
        assertTrue(GravixRegionMedia.roomHadVideo("r1"))
        testScheduler.advanceTimeBy(2000)
        assertFalse(GravixRegionMedia.roomHadVideo("r1"))
        assertEquals("nothing kept for nothing", 0, GravixRegionMedia.roomsWithVideoCount())
        RegionTestKit.resetClock()
    }

    @Test
    fun aForgottenRoomIsForgotten() {
        GravixRegionMedia.noteRoomHasVideo("r1")
        GravixRegionMedia.forgetRoomVideo("r1")
        assertFalse(GravixRegionMedia.roomHadVideo("r1"))
    }

    /** GRAVIX (2026-11-05): a muted camera carries nothing; its unmute is an update that says so. */
    @Test
    fun aMutedCameraIsNotVideo() {
        fun peer(muted: Boolean) = gravixcloud.GravixcloudModels.ParticipantInfo.newBuilder()
            .setIdentity("host")
            .addTracks(gravixcloud.GravixcloudModels.TrackInfo.newBuilder().setType(gravixcloud.GravixcloudModels.TrackType.VIDEO).setMuted(muted))
            .build()
        assertFalse(GravixRegionMedia.infosCarryVideo(listOf(peer(true)), "me"))
        assertTrue(GravixRegionMedia.infosCarryVideo(listOf(peer(false)), "me"))
    }
}
