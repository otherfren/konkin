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

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Per-IP sliding-window rate limiter.
 */
public final class RateLimiter {

    private final int maxRequests;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Returns true if the given IP has exceeded the rate limit.
     * Also records the current request.
     */
    public boolean isRateLimited(String ip) {
        Instant now = Instant.now();
        Deque<Instant> timestamps = buckets.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());

        // Purge expired entries
        Instant cutoff = now.minusSeconds(windowSeconds);
        while (!timestamps.isEmpty()) {
            Instant head = timestamps.peekFirst();
            if (head != null && head.isBefore(cutoff)) {
                timestamps.pollFirst();
            } else {
                break;
            }
        }

        if (timestamps.size() >= maxRequests) {
            return true;
        }

        timestamps.addLast(now);
        return false;
    }

    /**
     * Removes stale entries from all buckets. Call periodically from a background thread.
     */
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(windowSeconds);
        buckets.entrySet().removeIf(entry -> {
            Deque<Instant> timestamps = entry.getValue();
            while (!timestamps.isEmpty()) {
                Instant head = timestamps.peekFirst();
                if (head != null && head.isBefore(cutoff)) {
                    timestamps.pollFirst();
                } else {
                    break;
                }
            }
            return timestamps.isEmpty();
        });
    }
}
