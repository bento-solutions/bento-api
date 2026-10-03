package com.bento.crm.common.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RateLimitConfig {

    /**
     * Buckets are held in memory keyed by client IP. The key space is bounded in practice now
     * that {@code server.forward-headers-strategy=native} stops a spoofed {@code X-Forwarded-For}
     * from minting a fresh key per request, but an access-ordered LRU with a hard cap is kept as
     * defence in depth: under a distributed flood the map can never grow without limit, and
     * evicting an idle bucket only costs that IP a counter reset.
     */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Bucket> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            });

    /** General per-IP limit for anonymous requests that are not login, signup or invitation. */
    public Bucket resolveBucket(String ipAddress) {
        return bucketFor("general:" + ipAddress, 100, Duration.ofMinutes(1));
    }

    /**
     * Signed-in traffic is limited per user, not per IP. One load of the CRM fires about twenty
     * API calls, so the 100-per-IP budget ran out after four or five page loads -- sooner for
     * colleagues behind one office IP -- and the app came up half-loaded: no profile ("?"),
     * failed task updates, even a forced logout when the token refresh was refused. Refilled
     * gradually rather than once a minute, so a burst costs a short wait, not a dead minute.
     */
    public Bucket resolveUserBucket(String userId) {
        return cache.computeIfAbsent("user:" + userId, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(300, Refill.greedy(300, Duration.ofMinutes(1))))
                .build());
    }

    /**
     * Token refresh gets its own per-IP budget so a burst of ordinary calls can never starve it:
     * a refused refresh leaves the client with an expired session.
     */
    public Bucket resolveRefreshBucket(String ipAddress) {
        return bucketFor("refresh:" + ipAddress, 30, Duration.ofMinutes(1));
    }

    public Bucket resolveAuthBucket(String ipAddress) {
        return bucketFor("auth:" + ipAddress, 10, Duration.ofMinutes(1));
    }

    /**
     * Looser than the signup bucket because the invitee legitimately hits it several times --
     * a preview on page load, another after a reload, then the accept itself -- and an invitee
     * may share an office IP with the admin who invited them. Brute-forcing a 256-bit token is
     * infeasible regardless of the limit; this is depth, not the primary defence.
     */
    public Bucket resolveInvitationBucket(String ipAddress) {
        return bucketFor("invitation:" + ipAddress, 30, Duration.ofHours(1));
    }

    public Bucket resolveSignupBucket(String ipAddress) {
        return bucketFor("signup:" + ipAddress, 5, Duration.ofHours(1));
    }

    private Bucket bucketFor(String key, long capacity, Duration window) {
        return cache.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, window)))
                .build());
    }
}
