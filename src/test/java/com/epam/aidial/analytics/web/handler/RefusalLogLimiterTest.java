package com.epam.aidial.analytics.web.handler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RefusalLogLimiterTest {

    private static final long WINDOW = Duration.ofSeconds(60).toNanos();

    private final AtomicLong clock = new AtomicLong();
    private final RefusalLogLimiter limiter = new RefusalLogLimiter(clock::get);

    @Test
    void firstIsAdmittedTheRestCountedUntilTheWindowRolls() {
        assertThat(limiter.admit("h", "c", "/p")).isZero();
        assertThat(limiter.admit("h", "c", "/p")).isEqualTo(RefusalLogLimiter.SUPPRESSED);
        assertThat(limiter.admit("h", "c", "/p")).isEqualTo(RefusalLogLimiter.SUPPRESSED);

        clock.addAndGet(WINDOW - 1);
        assertThat(limiter.admit("h", "c", "/p")).isEqualTo(RefusalLogLimiter.SUPPRESSED);

        clock.addAndGet(1);
        assertThat(limiter.admit("h", "c", "/p")).isEqualTo(3L);
        // A fresh window: its own first line carries nothing more.
        clock.addAndGet(WINDOW);
        assertThat(limiter.admit("h", "c", "/p")).isZero();
    }

    @Test
    void concurrentRefusalsAdmitOneAndLoseNoCount() throws Exception {
        final int threads = 8;
        final int perThread = 1_000;
        final AtomicInteger admitted = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (limiter.admit("h", "c", "/p") != RefusalLogLimiter.SUPPRESSED) {
                        admitted.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(admitted).hasValue(1);
        clock.addAndGet(WINDOW);
        assertThat(limiter.admit("h", "c", "/p")).isEqualTo((long) threads * perThread - 1);
    }

    @Test
    void trackedKeysAreBounded() {
        for (int i = 0; i < RefusalLogLimiter.MAX_KEYS * 3; i++) {
            limiter.admit("h", "c", "/p" + i);
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(RefusalLogLimiter.MAX_KEYS + 1);

        // Once the windows expire the sweep reclaims them for new keys.
        clock.addAndGet(WINDOW);
        assertThat(limiter.admit("h", "c", "/fresh")).isZero();
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(RefusalLogLimiter.MAX_KEYS + 1);
    }
}
