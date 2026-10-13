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

package com.gravixcloud.rtc.util

/**
 * GRAVIX (security): removes anything token-shaped from a log line and keeps the rest of it.
 *
 * - `access_token=` / `token=` query values (an app may put its token into the url it passes);
 * - `Bearer <value>`;
 * - anything shaped like a JWT (`eyJ<header>.<payload>.<signature>`): the server's refresh token arrives in a
 *   SignalResponse, and the SDK logs every response at VERBOSE with its string fields.
 *
 * Same rules as the Flutter SDK's `gravixRedactError` (plus the JWT shape). Used by [GxLog] for every message
 * while [GxLog.redactSecrets] is on.
 */
object GxRedact {
    const val REDACTED = "<redacted>"

    private val queryToken = Regex("""(?i)([?&](?:access_token|token)=)[^&\s"'#]+""")
    private val bearer = Regex("""(?i)(Bearer\s+)[A-Za-z0-9\-_.=+/]+""")
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""")

    /** [text] with every token-shaped value replaced by [REDACTED]. */
    @JvmStatic
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        if (s.contains("token=", ignoreCase = true)) s = queryToken.replace(s) { it.groupValues[1] + REDACTED }
        if (s.contains("bearer", ignoreCase = true)) s = bearer.replace(s) { it.groupValues[1] + REDACTED }
        if (s.contains("eyJ")) s = jwt.replace(s, REDACTED)
        return s
    }
}
