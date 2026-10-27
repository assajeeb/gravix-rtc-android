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

package com.gravixcloud.rtc.token

import com.gravixcloud.rtc.join.GravixJoinRequest
import com.gravixcloud.rtc.join.GravixJoinTokenException
import com.gravixcloud.rtc.join.GravixJoinTokenProvider
import com.gravixcloud.rtc.room.RETIRED_RTC_HOST
import com.gravixcloud.rtc.room.RETIRED_RTC_URL
import com.gravixcloud.rtc.room.RetiredServerException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.function.Function
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GRAVIX (2026-10-27): the device-side token signer. The vectors are generated from the npm package's build
 * (`gravix-rtc-token`, `createTokenSync`, `Date.now` fixed; copied unchanged from the Flutter SDK's
 * test/fixtures/token_vectors.json): byte parity with the JS signer, not with a copy of this one.
 */
class GravixTokenBuilderTest {
    private val vectors = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/token/token_vectors.json")) { "missing token_vectors.json" }.readText(),
    ).jsonObject
    private val appId = vectors["appId"]!!.jsonPrimitive.content
    private val cert = vectors["appCertificate"]!!.jsonPrimitive.content
    private val nowMs = vectors["nowMs"]!!.jsonPrimitive.content.toLong()

    private fun options(o: JsonObject) = GravixTokenOptions(
        room = o["room"]!!.jsonPrimitive.content,
        identity = o["identity"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.content,
        metadata = o["metadata"]?.jsonPrimitive?.content,
        attributes = o["attributes"]?.jsonObject?.let { a -> LinkedHashMap<String, String>().also { m -> a.forEach { (k, v) -> m[k] = v.jsonPrimitive.content } } },
        ttlSeconds = o["ttlSeconds"]?.jsonPrimitive?.content?.toLong() ?: GravixTokenBuilder.DEFAULT_TTL_SECONDS,
        canPublish = o["canPublish"]?.jsonPrimitive?.boolean ?: true,
        canSubscribe = o["canSubscribe"]?.jsonPrimitive?.boolean ?: true,
        canPublishData = o["canPublishData"]?.jsonPrimitive?.boolean ?: true,
        canUpdateOwnMetadata = o["canUpdateOwnMetadata"]?.jsonPrimitive?.boolean ?: true,
        canPublishSources = o["canPublishSources"]?.jsonArray?.map { it.jsonPrimitive.content },
        hidden = o["hidden"]?.jsonPrimitive?.boolean ?: false,
        roomAdmin = o["roomAdmin"]?.jsonPrimitive?.boolean ?: false,
    )

    private fun payload(token: String): JsonObject =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1]), Charsets.UTF_8)).jsonObject

    private fun bad(id: String = appId, c: String = cert, o: GravixTokenOptions = GravixTokenOptions("r", "u")): GravixTokenBuildException {
        try {
            GravixTokenBuilder.create(id, c, o)
        } catch (e: GravixTokenBuildException) {
            return e
        }
        fail("expected GravixTokenBuildException")
        throw AssertionError()
    }

    @Test
    fun byteParityWithTheNpmPackageForEveryVector() {
        val cases = vectors["cases"]!!.jsonObject
        assertTrue(cases.size >= 5)
        for ((name, c) in cases) {
            val o = c.jsonObject
            assertEquals(name, o["token"]!!.jsonPrimitive.content, GravixTokenBuilder.create(appId, cert, options(o["options"]!!.jsonObject), nowMs))
        }
    }

    @Test
    fun signatureIsHs256OverHeaderDotPayloadAndUnpadded() {
        val t = GravixTokenBuilder.create(appId, cert, GravixTokenOptions("r", "u"))
        val p = t.split('.')
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(cert.toByteArray(), "HmacSHA256")) }
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("${p[0]}.${p[1]}".toByteArray()))
        assertEquals(sig, p[2])
        assertFalse(t.contains("="))
        assertEquals("""{"alg":"HS256","typ":"JWT"}""", String(Base64.getUrlDecoder().decode(p[0])))
    }

    @Test
    fun claimsIssuerPrefixedRoomAndExpiryFromTtl() {
        val p = payload(GravixTokenBuilder.create(appId, cert, GravixTokenOptions("r", "u", ttlSeconds = 900), nowMs))
        assertEquals(appId, p["iss"]!!.jsonPrimitive.content)
        assertEquals("${appId}_r", p["video"]!!.jsonObject["room"]!!.jsonPrimitive.content)
        assertEquals(900, p["exp"]!!.jsonPrimitive.int - p["iat"]!!.jsonPrimitive.int)
        assertEquals(nowMs / 1000, p["iat"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun jsonEscapingMatchesJsonStringify() {
        val o = GravixTokenOptions("r", "u", name = "a/b <>&\u007f", metadata = "\u0001\u001f\ud800x")
        val raw = String(Base64.getUrlDecoder().decode(GravixTokenBuilder.create(appId, cert, o).split('.')[1]), Charsets.UTF_8)
        assertTrue(raw, raw.contains("\"name\":\"a/b <>&\u007f\""))
        assertTrue(raw, raw.contains("\"metadata\":\"\\u0001\\u001f\\ud800x\""))
    }

    @Test
    fun refusesWhatTheServerWouldRefuse() {
        bad(id = "")
        bad(id = "APP_x")
        bad(c = "short")
        bad(o = GravixTokenOptions("", "u"))
        bad(o = GravixTokenOptions("r", ""))
        bad(o = GravixTokenOptions("r", "u", ttlSeconds = 0))
        bad(o = GravixTokenOptions("r", "u", ttlSeconds = -1))
        bad(o = GravixTokenOptions("r", "u", ttlSeconds = 24 * 3600L + 1))
        assertNotNull(GravixTokenBuilder.create(appId, cert, GravixTokenOptions("r", "u", ttlSeconds = 24 * 3600L)))
        assertNotNull(GravixTokenBuilder.create(appId, "x".repeat(32), GravixTokenOptions("r", "u")))
    }

    @Test
    fun errorMessagesNeverCarryTheCertificate() {
        val secret = "s3cr3t-" + "k".repeat(30)
        val errs = listOf(
            bad(id = "", c = secret),
            bad(id = "A_b", c = secret),
            bad(c = "x".repeat(31)),
            bad(c = secret, o = GravixTokenOptions("", "u")),
            bad(c = secret, o = GravixTokenOptions("r", "u", ttlSeconds = 0)),
        )
        for (e in errs) assertFalse(e.message, e.message!!.contains(secret) || e.message!!.contains("x".repeat(31)))
    }

    @Test
    fun roomNameAndDisplayName() {
        assertEquals("APP_r", GravixTokenBuilder.roomName("APP", "r"))
        assertEquals("APP_r", GravixTokenBuilder.roomName("APP", "APP_r"))
        assertEquals("r", GravixTokenBuilder.displayName("APP", "APP_r"))
        assertEquals("r", GravixTokenBuilder.displayName("APP", "r"))
    }

    @Test
    fun javaBuilderMakesTheSameOptions() {
        val a = GravixTokenOptions.builder("r", "u").name("Ann").canPublish(false).roomAdmin(true).ttlSeconds(900).build()
        val b = GravixTokenOptions("r", "u", name = "Ann", canPublish = false, roomAdmin = true, ttlSeconds = 900)
        assertEquals(GravixTokenBuilder.create(appId, cert, b, nowMs), GravixTokenBuilder.create(appId, cert, a, nowMs))
    }

    // ------------------------------------------------------------------------------------- the local provider

    @Test
    fun theLocalProviderSignsOnTheDeviceCachesAndAsksNothing() = runBlocking {
        var grants = 0
        val p = GravixJoinTokenProvider.local(appId, cert, "wss://sgp1.example.test", 6 * 3600L) { r ->
            grants++
            GravixTokenOptions(r.room, r.identity, name = "Ann", canPublish = r.canPublish)
        }
        val req = GravixJoinRequest("r1", "u1", true)
        val a = p.getCredentials(req)
        assertEquals("wss://sgp1.example.test", a.url)
        assertFalse(a.fromCache)
        val v = payload(a.token)
        assertEquals("Ann", v["name"]!!.jsonPrimitive.content)
        assertTrue(v["video"]!!.jsonObject["canPublish"]!!.jsonPrimitive.boolean)
        val b = p.getCredentials(req)
        assertTrue(b.fromCache)
        assertEquals(a.token, b.token)
        assertEquals("one signing, then the cache", 1, grants)
        assertEquals(a.token, p.cached(req)!!.token)
        assertFalse(a.toString().contains(a.token))
    }

    @Test
    fun theLocalProviderDefaultsToTheRequestGrantAndAJavaHookWorks() = runBlocking {
        val p = GravixJoinTokenProvider.local(appId, cert, "wss://x.example.test")
        val viewer = payload(p.getCredentials(GravixJoinRequest("r", "u")).token)["video"]!!.jsonObject
        assertFalse(viewer["canPublish"]!!.jsonPrimitive.boolean)
        val admin = GravixJoinTokenProvider.local(
            appId,
            cert,
            "wss://x.example.test",
            900L,
            Function { r -> GravixTokenOptions.builder(r.room, r.identity).roomAdmin(true).hidden(true).build() },
        )
        val v = payload(admin.getCredentials(GravixJoinRequest("r", "u")).token)
        assertTrue(v["video"]!!.jsonObject["roomAdmin"]!!.jsonPrimitive.boolean)
        assertTrue(v["video"]!!.jsonObject["hidden"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aBuildErrorSurfacesAsAFetcherFailureWithoutTheCertificate() = runBlocking {
        val p = GravixJoinTokenProvider.local(appId, "s3cr3t", "wss://x.example.test")
        try {
            p.getCredentials(GravixJoinRequest("r", "u"))
            fail("expected GravixJoinTokenException")
        } catch (e: GravixJoinTokenException) {
            assertEquals(GravixJoinTokenException.Reason.FETCHER, e.reason)
            assertFalse(e.toString().contains("s3cr3t") || e.cause?.message?.contains("s3cr3t") == true)
        }
    }

    @Test
    fun aRetiredUrlIsRefusedWhenTheProviderIsMade() {
        try {
            GravixJoinTokenProvider.local(appId, cert, RETIRED_RTC_URL)
            fail("expected RetiredServerException")
        } catch (e: RetiredServerException) {
            assertEquals(RETIRED_RTC_HOST, e.host)
        }
    }
}
