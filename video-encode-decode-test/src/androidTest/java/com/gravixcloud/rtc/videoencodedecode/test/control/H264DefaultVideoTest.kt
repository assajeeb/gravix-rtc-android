package com.gravixcloud.rtc.videoencodedecode.test.control

import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.gravixcloud.rtc.videoencodedecode.CallActivity
import com.gravixcloud.rtc.videoencodedecode.VideoTest
import com.gravixcloud.rtc.videoencodedecode.createAndroidIntentComposeRule

class H264DefaultVideoTest : VideoTest() {
    override val composeTestRule: ComposeTestRule = createAndroidIntentComposeRule<CallActivity> { context ->
        Intent(context, CallActivity::class.java).apply {
            putExtra(
                CallActivity.KEY_ARGS,
                CallActivity.BundleArgs(
                    SERVER_URL,
                    token1,
                    token2,
                    useDefaultVideoEncoderFactory = true,
                    codecWhiteList = listOf("H264")
                )
            )
        }
    }
}
