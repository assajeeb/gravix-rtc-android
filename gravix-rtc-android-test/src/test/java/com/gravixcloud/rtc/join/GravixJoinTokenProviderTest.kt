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

import com.gravixcloud.rtc.test.BaseTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** GRAVIX (0.1.2): the join-token cache: reuse rules, one fetch at a time, failures not cached, LRU, invalidation. */
@OptIn(ExperimentalCoroutinesApi::class)
class GravixJoinTokenProviderTest : BaseTest() {

    private var now = 1_000_000_000_000L
    private var fetches = 0

    /** An unsigned JWT-shaped token with [expSec] (built at runtime; never a real token). */
    private fun token(expSec: Long?, n: Int): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val exp = if (expSec == null) "" else ""","exp":$expSec"""
        val body = enc.encodeToString("""{"sub":"u$n"$exp}""".toByteArray())
        return "$header.$body.sig$n"
    }

    private fun provider(reuse: GravixTokenReuse = GravixTokenReuse.FAST_JOIN, expInSec: Long? = 600) =
        GravixJoinTokenProvider(
            GravixJoinTokenFetcher { r ->
                fetches++
                GravixJoinCredentials("wss://sgp1.gravixcloud.com", token(expInSec?.let { now / 1000 + it }, fetches))
            },
            reuse,
            coroutineRule.dispatcher,
        ).also { it.clock = { now } }

    private val a = GravixJoinRequest("r1", "user-1")
    private val b = GravixJoinRequest("r2", "user-1")

    @Test
    fun fastJoinRule() {
        val r = GravixTokenReuse.FAST_JOIN
        val t0 = 0L
        val exp10min = 600_000L
        assertTrue(r.allows(t0, exp10min, 60_000))
        assertFalse("half its lifetime", r.allows(t0, exp10min, 300_000))
        assertFalse("5 min cap", r.allows(t0, 3_600_000, 300_000))
        assertTrue(r.allows(t0, 3_600_000, 299_000))
        assertFalse("90 s left", r.allows(t0, 150_000, 61_000))
        assertTrue("no expiry: max age only", r.allows(t0, 0, 299_000))
        assertFalse(r.allows(t0, 0, 300_000))
        assertTrue(GravixTokenReuse.DEFAULT.allows(t0, 3_600_000, 3_500_000))
        assertFalse(GravixTokenReuse.DEFAULT.allows(t0, 3_600_000, 3_550_000))
    }

    @Test
    fun cachedWhileTheRuleAllows() = runTest {
        val p = provider()
        val c1 = p.getCredentials(a)
        assertFalse(c1.fromCache)
        now += 60_000
        val c2 = p.getCredentials(a)
        assertTrue(c2.fromCache)
        assertEquals(c1.token, c2.token)
        assertEquals(1, fetches)
        now += 250_000 // 310 s after the fetch of a 600 s token: past half its lifetime
        assertFalse(p.getCredentials(a).fromCache)
        assertEquals(2, fetches)
        assertEquals(c1.toString().contains("eyJ"), false)
    }

    @Test
    fun oneFetchForConcurrentCallers() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = GravixJoinTokenProvider(
            GravixJoinTokenFetcher {
                fetches++
                gate.await()
                GravixJoinCredentials("wss://x.gravixcloud.com", token(now / 1000 + 600, fetches))
            },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        ).also { it.clock = { now } }
        val d1 = async { p.getCredentials(a) }
        val d2 = async { p.getCredentials(a) }
        gate.complete(Unit)
        assertEquals(d1.await().token, d2.await().token)
        assertEquals(1, fetches)
    }

    @Test
    fun failuresAreNotCached() = runTest {
        var fail = true
        val p = GravixJoinTokenProvider(
            GravixJoinTokenFetcher {
                fetches++
                if (fail) throw IllegalStateException("refused")
                GravixJoinCredentials("wss://x.gravixcloud.com", token(now / 1000 + 600, fetches))
            },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        )
        try {
            p.getCredentials(a)
            fail("no exception")
        } catch (e: GravixJoinTokenException) {
            assertEquals(GravixJoinTokenException.Reason.FETCHER, e.reason)
            assertTrue(e.cause is IllegalStateException)
        }
        fail = false
        assertFalse(p.getCredentials(a).fromCache)
        assertEquals(2, fetches)
    }

    @Test
    fun malformedAndTimeout() = runTest {
        val p = GravixJoinTokenProvider(GravixJoinTokenFetcher { GravixJoinCredentials("", "t") }, GravixTokenReuse.FAST_JOIN, coroutineRule.dispatcher)
        try {
            p.getCredentials(a)
            fail()
        } catch (e: GravixJoinTokenException) {
            assertEquals(GravixJoinTokenException.Reason.MALFORMED, e.reason)
        }
        val never = GravixJoinTokenProvider(
            GravixJoinTokenFetcher { CompletableDeferred<GravixJoinCredentials>().await() },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        ).also { it.timeoutMs = 1_000 }
        val d = async { runCatching { never.getCredentials(a) } }
        testScheduler.advanceTimeBy(1_001)
        val e = d.await().exceptionOrNull()
        assertTrue("$e", e is GravixJoinTokenException && e.reason == GravixJoinTokenException.Reason.TIMEOUT)
    }

    @Test
    fun perRequestEntriesLruOf16() = runTest {
        val p = provider()
        p.getCredentials(a)
        p.getCredentials(b)
        assertTrue(p.getCredentials(a).fromCache)
        assertTrue(p.getCredentials(b).fromCache)
        assertEquals(2, fetches)
        for (i in 0 until 16) p.getCredentials(GravixJoinRequest("room$i", "user-1"))
        assertNull("the least recently used entry left", p.cached(a))
        assertNull(p.cached(b))
        assertTrue(p.cached(GravixJoinRequest("room15", "user-1")) != null)
        // canPublish is part of the key
        assertNull(p.cached(GravixJoinRequest("room15", "user-1", true)))
    }

    @Test
    fun invalidateAllDropsTokensAndAFetchRunningNow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val p = GravixJoinTokenProvider(
            GravixJoinTokenFetcher {
                fetches++
                if (fetches == 2) gate.await()
                GravixJoinCredentials("wss://x.gravixcloud.com", token(now / 1000 + 600, fetches))
            },
            GravixTokenReuse.FAST_JOIN,
            coroutineRule.dispatcher,
        ).also { it.clock = { now } }
        p.getCredentials(a)
        p.invalidate(a)
        val running = async { p.getCredentials(a) }
        p.invalidateAll() // another account signed in while the fetch runs
        gate.complete(Unit)
        running.await()
        assertNull("a fetch started before invalidateAll is not cached", p.cached(a))
    }
}
