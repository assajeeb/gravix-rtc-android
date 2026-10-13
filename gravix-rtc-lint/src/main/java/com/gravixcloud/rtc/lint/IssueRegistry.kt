package com.gravixcloud.rtc.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue
import com.google.auto.service.AutoService

@Suppress("UnstableApiUsage", "unused")
@AutoService(value = [IssueRegistry::class])
class IssueRegistry : IssueRegistry() {

    override val api: Int = CURRENT_API

    override val vendor: Vendor = Vendor(
        vendorName = "Gravix Cloud",
        identifier = "com.gravixcloud.rtc",
        feedbackUrl = "https://www.gravixcloud.com", // GRAVIX: lint.jar ships inside the AAR
    )

    override val issues: List<Issue>
        get() = listOf(MediaTrackEqualsDetector.ISSUE, FlowDelegateUsageDetector.ISSUE)
}
