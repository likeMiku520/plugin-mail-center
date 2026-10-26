package com.fufu.mail.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Small sliding window limiter for the key protected trigger endpoint. Kept in memory
 * on purpose: the limit protects against a runaway script, it is not an audit feature.
 */
@Component
public class RateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_KEYS = 5_000;

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public boolean allow(String key, int perMinute) {
        if (perMinute <= 0) {
            return true;
        }
        var now = System.currentTimeMillis();
        if (buckets.size() > MAX_TRACKED_KEYS) {
            buckets.clear();
        }
        var bucket = buckets.computeIfAbsent(key == null ? "unknown" : key,
            ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && now - bucket.peekFirst() > WINDOW_MS) {
                bucket.pollFirst();
            }
            if (bucket.size() >= perMinute) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }
}