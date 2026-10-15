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

package com.gravixcloud.rtc.join

/**
 * GRAVIX (0.1.2): when [GravixJoinTokenProvider] presents a cached token instead of fetching a new one.
 *
 * [FAST_JOIN] (the provider's default): younger than half its lifetime and 5 min, with at least 90 s left -- a token
 * fetched at a list's warm-up is still good at the tap, and never one that expires during the connect. Build your own
 * with [Builder].
 */
class GravixTokenReuse private constructor(builder: Builder) {
    /** Never reused with less than this left before the token's expiry (ms). */
    val minRemainingMs: Long = builder.minRemainingMs

    /** Never reused once this old (ms since the fetch; 0: no cap). The only rule for a token without an expiry. */
    val maxAgeMs: Long = builder.maxAgeMs

    /** Never reused once older than this share of its lifetime (fetch to expiry; 0: no such cap). */
    val maxLifetimeFraction: Double = builder.maxLifetimeFraction

    /** Whether a token fetched at [fetchedAtMs], expiring at [expiresAtMs] (0: unknown), may be presented at [nowMs]. */
    fun allows(fetchedAtMs: Long, expiresAtMs: Long, nowMs: Long): Boolean {
        val age = nowMs - fetchedAtMs
        if (maxAgeMs > 0 && age >= maxAgeMs) return false
        if (expiresAtMs <= 0) return true
        if (expiresAtMs - nowMs < minRemainingMs) return false
        if (maxLifetimeFraction > 0) {
            val lifetime = expiresAtMs - fetchedAtMs
            if (age >= lifetime * maxLifetimeFraction) return false
        }
        return true
    }

    override fun toString(): String =
        "GravixTokenReuse(minRemainingMs=$minRemainingMs, maxAgeMs=$maxAgeMs, maxLifetimeFraction=$maxLifetimeFraction)"

    /** Builds a [GravixTokenReuse]; the defaults are "at least 60 s left", no age caps. */
    class Builder {
        internal var minRemainingMs = 60_000L
        internal var maxAgeMs = 0L
        internal var maxLifetimeFraction = 0.0

        fun setMinRemainingMs(ms: Long): Builder = apply { minRemainingMs = ms.coerceAtLeast(0) }

        /** 0: no cap. */
        fun setMaxAgeMs(ms: Long): Builder = apply { maxAgeMs = ms.coerceAtLeast(0) }

        /** Between 0 (no cap) and 1. */
        fun setMaxLifetimeFraction(fraction: Double): Builder = apply { maxLifetimeFraction = fraction.coerceIn(0.0, 1.0) }

        fun build(): GravixTokenReuse = GravixTokenReuse(this)
    }

    companion object {
        /** Age < min(1/2 lifetime, 5 min), at least 90 s left. */
        @JvmField
        val FAST_JOIN: GravixTokenReuse = Builder()
            .setMinRemainingMs(90_000)
            .setMaxAgeMs(5 * 60_000)
            .setMaxLifetimeFraction(0.5)
            .build()

        /** At least 60 s left (the SDK's token sources' rule). */
        @JvmField
        val DEFAULT: GravixTokenReuse = Builder().build()
    }
}
