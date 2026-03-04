/*
 * Copyright 2026 Peter Geschel
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

package io.konkin.web;

import io.konkin.db.entity.PageResult;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WebUtils {

    public static final String SESSION_COOKIE_NAME = "konkin_landing_session";

    private WebUtils() {
    }

    public static boolean hasValidSession(io.javalin.http.Context ctx, java.util.Map<String, java.time.Instant> activeSessions) {
        String token = ctx.cookie(SESSION_COOKIE_NAME);
        if (token == null || token.isBlank()) {
            return false;
        }

        java.time.Instant expiry = activeSessions.get(token);
        if (expiry == null) {
            return false;
        }

        if (expiry.isBefore(java.time.Instant.now())) {
            activeSessions.remove(token);
            ctx.removeCookie(SESSION_COOKIE_NAME);
            return false;
        }

        return true;
    }

    public static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int val = Integer.parseInt(raw.trim());
            return Math.max(1, val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    public static Map<String, Object> pageMetaFrom(PageResult<?> result) {
        Map<String, Object> pageMeta = new LinkedHashMap<>();
        pageMeta.put("page", result.page());
        pageMeta.put("pageSize", result.pageSize());
        pageMeta.put("totalRows", result.totalRows());
        pageMeta.put("totalPages", result.totalPages());
        pageMeta.put("sortBy", result.sortBy());
        pageMeta.put("sortDir", result.sortDir());
        pageMeta.put("hasPrev", result.page() > 1);
        pageMeta.put("hasNext", result.totalPages() > 0 && result.page() < result.totalPages());
        pageMeta.put("prevPage", Math.max(1, result.page() - 1));
        pageMeta.put("nextPage", result.totalPages() <= 0 ? 1 : Math.min(result.totalPages(), result.page() + 1));
        return Map.copyOf(pageMeta);
    }
    public static String maskIdentifier(String id) {
        if (id == null || id.isBlank()) return "-";
        String trimmed = id.trim();
        if (trimmed.length() <= 4) return "****";
        return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 2);
    }
}