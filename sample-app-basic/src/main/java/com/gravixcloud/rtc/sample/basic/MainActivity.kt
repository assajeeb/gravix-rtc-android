/*
 * Copyright 2024 LiveKit, Inc.
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

package com.gravixcloud.rtc.sample.basic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gravixcloud.rtc.GravixRecoveryOptions
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.RoomOptions
import com.gravixcloud.rtc.events.RoomEvent
import com.gravixcloud.rtc.events.collect
import com.gravixcloud.rtc.regions.GravixRegionMeasureOptions
import com.gravixcloud.rtc.regions.GravixRegionUrl
import com.gravixcloud.rtc.regions.GravixRegions
import com.gravixcloud.rtc.renderer.SurfaceViewRenderer
import com.gravixcloud.rtc.renderer.TextureViewRenderer
import com.gravixcloud.rtc.room.Room
import com.gravixcloud.rtc.room.participant.AudioTrackPublishDefaults
import com.gravixcloud.rtc.room.setRegionUrlProviderTesting
import com.gravixcloud.rtc.room.track.LocalVideoTrack
import com.gravixcloud.rtc.room.track.Track
import com.gravixcloud.rtc.room.track.VideoTrack
import com.gravixcloud.rtc.token.TokenRequestOptions
import com.gravixcloud.rtc.token.TokenSource
import com.gravixcloud.rtc.util.LoggingLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.RTCStatsReport

class MainActivity : AppCompatActivity() {

    lateinit var room: Room
    private var dtx = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // GRAVIX: scripted runs (emulator/sim tests) pass options as intent extras, e.g.
        //   adb shell am start -n com.gravixcloud.rtc.sample.basic/.MainActivity \
        //       --es tokenServerUrl http://10.0.2.2:7970/token --es room devkey_x --es identity android-emu \
        //       --es url ws://10.0.2.2:7880 --ez dtx false
        // tokenServerUrl: the token comes from a development token server (tools/sim/dev-token-server.py on the Mac;
        // the emulator reaches the Mac's loopback at 10.0.2.2), so no token is on the command line -- adbd writes
        // every `am start` command line to logcat. (--es token <jwt> still works for manual runs, and leaks there.)
        // dtx=false keeps a silent microphone (the emulator's) sending ~50 audio packets/s, so an
        // independent subscriber can prove that audio flows. sdkLog=true turns on SDK logging (tokens are
        // redacted from every SDK log line: GxLog.redactSecrets).
        if (intent.getBooleanExtra("sdkLog", false)) {
            // --es sdkLogLevel VERBOSE for state-machine timelines (still redacted)
            GravixRtc.loggingLevel = intent.getStringExtra("sdkLogLevel")?.let { LoggingLevel.valueOf(it) } ?: LoggingLevel.INFO
        }
        dtx = intent.getBooleanExtra("dtx", true)

        // Create Room object.
        room = GravixRtc.create(
            applicationContext,
            RoomOptions(audioTrackPublishDefaults = AudioTrackPublishDefaults(dtx = dtx)),
        )

        // GRAVIX: region tests against a local sim (SIMULATED runs only):
        //   --ez regionsTesting true  TEST-ONLY: region discovery/failover for non-Gravix hosts (e.g. 10.0.2.2);
        //                             never in an app build that ships.
        //   --es regionUrls "a=ws://10.0.2.2:7980,b=ws://10.0.2.2:7990"  start-up measurement of these regions
        //   --es recovery upstream    GravixRecoveryOptions.UPSTREAM (A/B against the upstream engine)
        if (intent.getBooleanExtra("regionsTesting", false)) {
            Log.w(TAG, "TEST-ONLY: region failover enabled for non-Gravix hosts")
            setRegionUrlProviderTesting(true)
        }
        if (intent.getStringExtra("recovery") == "upstream") {
            room.gravixRecovery = GravixRecoveryOptions.UPSTREAM
        }
        // --es recoveryOff a,b: these switches off, the rest at their defaults (A/B runs of one switch)
        intent.getStringExtra("recoveryOff")?.split(',')?.map { it.trim() }?.forEach { f ->
            val r = room.gravixRecovery
            room.gravixRecovery = when (f) {
                "measuredRegionFailover" -> r.copy(measuredRegionFailover = false)
                "retrySameUrlFirst" -> r.copy(retrySameUrlFirst = false)
                "failoverNeedsEvidence" -> r.copy(failoverNeedsEvidence = false)
                "lastGoodRegionList" -> r.copy(lastGoodRegionList = false)
                "ignoreStaleSignalFailures" -> r.copy(ignoreStaleSignalFailures = false)
                "refusalEndsConnect" -> r.copy(refusalEndsConnect = false)
                "resumeOnPeerDisconnected" -> r.copy(resumeOnPeerDisconnected = false)
                "resumePrimaryDeadlineMs" -> r.copy(resumePrimaryDeadlineMs = null)
                "leaveAbortsResumeWait" -> r.copy(leaveAbortsResumeWait = false)
                "restartAbortsOnSignalLoss" -> r.copy(restartAbortsOnSignalLoss = false)
                "waitForNetwork" -> r.copy(waitForNetwork = false)
                "networkWaitProbeIntervalMs" -> r.copy(networkWaitProbeIntervalMs = null)
                "rejoinClearsFullRequest" -> r.copy(rejoinClearsFullRequest = false)
                "signalDialCapMs" -> r.copy(signalDialCapMs = null)
                "silentHostFailFast" -> r.copy(silentHostFailFast = false)
                "resumeWaitsForLiveState" -> r.copy(resumeWaitsForLiveState = false)
                else -> r.also { Log.w(TAG, "unknown recovery switch $f") }
            }
        }
        Log.i(TAG, "recovery ${room.gravixRecovery}")
        room.addConnectedUrlListener { e ->
            Log.i(TAG, "connectedUrl ${e.url} region=${e.region} previous=${e.previousUrl} (${e.previousRegion}) reason=${e.reason}")
        }

        // Setup the video renderer
        room.initVideoRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))
        room.initVideoRenderer(findViewById<TextureViewRenderer>(R.id.local_camera))

        requestNeededPermissions { connectToRoom() }
    }

    /**
     * GRAVIX: the url and token of a scripted run: from the development token server when `tokenServerUrl` is given
     * (`url` overrides the server url it returns), else the `url` / `token` extras. The token is never logged.
     */
    private suspend fun credentials(): Pair<String, String>? {
        val tokenServerUrl = intent.getStringExtra("tokenServerUrl")
            ?: return (intent.getStringExtra("url") ?: "wss://your_host") to (intent.getStringExtra("token") ?: "your_token")
        val response = TokenSource.fromEndpoint(tokenServerUrl).fetch(
            TokenRequestOptions(
                roomName = intent.getStringExtra("room"),
                participantIdentity = intent.getStringExtra("identity"),
                participantName = intent.getStringExtra("identity"),
            ),
        ).getOrElse {
            Log.e(TAG, "token server $tokenServerUrl failed: ${it.javaClass.simpleName}")
            return null
        }
        Log.i(TAG, "token from the token server ($tokenServerUrl; ${response.participantToken.length} chars, not logged)")
        return (intent.getStringExtra("url") ?: response.serverUrl) to response.participantToken
    }

    private fun connectToRoom() {
        lifecycleScope.launch {
            // GRAVIX: start-up region measurement (scripted region runs)
            intent.getStringExtra("regionUrls")?.let { spec ->
                val regions = spec.split(',').mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null else GravixRegionUrl(part.substring(0, i).trim(), part.substring(i + 1).trim())
                }
                val handle = GravixRegions.startMeasurement(
                    applicationContext,
                    GravixRegionMeasureOptions().apply { regionUrls = regions },
                    refreshMs = 0,
                    watchNetwork = false,
                )
                val r = withTimeoutOrNull(5000) { handle.ready.await() }
                Log.i(
                    TAG,
                    "measured best=${r?.best?.region} source=${r?.bestSource} elapsed=${r?.elapsedMs}ms " +
                        r?.regions?.joinToString { "${it.region}:${it.status}:rtt=${it.rttMs}:samples=${it.samplesMs}:conns=${it.connections}" },
                )
            }

            // Setup event handling.
            launch {
                room.events.collect { event ->
                    // GRAVIX: one line per event for scripted runs (no tokens or SDP in these lines).
                    Log.i(TAG, "event ${event::class.simpleName}")
                    when (event) {
                        is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)
                        else -> {}
                    }
                }
            }

            // Connect to server.
            if (!connectOnce()) return@launch

            // GRAVIX: --ei unpublishCameraAtMs N: unpublish the camera N ms after the connect (a renegotiation at a
            // scripted moment, e.g. while the network is down)
            intent.getIntExtra("unpublishCameraAtMs", 0).takeIf { it > 0 }?.let { at ->
                launch {
                    delay(at.toLong())
                    val cam = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track
                    if (cam != null) {
                        room.localParticipant.unpublishTrack(cam)
                        Log.i(TAG, "scripted: camera unpublished")
                    }
                }
            }

            // GRAVIX: --ei rejoinAfterMs N --ei rejoinGapMs M: disconnect N ms after the first connect and connect the
            // SAME Room again M ms later (scripted: e.g. a region taken down in between).
            val rejoinAfterMs = intent.getIntExtra("rejoinAfterMs", 0)
            if (rejoinAfterMs > 0) {
                launch {
                    delay(rejoinAfterMs.toLong())
                    val gap = intent.getIntExtra("rejoinGapMs", 10000).toLong()
                    room.disconnect()
                    Log.i(TAG, "scripted: disconnected; connecting again in $gap ms")
                    delay(gap)
                    if (connectOnce()) {
                        room.localParticipant.setMicrophoneEnabled(true)
                        room.localParticipant.setCameraEnabled(true)
                        Log.i(TAG, "scripted: connected again url=${room.connectedUrl} region=${room.connectedRegion}")
                    }
                }
            }

            // Turn on audio/video recording.
            val localParticipant = room.localParticipant
            localParticipant.setMicrophoneEnabled(true)
            localParticipant.setCameraEnabled(true)
            Log.i(TAG, "published ${localParticipant.trackPublications.values.joinToString { "${it.kind}:${it.source}:${it.sid}" }}")

            // Attach local video camera
            val localTrack = localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
            if (localTrack != null) {
                attachLocalVideo(localTrack)
            }

            // Attach video of remote participant if already available.
            val remoteVideoTrack = room.remoteParticipants.values.firstOrNull()
                ?.getTrackPublication(Track.Source.CAMERA)
                ?.track as? VideoTrack

            if (remoteVideoTrack != null) {
                attachVideo(remoteVideoTrack)
            }

            // GRAVIX: --ez stats true logs RTP packet counters every 5 s (scripted media-flow proof).
            if (intent.getBooleanExtra("stats", false)) {
                launch {
                    while (true) {
                        delay(5000)
                        room.getPublisherRTCStats { logRtp("out", it) }
                        room.getSubscriberRTCStats { logRtp("in", it) }
                    }
                }
            }
        }
    }

    /** GRAVIX: one connect with fresh credentials; false (logged) when it failed. */
    private suspend fun connectOnce(): Boolean {
        val (url, token) = credentials() ?: return false
        // --ei connectDelayMs N: wait between the token and the connect (a scripted run degrades the path meanwhile)
        intent.getIntExtra("connectDelayMs", 0).takeIf { it > 0 }?.let {
            Log.i(TAG, "connecting in $it ms")
            delay(it.toLong())
        }
        Log.i(TAG, "connecting to $url")
        try {
            room.connect(url, token)
        } catch (e: Exception) {
            Log.e(TAG, "Error while connecting to server:", e)
            return false
        }
        Log.i(TAG, "connected room=${room.name} identity=${room.localParticipant.identity?.value} dtx=$dtx")
        return true
    }

    private fun logRtp(direction: String, report: RTCStatsReport) {
        val type = if (direction == "out") "outbound-rtp" else "inbound-rtp"
        val counter = if (direction == "out") "packetsSent" else "packetsReceived"
        val parts = report.statsMap.values
            .filter { it.type == type }
            .map { "${it.members["kind"]}:ssrc=${it.members["ssrc"]}:$counter=${it.members[counter]}" }
            .sorted()
        Log.i(TAG, "rtp $direction ${parts.joinToString(" ")}")
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val track = event.track
        Log.i(TAG, "subscribed ${track.kind} from ${event.participant.identity?.value}")
        if (track is VideoTrack) {
            attachVideo(track)
        }
    }

    private fun attachVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(findViewById<SurfaceViewRenderer>(R.id.renderer))
        findViewById<View>(R.id.progress).visibility = View.GONE
    }

    private fun attachLocalVideo(videoTrack: VideoTrack) {
        videoTrack.addRenderer(findViewById<SurfaceViewRenderer>(R.id.local_camera))
    }

    private fun requestNeededPermissions(onHasPermissions: () -> Unit) {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                var hasDenied = false
                // Check if any permissions weren't granted.
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(this, "Missing permission: ${grant.key}", Toast.LENGTH_SHORT).show()

                        hasDenied = true
                    }
                }

                if (!hasDenied) {
                    onHasPermissions()
                }
            }

        // Assemble the needed permissions to request
        val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED }
            .toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            onHasPermissions()
        }
    }

    companion object {
        private const val TAG = "GravixSample"
    }

    override fun onDestroy() {
        super.onDestroy()
        room.disconnect()
    }
}
