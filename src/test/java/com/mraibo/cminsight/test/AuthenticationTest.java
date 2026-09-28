package com.mraibo.cminsight.test;

import com.mraibo.cminsight.security.AuthResult;
import com.mraibo.cminsight.security.Authenticator;
import com.mraibo.cminsight.security.LoginThrottle;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP Basic authentication failures and the brute-force guard. */
public class AuthenticationTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "Correct-Horse-9911";

    private static Authenticator authenticator(LoginThrottle throttle) {
        return new Authenticator(TestSupport.credentials(USER, PASSWORD), throttle);
    }

    private static LoginThrottle throttle(int maxFailures) {
        return new LoginThrottle(maxFailures, Duration.ofMinutes(5), 64);
    }

    private static String encoded(String user, String password) {
        String header = TestSupport.basic(user, password);
        return header.substring(header.indexOf(' ') + 1);
    }

    public void correctCredentialsAreAccepted() {
        Authenticator auth = authenticator(throttle(5));

        AuthResult result = auth.authenticate(TestSupport.basic(USER, PASSWORD), "10.0.0.1");
        Assert.assertTrue(result.authenticated(), "the configured pair authenticates");
        Assert.assertNotNull(result.principal(), "a principal is returned");
        Assert.assertEquals(USER, result.principal().user(), "the principal is the configured user");
        Assert.assertTrue(result.failureReason() == null, "a success carries no failure reason");
        Assert.assertFalse(result.lockedOut(), "a success is not a lockout");

        Assert.assertTrue(auth.authenticate("basic " + encoded(USER, PASSWORD), "10.0.0.2").authenticated(),
                "the scheme is matched case-insensitively");
        Assert.assertTrue(auth.authenticate("  Basic   " + encoded(USER, PASSWORD) + "  ", "10.0.0.3")
                        .authenticated(),
                "surrounding whitespace is tolerated");
        Assert.assertEquals("CM Insight", Authenticator.REALM, "the realm is the documented one");
    }

    public void missingOrMalformedHeadersAreRejected() {
        Authenticator auth = authenticator(throttle(100));

        Assert.assertEquals("missing_credentials", auth.authenticate(null, "10.0.0.1").failureReason(),
                "a missing header fails with the missing_credentials reason");
        Assert.assertEquals("missing_credentials", auth.authenticate("   ", "10.0.0.1").failureReason(),
                "a blank header fails the same way");
        Assert.assertFalse(auth.authenticate(null, "10.0.0.1").authenticated(), "a missing header never authenticates");

        Assert.assertEquals("malformed_authorization", auth.authenticate("Bearer abc", "10.0.0.1").failureReason(),
                "another scheme is malformed");
        Assert.assertEquals("malformed_authorization", auth.authenticate("Basic", "10.0.0.1").failureReason(),
                "a scheme without a value is malformed");
        Assert.assertEquals("malformed_authorization", auth.authenticate("Basic !!!not-base64!!!", "10.0.0.1")
                .failureReason(), "invalid base64 is malformed");
        Assert.assertEquals("malformed_authorization",
                auth.authenticate("Basic " + Base64.getEncoder()
                        .encodeToString("nocolon".getBytes(StandardCharsets.UTF_8)), "10.0.0.1").failureReason(),
                "a decoded value without a colon is malformed");
        Assert.assertEquals("malformed_authorization",
                auth.authenticate("Basic " + "A".repeat(4100), "10.0.0.1").failureReason(),
                "an oversized header is malformed");
        Assert.assertEquals("malformed_authorization",
                auth.authenticate("Basic " + Base64.getEncoder()
                        .encodeToString(("a".repeat(2000) + ":x").getBytes(StandardCharsets.UTF_8)),
                        "10.0.0.1").failureReason(),
                "an oversized decoded value is malformed");
    }

    public void wrongCredentialsFailWithoutEchoingAnything() {
        Authenticator auth = authenticator(throttle(100));
        String guess = "Wrong-Guess-4477";

        AuthResult failed = auth.authenticate(TestSupport.basic(USER, guess), "10.0.0.2");
        Assert.assertFalse(failed.authenticated(), "a wrong password does not authenticate");
        Assert.assertEquals("invalid_credentials", failed.failureReason(), "the reason is a fixed internal code");
        Assert.assertTrue(failed.principal() == null, "a failure produces no principal");
        Assert.assertFalse(failed.lockedOut(), "a plain failure is not a lockout");
        Assert.assertFalse(failed.toString().contains(guess),
                "AuthResult never echoes the submitted credential: " + failed);
        Assert.assertFalse(failed.toString().contains(TestSupport.basic(USER, guess)),
                "AuthResult never echoes the Authorization header: " + failed);
        Assert.assertFalse(failed.failureReason().contains(guess), "the reason never carries the credential");
    }

    /**
     * The comparison path must not depend on which half of the pair is wrong.
     *
     * <p>A short-circuiting comparison ({@code &&}, {@code equals}) leaks which field matched, and a
     * length-dependent one leaks the credential length. The production code compares byte arrays with
     * {@code MessageDigest.isEqual} and combines the results with {@code &} (verified by inspection);
     * the observable contract asserted here is that a user mismatch, a password mismatch, a
     * combination, a prefix and an extension all produce the identical fixed reason and all count
     * exactly one throttle failure, so no ordinal information leaks through the result.
     */
    public void mismatchesAreIndistinguishableAndAllCounted() {
        LoginThrottle throttle = throttle(10);
        Authenticator auth = authenticator(throttle);

        AuthResult wrongUser = auth.authenticate(TestSupport.basic("intruder", PASSWORD), "10.1.0.1");
        AuthResult wrongPassword = auth.authenticate(TestSupport.basic(USER, "wrong"), "10.1.0.2");
        AuthResult bothWrong = auth.authenticate(TestSupport.basic("intruder", "wrong"), "10.1.0.3");
        AuthResult emptyPassword = auth.authenticate(TestSupport.basic(USER, ""), "10.1.0.4");
        AuthResult prefixUser = auth.authenticate(TestSupport.basic(USER.substring(0, 2), PASSWORD), "10.1.0.5");
        AuthResult longerPassword = auth.authenticate(TestSupport.basic(USER, PASSWORD + "x"), "10.1.0.6");

        for (AuthResult result : List.of(wrongUser, wrongPassword, bothWrong, emptyPassword, prefixUser,
                longerPassword)) {
            Assert.assertFalse(result.authenticated(), "every mismatch fails: " + result);
            Assert.assertEquals("invalid_credentials", result.failureReason(),
                    "every mismatch reports the same reason: " + result);
        }
        Assert.assertEquals(6, throttle.trackedKeyCount(),
                "every distinct source counted exactly one failure, whether the user or the password was wrong");
        Assert.assertFalse(throttle.isLocked("10.1.0.1"), "six failures spread over six keys are below the threshold");
    }

    public void aLockedKeyIsRefusedWithoutComparingCredentials() {
        AtomicLong clock = new AtomicLong();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(60), 64, clock::get);
        Authenticator auth = new Authenticator(TestSupport.credentials(USER, PASSWORD), throttle);
        String remote = "10.2.0.1";

        for (int attempt = 0; attempt < 3; attempt++) {
            AuthResult failed = auth.authenticate(TestSupport.basic(USER, "wrong-" + attempt), remote);
            Assert.assertFalse(failed.authenticated(), "attempt " + attempt + " fails");
            Assert.assertFalse(failed.lockedOut(), "attempt " + attempt + " is not yet reported as a lockout");
        }
        Assert.assertTrue(throttle.isLocked(remote), "the key is locked after the threshold");
        Assert.assertEquals(60L, throttle.remainingLockout(remote).toSeconds(), "the full lockout remains");

        AuthResult locked = auth.authenticate(TestSupport.basic(USER, PASSWORD), remote);
        Assert.assertTrue(locked.lockedOut(), "a locked key is refused even with the correct credentials");
        Assert.assertFalse(locked.authenticated(), "a lockout is not an authentication");
        Assert.assertEquals("locked_out", locked.failureReason(), "the lockout reason is its own code");
        Assert.assertTrue(locked.principal() == null, "a lockout produces no principal");
        Assert.assertFalse(throttle.isLocked("10.9.9.9"), "another key is unaffected");

        clock.addAndGet(Duration.ofSeconds(61).toNanos());
        Assert.assertFalse(throttle.isLocked(remote), "the lockout expires with the clock");
        Assert.assertEquals(Duration.ZERO, throttle.remainingLockout(remote), "nothing is left of the lockout");
        Assert.assertTrue(auth.authenticate(TestSupport.basic(USER, PASSWORD), remote).authenticated(),
                "the credentials work again after the lockout");
        Assert.assertEquals(0, throttle.trackedKeyCount(), "a success clears the entry");
    }

    public void recordSuccessClearsTheFailureCounter() {
        AtomicLong clock = new AtomicLong();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(30), 64, clock::get);

        throttle.recordFailure("key");
        throttle.recordFailure("key");
        throttle.recordSuccess("key");
        Assert.assertFalse(throttle.isLocked("key"), "a success clears the accumulated failures");
        Assert.assertEquals(Duration.ZERO, throttle.remainingLockout("key"), "nothing is locked");
        Assert.assertEquals(0, throttle.trackedKeyCount(), "the key is forgotten entirely");

        throttle.recordFailure("key");
        throttle.recordFailure("key");
        Assert.assertFalse(throttle.isLocked("key"), "two failures after a success are below the threshold");
        throttle.recordFailure("key");
        Assert.assertTrue(throttle.isLocked("key"), "the third consecutive failure locks the key");
        Assert.assertTrue(throttle.remainingLockout("key").toSeconds() == 30L, "the configured lockout applies");
    }

    public void trackedKeysCannotGrowWithoutBound() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(1), 64);
        for (int index = 0; index < 2000; index++) {
            throttle.recordFailure("key-" + index);
        }
        Assert.assertTrue(throttle.trackedKeyCount() <= 64,
                "the tracked key map stays bounded by configuration: " + throttle.trackedKeyCount());
        Assert.assertTrue(throttle.trackedKeyCount() > 0, "recent keys are still tracked");
        Assert.assertFalse(throttle.isLocked("key-1999"), "a recent key with one failure is not locked");
    }

    public void concurrentFailuresAreSafeAndKeepTheMapBounded() throws Exception {
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(30), 64);
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();

        for (int slot = 0; slot < threads; slot++) {
            final int index = slot;
            Thread worker = new Thread(() -> {
                try {
                    start.await(20, TimeUnit.SECONDS);
                    for (int attempt = 0; attempt < 20; attempt++) {
                        throttle.recordFailure("shared");
                    }
                    for (int attempt = 0; attempt < perThread; attempt++) {
                        String key = "addr-" + (index * perThread + attempt);
                        throttle.recordFailure(key);
                        throttle.isLocked(key);
                        throttle.remainingLockout(key);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            }, "throttle-" + slot);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        Assert.assertTrue(done.await(20, TimeUnit.SECONDS), "every throttle thread must finish");
        Assert.assertTrue(failures.isEmpty(), "no concurrent failure: " + failures);
        Assert.assertTrue(throttle.trackedKeyCount() <= 64,
                "the map cannot grow without bound: " + throttle.trackedKeyCount());
        Assert.assertFalse(throttle.isLocked("addr-0"), "a key with one failure is not locked");
    }

    public void aLockedKeySurvivesConcurrentHammering() throws Exception {
        // A deterministic nanosecond clock keeps the concurrency but makes the lockout window exact:
        // with the wall clock the remaining lockout is 29.999...s deep in the run, so any assertion on
        // it would be a timing assertion.
        AtomicLong clock = new AtomicLong();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(30), 64, clock::get);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int slot = 0; slot < threads; slot++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await(20, TimeUnit.SECONDS);
                    for (int attempt = 0; attempt < 20; attempt++) {
                        throttle.recordFailure("shared");
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            }, "hammer-" + slot);
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        Assert.assertTrue(done.await(20, TimeUnit.SECONDS), "every hammering thread must finish");
        Assert.assertTrue(failures.isEmpty(), "no failure while hammering: " + failures);
        Assert.assertTrue(throttle.isLocked("shared"), "a key with concurrent failures is locked");
        Assert.assertEquals(30_000L, throttle.remainingLockout("shared").toMillis(),
                "the full lockout is remaining on a deterministic clock");
        Assert.assertEquals(30L, LoginThrottle.secondsCeil(throttle.remainingLockout("shared")),
                "the retry hint rounds the remaining lockout up to whole seconds");
        Assert.assertEquals(1, throttle.trackedKeyCount(), "only the hammered key is tracked");
    }

    /**
     * Regression (t5 F3): a key flood must not evict a live lockout.
     *
     * <p>Fails against the pre-fix code, whose prune sorted purely by age: the locked key had been
     * touched before the flood, so it was the oldest entry and was evicted on the first over-limit
     * prune. {@code isLocked()} then returned false and the brute-force guard forgot the attacker.
     */
    public void aKeyFloodCannotEvictALiveLockout() {
        AtomicLong clock = new AtomicLong();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofSeconds(30), 64, clock::get);
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure("attacker");
        }
        Assert.assertTrue(throttle.isLocked("attacker"), "the attacker key is locked first");

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        for (int index = 0; index < 2000; index++) {
            throttle.recordFailure("flood-" + index);
        }

        Assert.assertTrue(throttle.isLocked("attacker"), "the live lockout survived the key flood");
        Assert.assertTrue(throttle.remainingLockout("attacker").toSeconds() == 29L,
                "the remaining lockout is still reported");
        Assert.assertTrue(throttle.trackedKeyCount() <= 64, "the key map is still bounded");
    }

    public void secondsCeilRoundsUpToWholeSeconds() {
        Assert.assertEquals(0L, LoginThrottle.secondsCeil(Duration.ZERO), "zero stays zero");
        Assert.assertEquals(0L, LoginThrottle.secondsCeil(Duration.ofMillis(-1)), "a negative duration is zero");
        Assert.assertEquals(1L, LoginThrottle.secondsCeil(Duration.ofMillis(1)), "a partial second rounds up");
        Assert.assertEquals(1L, LoginThrottle.secondsCeil(Duration.ofSeconds(1)), "a whole second stays one");
        Assert.assertEquals(2L, LoginThrottle.secondsCeil(Duration.ofMillis(1001)), "over a second rounds up");
        Assert.assertEquals(60L, LoginThrottle.secondsCeil(Duration.ofMinutes(1)), "a minute is sixty seconds");
    }

    public void constructorAndKeyValidation() {
        LoginThrottle throttle = throttle(3);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new LoginThrottle(0, Duration.ofSeconds(1), 64), "maxFailures must be at least one");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new LoginThrottle(1, Duration.ZERO, 64), "the lockout must be positive");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> new LoginThrottle(1, Duration.ofSeconds(1), 8), "maxTrackedKeys must be at least sixteen");
        Assert.assertThrows(NullPointerException.class,
                () -> new LoginThrottle(1, Duration.ofSeconds(1), 16, null), "the clock is required");
        Assert.assertThrows(IllegalArgumentException.class, () -> throttle.recordFailure("   "),
                "a blank key is refused");
        Assert.assertThrows(IllegalArgumentException.class, () -> throttle.recordFailure("x".repeat(257)),
                "an over-long key is refused");
        Assert.assertThrows(NullPointerException.class, () -> throttle.recordFailure(null),
                "a null key is refused");
        Assert.assertThrows(NullPointerException.class, () -> throttle.isLocked(null), "a null lookup is refused");
    }
}
