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

package com.gravixcloud.rtc.noise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// GRAVIX (2026-10-21): remote NS policy (`ns/v1/policy.json`): kill switches, studio allow/deny and standard deny
// lists, thresholds. Rule for rule the Flutter SDK's gravix_noise_policy.dart (= the JS SDK's policy.ts). The parser
// never throws: a malformed document yields `policy == null` + an error, a malformed rule or threshold is skipped.

/** Thresholds for the static check and the benchmark. Null field = default. */
data class GravixNsThresholds(
    val benchmarkP95Us: Int? = null,
    val minRamMb: Int? = null,
    val minCpuMaxFreqMhz: Int? = null,
    val minPerformanceClass: Int? = null,
    val minIosChip: Int? = null,
    val minCores: Int? = null,
    val minDeviceMemoryGb: Double? = null,
) {
    /** Fields of [over] win. */
    fun merge(over: GravixNsThresholds?): GravixNsThresholds = if (over == null) {
        this
    } else {
        GravixNsThresholds(
            benchmarkP95Us = over.benchmarkP95Us ?: benchmarkP95Us,
            minRamMb = over.minRamMb ?: minRamMb,
            minCpuMaxFreqMhz = over.minCpuMaxFreqMhz ?: minCpuMaxFreqMhz,
            minPerformanceClass = over.minPerformanceClass ?: minPerformanceClass,
            minIosChip = over.minIosChip ?: minIosChip,
            minCores = over.minCores ?: minCores,
            minDeviceMemoryGb = over.minDeviceMemoryGb ?: minDeviceMemoryGb,
        )
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        benchmarkP95Us?.let { put("benchmarkP95Us", it) }
        minRamMb?.let { put("minRamMb", it) }
        minCpuMaxFreqMhz?.let { put("minCpuMaxFreqMhz", it) }
        minPerformanceClass?.let { put("minPerformanceClass", it) }
        minIosChip?.let { put("minIosChip", it) }
        minCores?.let { put("minCores", it) }
        minDeviceMemoryGb?.let { put("minDeviceMemoryGb", it) }
    }

    companion object {
        @JvmField
        val DEFAULTS = GravixNsThresholds(
            benchmarkP95Us = 3000,
            minRamMb = 4096,
            minCpuMaxFreqMhz = 2000,
            minPerformanceClass = 31,
            minIosChip = 12,
            minCores = 4,
            minDeviceMemoryGb = 4.0,
        )

        internal fun fromJson(j: JsonObject): GravixNsThresholds = GravixNsThresholds(
            benchmarkP95Us = nsInt(j["benchmarkP95Us"]),
            minRamMb = nsInt(j["minRamMb"]),
            minCpuMaxFreqMhz = nsInt(j["minCpuMaxFreqMhz"]),
            minPerformanceClass = nsInt(j["minPerformanceClass"]),
            minIosChip = nsInt(j["minIosChip"]),
            minCores = nsInt(j["minCores"]),
            minDeviceMemoryGb = nsNum(j["minDeviceMemoryGb"]),
        )
    }
}

/**
 * One allow/deny rule. Every field that is set must match.
 * - `platform`: android | ios | web | * (required)
 * - `model`, `manufacturer`, `soc`: case-insensitive; a trailing `*` = prefix
 * - `uaRegex`: regular expression on the user agent (web)
 * - `osMin` / `osMax`: inclusive dotted versions (`"12"`, `"17.4"`)
 */
