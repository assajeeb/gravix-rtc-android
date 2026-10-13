package com.gravixcloud.rtc.videoencodedecode

import android.app.Application
import com.gravixcloud.rtc.GravixRtc
import com.gravixcloud.rtc.util.LoggingLevel

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        GravixRtc.loggingLevel = LoggingLevel.VERBOSE
    }
}
