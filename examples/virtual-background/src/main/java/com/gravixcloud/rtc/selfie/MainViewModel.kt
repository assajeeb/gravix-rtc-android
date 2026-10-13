/*
 * Copyright 2024-2025 LiveKit, Inc.
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

package com.gravixcloud.rtc.selfie

import android.app.Application
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.OptIn
import androidx.appcompat.content.res.AppCompatResources
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.GravixRtcOverrides
import com.gravixcloud.rtc.room.track.CameraPosition
import com.gravixcloud.rtc.room.track.LocalVideoTrack
import com.gravixcloud.rtc.room.track.LocalVideoTrackOptions
import com.gravixcloud.rtc.room.track.video.CameraCapturerUtils
import com.gravixcloud.rtc.track.processing.video.VirtualBackgroundVideoProcessor
import com.gravixcloud.rtc.util.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import org.webrtc.CameraXHelper
import org.webrtc.EglBase

@OptIn(ExperimentalCamera2Interop::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    init {
        GravixRtc.loggingLevel = LoggingLevel.INFO
    }

    val eglBase = EglBase.create()
    val room = GravixRtc.create(
        application,
        overrides = GravixRtcOverrides(
            eglBase = eglBase,
        ),
    )

    private val virtualBackground = (AppCompatResources.getDrawable(application, R.drawable.background) as BitmapDrawable).bitmap

    private var blur = 16f
    private val processor = VirtualBackgroundVideoProcessor(eglBase, Dispatchers.IO, initialBlurRadius = blur).apply {
        backgroundImage = virtualBackground
    }

    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null

    private var imageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                // LocalVideoTrack has default aspect ratio 16:9 VideoPreset169.H720
                // ImageAnalysis of CameraX has default aspect ratio 4:3
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build(),
        )
        .build()
        .apply { setAnalyzer(Dispatchers.IO.asExecutor(), processor.imageAnalyzer) }

    init {
        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get(), arrayOf(imageAnalysis)).let {
            if (it.isSupported(application)) {
                CameraCapturerUtils.registerCameraProvider(it)
                cameraProvider = it
            }
        }
    }

    val track = MutableLiveData<LocalVideoTrack?>(null)

    fun startCapture() {
        val videoTrack = room.localParticipant.createVideoTrack(
            options = LocalVideoTrackOptions(position = CameraPosition.FRONT),
            videoProcessor = processor,
        )

        videoTrack.startCapture()
        track.postValue(videoTrack)
    }

    override fun onCleared() {
        super.onCleared()
        track.value?.stopCapture()
        room.release()
        processor.dispose()
        cameraProvider?.let {
            CameraCapturerUtils.unregisterCameraProvider(it)
        }
    }

    fun toggleProcessor(): Boolean {
        val newState = !processor.enabled
        processor.enabled = newState
        return newState
    }

    fun decreaseBlur() {
        blur -= 5
        processor.updateBlurRadius(blur)
    }

    fun increaseBlur() {
        blur += 5
        processor.updateBlurRadius(blur)
    }

    fun toggleVirtualBackground(): Boolean {
        if (processor.backgroundImage != virtualBackground) {
            processor.backgroundImage = virtualBackground
            return true
        } else {
            processor.backgroundImage = null
            return false
        }
    }

    fun flipCamera() {
        val videoTrack = track.value ?: return
        val newPosition = when (videoTrack.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> CameraPosition.FRONT
        }
        videoTrack.switchCamera(position = newPosition)
    }
}