data class GravixNsRule(
    val platform: String,
    val model: String? = null,
    val manufacturer: String? = null,
    val soc: String? = null,
    val uaRegex: String? = null,
    val osMin: String? = null,
    val osMax: String? = null,
) {
    private val ua: Regex? by lazy { uaRegex?.let { Regex(it) } }

    /** Whether the device described by these fields matches. */
    @JvmOverloads
    fun matches(
        platform: String,
        model: String? = null,
        manufacturer: String? = null,
        soc: String? = null,
        userAgent: String? = null,
        osVersion: String? = null,
    ): Boolean {
        if (this.platform != "*" && this.platform != platform.lowercase()) return false
        if (this.model != null && !glob(this.model, model)) return false
        if (this.manufacturer != null && !glob(this.manufacturer, manufacturer)) return false
        if (this.soc != null && !glob(this.soc, soc)) return false
        if (ua != null && (userAgent == null || !ua!!.containsMatchIn(userAgent))) return false
        if (osMin != null || osMax != null) {
            val v = osVersion?.let { version(it) } ?: return false
            if (osMin != null && cmp(v, version(osMin)!!) < 0) return false
            if (osMax != null && cmp(v, version(osMax)!!) > 0) return false
        }
        return true
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("platform", platform)
        model?.let { put("model", it) }
        manufacturer?.let { put("manufacturer", it) }
        soc?.let { put("soc", it) }
        uaRegex?.let { put("uaRegex", it) }
        osMin?.let { put("osMin", it) }
        osMax?.let { put("osMax", it) }
    }

    companion object {
        /** Null when the rule is malformed (no platform, bad regex, bad version). */
        internal fun fromJson(o: JsonElement?): GravixNsRule? {
            if (o !is JsonObject) return null
            val p = nsStr(o["platform"]) ?: return null
            val ua = nsStr(o["uaRegex"])
            if (ua != null) {
                try {
                    Regex(ua)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            val osMin = nsStr(o["osMin"])
            val osMax = nsStr(o["osMax"])
            if ((osMin != null && version(osMin) == null) || (osMax != null && version(osMax) == null)) return null
            return GravixNsRule(
                platform = p.lowercase(),
                model = nsStr(o["model"]),
                manufacturer = nsStr(o["manufacturer"]),
                soc = nsStr(o["soc"]),
                uaRegex = ua,
                osMin = osMin,
                osMax = osMax,
            )
        }

        private fun glob(pattern: String, value: String?): Boolean {
            if (value == null) return false
            val p = pattern.lowercase()
            val v = value.lowercase()
            return if (p.endsWith("*")) v.startsWith(p.dropLast(1)) else p == v
        }
    }
}

/** Result of [GravixNsPolicy.parse]: exactly one of [policy] / [error] is set. */
data class GravixNsPolicyParse(
    val policy: GravixNsPolicy?,
    val error: String?,
    /** Malformed rules that were dropped. */
    val skippedRules: Int,
)

/** The parsed policy. */
data class GravixNsPolicy(
    val version: Int = 1,
    /** Everything off (remote kill switch). */
    val killAll: Boolean = false,
    /** Studio off; standard stays. */
    val killStudio: Boolean = false,
    /** Devices that skip the static check for studio (benchmark still runs). */
    val studioAllow: List<GravixNsRule> = emptyList(),
    /** Devices that never get studio. */
    val studioDeny: List<GravixNsRule> = emptyList(),
    /** Devices that get no Gravix NS at all. */
    val standardDeny: List<GravixNsRule> = emptyList(),
    val thresholds: GravixNsThresholds? = null,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        putJsonObject("killSwitch") {
            put("all", killAll)
            put("studio", killStudio)
        }
        putJsonObject("studio") {
            putJsonArray("allow") { studioAllow.forEach { add(it.toJson()) } }
            putJsonArray("deny") { studioDeny.forEach { add(it.toJson()) } }
        }
        putJsonObject("standard") {
            putJsonArray("deny") { standardDeny.forEach { add(it.toJson()) } }
        }
        thresholds?.let { put("thresholds", it.toJson()) }
    }

    companion object {
        /** Supported policy schema version. */
        const val VERSION: Int = 1

        @JvmField
        val EMPTY = GravixNsPolicy()

        /** Parses a policy document: a JSON string or an already decoded [JsonElement]. Never throws. */
        @JvmStatic
        fun parse(source: Any?): GravixNsPolicyParse {
            val root: JsonElement? = when (source) {
                is String -> try {
                    Json.parseToJsonElement(source)
                } catch (e: IllegalArgumentException) {
                    return GravixNsPolicyParse(null, "bad_json: ${e.message}", 0)
                }
                is JsonElement -> source
                else -> null
            }
            if (root !is JsonObject) return GravixNsPolicyParse(null, "not_an_object", 0)
            val version = nsInt(root["version"]) ?: 1
            if (version > VERSION) return GravixNsPolicyParse(null, "unsupported_version: $version", 0)
            var skipped = 0
            fun rules(list: JsonElement?): List<GravixNsRule> {
                if (list !is JsonArray) return emptyList()
                val out = ArrayList<GravixNsRule>()
                for (o in list) {
                    val r = GravixNsRule.fromJson(o)
                    if (r == null) skipped++ else out.add(r)
                }
                return out
            }
            val kill = root["killSwitch"] as? JsonObject ?: JsonObject(emptyMap())
            val studio = root["studio"] as? JsonObject ?: JsonObject(emptyMap())
            val standard = root["standard"] as? JsonObject ?: JsonObject(emptyMap())
            val th = (root["thresholds"] as? JsonObject)?.let { GravixNsThresholds.fromJson(it) }
            val policy = GravixNsPolicy(
                version = version,
                killAll = nsTrue(kill["all"]),
                killStudio = nsTrue(kill["studio"]),
                studioAllow = rules(studio["allow"]),
                studioDeny = rules(studio["deny"]),
                standardDeny = rules(standard["deny"]),
                thresholds = th,
            )
            return GravixNsPolicyParse(policy, null, skipped)
        }

        /** Dotted version compare (-1/0/1), null if unparsable. */
        @JvmStatic
        fun compareVersions(a: String, b: String): Int? {
            val x = version(a) ?: return null
            val y = version(b) ?: return null
            return cmp(x, y)
        }
    }
}

// JSON helpers with the Dart parser's strictness: only a real `true` is true; an int may be a whole double.
internal fun nsStr(o: JsonElement?): String? = (o as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

internal fun nsTrue(o: JsonElement?): Boolean = o is JsonPrimitive && !o.isString && o.content == "true"

internal fun nsNum(o: JsonElement?): Double? {
    if (o !is JsonPrimitive || o.isString || o is JsonNull) return null
    return o.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun nsInt(o: JsonElement?): Int? {
    if (o !is JsonPrimitive || o.isString || o is JsonNull) return null
    o.content.toIntOrNull()?.let { return it }
    val d = o.content.toDoubleOrNull() ?: return null
    return if (d.isFinite() && d == Math.rint(d) && d <= Int.MAX_VALUE && d >= Int.MIN_VALUE) d.toInt() else null
}

/** `"17.4.1"` -> [17, 4, 1]; null when not a dotted number. */
private fun version(s: String): List<Int>? {
    val out = ArrayList<Int>()
    for (p in s.trim().split('.')) {
        val v = p.toIntOrNull() ?: return null
        if (v < 0) return null
        out.add(v)
    }
    return out.ifEmpty { null }
}

private fun cmp(a: List<Int>, b: List<Int>): Int {
    var i = 0
    while (i < a.size || i < b.size) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x < y) -1 else 1
        i++
    }
    return 0
}
