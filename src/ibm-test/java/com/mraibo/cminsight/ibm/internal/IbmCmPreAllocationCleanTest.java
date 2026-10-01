package com.mraibo.cminsight.ibm.internal;

import com.mraibo.cminsight.config.DatabaseVendor;
import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.CmSession;
import com.mraibo.cminsight.connection.CreationFailure;
import com.mraibo.cminsight.connection.Lease;
import com.mraibo.cminsight.connection.PoolException;
import com.mraibo.cminsight.connection.ResourceFactory;
import com.mraibo.cminsight.core.CmSessionFactory;
import com.mraibo.cminsight.ibm.CmAdapterSettings;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static com.mraibo.cminsight.ibm.internal.Assert.assertEquals;
import static com.mraibo.cminsight.ibm.internal.Assert.assertFalse;
import static com.mraibo.cminsight.ibm.internal.Assert.assertNotNull;
import static com.mraibo.cminsight.ibm.internal.Assert.assertThrows;
import static com.mraibo.cminsight.ibm.internal.Assert.assertTrue;

/**
 * Goal 02B section A, the blocking regression: a KNOWN-CLEAN failure that happens before the allocation
 * boundary must release the creation slot the pool reserved, and an UNKNOWN failure after that boundary must
 * still quarantine it.
 *
 * <h2>The regression this suite exists for</h2>
 *
 * <p>Goal 02A made the pool fail-safe in the right direction: a reserved creation slot is released
 * <strong>only</strong> for an explicit {@link CreationFailure.Cleanup#PROVEN_CLEAN}, so every untyped failure
 * quarantines the slot for the lifetime of the pool. That is correct for a failure whose physical outcome the
 * pool cannot see, but it turned two pre-existing paths in {@link IbmCmSessionFactory} into a real capacity
 * leak: {@code validateProfile()} and {@code resolveCredentials()} both ran BEFORE the try/catch that produced
 * the verdict, and both throw {@code IbmCmFailure} - so a failure that provably allocated nothing, and never
 * even reached the physical layer, permanently consumed a slot.
 *
 * <p>The credential path is not theoretical. The adapter deliberately re-resolves the CM credentials for
 * every session it creates (see {@code CmAdapterSettings}), so a secret that disappears after a successful
 * activation fails the NEXT replacement session. Before this fix, that cost the pool one configured CM
 * session for good - a pool of size 4 running at 3 - for a resource that was never created. This suite makes
 * the credential source disappear for real (the value lives in a secret FILE, which the test removes and
 * restores between two creation attempts) rather than simulating the missing value with a factory that was
 * credential-less from the start.
 *
 * <h2>What "the real production seam" means here</h2>
 *
 * <p>Every test drives production code for the whole decision: the real {@link IbmCmSessionFactory}, the real
 * {@link IbmCmSession}/{@link IbmCmCleanupFailure} lifecycle, the real {@link BoundedPool} accounting and the
 * same {@link ResourceFactory} shape the core builds around {@code CmSessionFactory.open(profile)}. The ONLY
 * faked layer is the physical one - {@link IbmFakes.FakeConnections} behind {@code IbmCmConnectionFactory} and
 * {@link IbmFakes.FakeDatastore} behind {@code IcmDatastore} - because no IBM CM 8.7 server exists on a CI
 * machine. Nothing here asserts a call returned: the assertions are the pool's own capacity metrics, the
 * measured PHYSICAL CONNECT COUNT, the verdict, and the attempt diagnostic.
 *
 * <h2>Both directions are asserted, on purpose</h2>
 *
 * <p>{@link #aCredentialThatDisappearsBeforeAReplacementReleasesTheSlotWithoutConnecting()} shows that a
 * known-clean pre-allocation failure no longer burns capacity.
 * {@link #anUnknownFailureAfterTheAllocationBoundaryStillQuarantinesTheReplacement()} is the opposite control
 * on the same pool at the same replacement point: a failure the adapter cannot classify must still quarantine.
 * Without that control the first test would only demonstrate that failures can be relabelled clean, which is
 * exactly the property Goal 02A exists to prevent.
 */
public final class IbmCmPreAllocationCleanTest {

    /** Long enough that a borrow never times out for the reason a test is not about. */
    private static final Duration PATIENT = Duration.ofSeconds(5);

    /** Short enough to assert backpressure quickly: a quarantined slot must not authorise a replacement. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    /** The secret file the fake CM user comes from; removing it is how this suite loses a credential. */
    private static final String CM_USER_FILE = "cm-user.txt";
    /** The secret file the fake CM password comes from. */
    private static final String CM_PASSWORD_FILE = "cm-password.txt";

    /** A distinctive user value, so a test can assert it never appears in any operator text. */
    private static final String CM_USER_VALUE = "cm-user-value-77";
    /** A distinctive password value, never to be reproduced either. */
    private static final String CM_PASSWORD_VALUE = "cm-password-value-99";

    /**
     * The regression. Creates and activates a pool, forces one session to retire, removes the CM credential
     * source, and proves the replacement failure is reported clean, costs ZERO additional physical connect
     * attempts, quarantines nothing, releases the reserved slot - and that the pool recovers to normal
     * capacity once the credential source is back.
     */
    public void aCredentialThatDisappearsBeforeAReplacementReleasesTheSlotWithoutConnecting() throws Exception {
        Fixture fixture = new Fixture("credential-gone");
        BoundedPool<CmSession> pool = fixture.pool(1, PATIENT);
        try {
            // (1) the repository activates: the pool is filled with one real session.
            pool.initialize();
            assertEquals(1, fixture.connections.connectAttempts(),
                    "A: activation must open exactly one physical session in a pool of size 1");
            assertEquals(1L, pool.metrics().initialCreations(), "A: that creation is an initial one");
            assertEquals(1, pool.metrics().available(), "A: the created session is idle in the pool");
            assertEquals(1, pool.metrics().capacityInUse(), "A: one of the configured slots is consumed");

            // (2) force the only session to retire, so the next borrow MUST create a replacement. This uses
            // the pool's own retirement path (an UNHEALTHY rotation on lease return), not a test shortcut.
            IbmCmSession retired = retireTheOnlySession(pool);
            assertEquals(0, pool.metrics().available(), "A: the retired session left the idle set");
            assertEquals(0, pool.metrics().capacityInUse(),
                    "A: retiring it released the slot, so the pool now needs a replacement creation");
            assertEquals(1L, pool.metrics().unhealthyRotations(), "A: the retirement came from the health probe");

            // (3) the CM credential source disappears AFTER a successful activation.
            fixture.hideCredentials();
            int attemptsBeforeReplacement = fixture.connections.connectAttempts();

            // (4) the replacement borrow fails. It must fail with a CLEAN verdict, and it must not touch the
            // physical layer at all: the credential could not even be resolved, so there is nothing to
            // connect with and nothing that could have been allocated.
            PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                    "A: the borrow that needs the missing credential must fail with the pool's failure type");
            assertTrue(surfaced.getCause() instanceof CreationFailure,
                    "A: the pool must surface the adapter's verdict as the cause, so the cleanup outcome is"
                            + " still readable; got " + surfaced.getCause());
            CreationFailure verdict = (CreationFailure) surfaced.getCause();
            assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, verdict.cleanup(),
                    "A: a credential that cannot be resolved is refused BEFORE any physical resource exists, so"
                            + " it must report PROVEN_CLEAN - reporting it as an untyped failure quarantines an"
                            + " empty slot and permanently costs the pool a configured CM session");
            assertEquals(attemptsBeforeReplacement, fixture.connections.connectAttempts(),
                    "A: ZERO additional physical connect attempts are allowed for a missing credential"
                            + " (measured before=" + attemptsBeforeReplacement + ", after="
                            + fixture.connections.connectAttempts() + ")");
            assertEquals(1, fixture.connections.connectAttempts(),
                    "A: the measured physical connect count for the whole scenario is exactly 1 - the"
                            + " activation; the failed replacement never reached the physical layer");

            // (5) nothing was quarantined and the reserved slot came back.
            assertEquals(0, pool.metrics().quarantined(),
                    "A: a known-clean failure must not quarantine a slot");
            assertEquals(0L, pool.metrics().createQuarantineFailures(),
                    "A: no create-quarantine may be counted for a known-clean failure");
            assertEquals(0, pool.metrics().capacityInUse(),
                    "A: the reserved slot was RELEASED, so the pool is not holding capacity for a session that"
                            + " was never created");
            assertFalse(pool.metrics().degraded(),
                    "A: the pool must not report itself degraded for a failure that released everything");
            assertEquals(1L, pool.metrics().createFailures(),
                    "A: the failed replacement is still counted as a failed creation");
            assertFalse(fixture.sessions.lastAttemptLeftResources(),
                    "A: the attempt diagnostic must describe THIS attempt, which provably left nothing behind -"
                            + " a stale true from an earlier attempt would misreport it");

            // The sanitised cause and the operator text survive, and carry no credential value.
            assertTrue(verdict.getCause() instanceof IbmCmFailure,
                    "A: the original sanitised IbmCmFailure must survive as the cause; got "
                            + verdict.getCause());
            assertEquals("configuration", ((IbmCmFailure) verdict.getCause()).category(),
                    "A: a credential that cannot be resolved is a configuration failure, not a vendor one");
            assertTrue(verdict.getMessage().contains(RepositoryProfile.CM_USER_KEY),
                    "A: the operator text must name the credential key that failed: " + verdict.getMessage());
            assertFalse(verdict.getMessage().contains(CM_USER_VALUE),
                    "A: the operator text must never reproduce the credential value");
            assertFalse(fixture.sessions.lastAdapterErrorText().contains(CM_PASSWORD_VALUE),
                    "A: the recorded diagnostic must never reproduce the credential value either");
            assertFalse(fixture.sessions.lastAdapterErrorText().isBlank(),
                    "A: the failure must still be recorded for diagnostics, as before the fix");

            // (6) the credential source comes back.
            fixture.restoreCredentials();

            // (7) the pool recovers to normal capacity with a FRESH physical session.
            try (Lease<CmSession> lease = pool.borrow()) {
                CmSession replacement = lease.value();
                assertNotNull(replacement, "A: the pool creates a replacement once the credential resolves");
                assertTrue(replacement.isHealthy(), "A: the replacement is a live session");
                assertFalse(replacement == retired,
                        "A: the replacement is a new physical session, not the retired one");
            }
            assertEquals(2, fixture.connections.connectAttempts(),
                    "A: the replacement is the second - and only other - physical connect of the scenario");
            assertEquals(2L, pool.metrics().created(), "A: two sessions have been created in total");
            assertEquals(1L, pool.metrics().replacementCreations(),
                    "A: the second creation is counted as a replacement, not as an initial one");
            assertEquals(1, pool.metrics().available(), "A: the pool holds one idle session again");
            assertEquals(1, pool.metrics().capacityInUse(), "A: the pool is back at its full capacity");
            assertEquals(0, pool.metrics().quarantined(), "A: nothing is quarantined after recovery");
            assertFalse(pool.metrics().degraded(), "A: the pool reports normal capacity again");
        } finally {
            pool.close();
            fixture.close();
        }
    }

    /**
     * The opposite control, at the same replacement point: a failure that the adapter cannot classify must
     * still quarantine the slot.
     *
     * <p>This is the control without which the regression test above would only show that some failures can be
     * relabelled clean. Here the physical layer throws a plain exception AFTER the allocation boundary, which
     * says nothing about whether a datastore was allocated. The adapter must let it cross unchanged, and the
     * pool's conservative default must quarantine the slot - so the next borrow is refused rather than opening
     * a replacement beside a session that may still exist.
     */
    public void anUnknownFailureAfterTheAllocationBoundaryStillQuarantinesTheReplacement() throws Exception {
        Fixture fixture = new Fixture("unknown-after-boundary");
        BoundedPool<CmSession> pool = fixture.pool(1, IMPATIENT);
        try {
            pool.initialize();
            retireTheOnlySession(pool);

            // The physical layer fails in a way that carries no cleanup evidence at all.
            fixture.connections.failNext(IbmFakes.UnexpectedFailure.ordinaryException(
                    "the connection layer did not say what happened"));
            int attemptsBefore = fixture.connections.connectAttempts();

            PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                    "A: the replacement borrow must fail");

            assertEquals(attemptsBefore + 1, fixture.connections.connectAttempts(),
                    "A: this failure happened AFTER the allocation boundary, so the physical connect WAS"
                            + " attempted - the mirror image of the known-clean pre-allocation case");
            assertFalse(surfaced.getCause() instanceof CreationFailure,
                    "A: an unclassifiable failure must cross the adapter unchanged. Inventing PROVEN_CLEAN"
                            + " here would free a slot for a session that may still exist; got "
                            + surfaced.getCause());
            assertEquals(1, pool.metrics().quarantined(),
                    "A: an unknown post-boundary failure MUST quarantine the reserved slot");
            assertEquals(1L, pool.metrics().createQuarantineFailures(),
                    "A: the quarantine must be counted so the lost capacity is visible");
            assertEquals(1, pool.metrics().capacityInUse(),
                    "A: the quarantined slot keeps consuming capacity");
            assertTrue(pool.metrics().degraded(), "A: a quarantined slot makes the pool report itself degraded");
            assertTrue(fixture.sessions.lastAttemptLeftResources(),
                    "A: the attempt diagnostic must report the current attempt as not proven clean");

            int attemptsAfterQuarantine = fixture.connections.connectAttempts();
            assertThrows(TimeoutException.class, pool::borrow,
                    "A: a quarantined slot must not authorise a replacement session");
            assertEquals(attemptsAfterQuarantine, fixture.connections.connectAttempts(),
                    "A: no physical connection may be attempted on top of a session that may still be alive");
        } finally {
            pool.close();
            fixture.close();
        }
    }

    /**
     * The explicit form of the same control: an {@link IbmCmCleanupFailure} after the allocation boundary -
     * the physical layer's own statement that a teardown step did not return normally - must quarantine too.
     *
     * <p>Together with the untyped control above, this pins BOTH shapes of "post-boundary, not proven clean"
     * against the pre-allocation fix, so the fix cannot have been implemented by widening the clean claim.
     */
    public void anUnprovenCleanupAfterTheAllocationBoundaryStillQuarantinesTheReplacement() throws Exception {
        Fixture fixture = new Fixture("unproven-after-boundary");
        BoundedPool<CmSession> pool = fixture.pool(1, IMPATIENT);
        try {
            pool.initialize();
            retireTheOnlySession(pool);

            fixture.connections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("destroy threw"));
            PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                    "A: the replacement borrow must fail");

            assertTrue(surfaced.getCause() instanceof CreationFailure,
                    "A: an explicit cleanup failure must reach the pool as a verdict; got " + surfaced.getCause());
            assertEquals(CreationFailure.Cleanup.UNPROVEN,
                    ((CreationFailure) surfaced.getCause()).cleanup(),
                    "A: a teardown that did not return normally must stay UNPROVEN and quarantine the slot");
            assertEquals(1, pool.metrics().quarantined(), "A: the slot must be quarantined");
            assertEquals(1L, pool.metrics().createQuarantineFailures(), "A: and counted");
            assertEquals(1, pool.metrics().capacityInUse(), "A: the slot stays consumed");
            assertTrue(pool.metrics().degraded(), "A: the pool is degraded, deliberately");
            assertTrue(fixture.sessions.lastAttemptLeftResources(),
                    "A: the attempt diagnostic must report the current attempt as not proven clean");
        } finally {
            pool.close();
            fixture.close();
        }
    }

    /**
     * Request validation before allocation, driven through the pool: a factory asked for a DIFFERENT
     * repository refuses before it resolves a credential or allocates anything, and that refusal must release
     * the reserved slot instead of consuming it.
     *
     * <p>{@link IbmCmSessionFactory#open(RepositoryProfile)} is the method the core's own
     * {@code ResourceFactory} calls, so this is the production entry point; a wiring mistake that asks a
     * factory for the wrong repository is exactly the case the adapter's guard exists to catch. The second
     * half of the test shows the pool recovers as soon as the request is right, which is only possible because
     * the refusal did not quarantine anything.
     */
    public void aRequestForADifferentRepositoryIsCleanBeforeAnyConnect() throws Exception {
        Fixture fixture = new Fixture("request-guard");
        RequestProfileFactory requests = new RequestProfileFactory(fixture.sessions, fixture.profileFor("other"));
        BoundedPool<CmSession> pool = fixture.pool(1, PATIENT, requests);
        try {
            PoolException surfaced = assertThrows(PoolException.class, pool::borrow,
                    "A: a request for another repository must fail the borrow");

            assertTrue(surfaced.getCause() instanceof CreationFailure,
                    "A: the refusal must reach the pool as a verdict; got " + surfaced.getCause());
            CreationFailure verdict = (CreationFailure) surfaced.getCause();
            assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, verdict.cleanup(),
                    "A: a refused request allocates nothing, so it must report PROVEN_CLEAN");
            assertTrue(verdict.getMessage().contains("was asked to open"),
                    "A: the operator text must say which repository was asked for: " + verdict.getMessage());
            assertEquals(0, fixture.connections.connectAttempts(),
                    "A: a refused request must never reach the physical layer");
            assertEquals(0, pool.metrics().quarantined(), "A: nothing may be quarantined");
            assertEquals(0L, pool.metrics().createQuarantineFailures(), "A: and nothing counted as quarantined");
            assertEquals(0, pool.metrics().capacityInUse(), "A: the reserved slot was released");
            assertFalse(pool.metrics().degraded(), "A: the pool is not degraded by a released failure");
            assertFalse(fixture.sessions.lastAttemptLeftResources(),
                    "A: the attempt diagnostic must describe THIS attempt, which left nothing behind");

            // Recovery: the same pool creates the right repository's session once it is asked for it.
            requests.request(fixture.profile);
            try (Lease<CmSession> lease = pool.borrow()) {
                assertNotNull(lease.value(), "A: the pool creates a session for the correct request");
                assertTrue(lease.value().isHealthy(), "A: and it is live");
            }
            assertEquals(1, fixture.connections.connectAttempts(),
                    "A: the recovered borrow is the only physical connect of the scenario");
            assertEquals(1, pool.metrics().available(), "A: the pool holds its session again");
            assertEquals(0, pool.metrics().quarantined(), "A: nothing is quarantined after recovery");
        } finally {
            pool.close();
            fixture.close();
        }
    }

    /**
     * The same invariant on the ACTIVATION path: a pool whose very first creation fails for a pre-allocation
     * reason must still be initializable again.
     *
     * <p>This is the strongest form of "the slot was released": a quarantined slot makes
     * {@code BoundedPool.initialize()} refuse forever ("cannot be initialized: 1 of its 1 slot(s) are already
     * accounted for"), so a successful second {@code initialize()} proves the reservation came back rather
     * than being merely invisible. Before the fix, this test failed at the assertion on
     * {@code quarantined()} and the retry threw.
     */
    public void aPreAllocationFailureDuringActivationLeavesThePoolInitializable() throws Exception {
        Fixture fixture = new Fixture("activation-clean");
        fixture.hideCredentials();
        BoundedPool<CmSession> pool = fixture.pool(2, PATIENT);
        try {
            CreationFailure verdict = assertThrows(CreationFailure.class, pool::initialize,
                    "A: an activation with no usable credential must fail with the adapter's verdict");
            assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, verdict.cleanup(),
                    "A: nothing was allocated, so the verdict must be PROVEN_CLEAN");
            assertEquals(0, fixture.connections.connectAttempts(),
                    "A: activation must fail before any physical connect is attempted");
            assertEquals(0, pool.metrics().quarantined(), "A: no slot may be quarantined");
            assertEquals(0L, pool.metrics().createQuarantineFailures(), "A: and none counted");
            assertEquals(0, pool.metrics().capacityInUse(),
                    "A: the pool must hold no capacity at all after a known-clean rollback");

            // Recovery: restore the credential and initialize the SAME pool. A quarantined slot would make
            // this refuse, so this is the proof that the reservation was released.
            fixture.restoreCredentials();
            pool.initialize();
            assertEquals(2, fixture.connections.connectAttempts(),
                    "A: the retry creates both configured sessions");
            assertEquals(2, pool.metrics().available(), "A: the pool is at full capacity");
            assertEquals(2L, pool.metrics().initialCreations(), "A: both creations are initial ones");
            assertEquals(0, pool.metrics().quarantined(), "A: nothing is quarantined");
            assertFalse(pool.metrics().degraded(), "A: the pool is not degraded");
        } finally {
            pool.close();
            fixture.close();
        }
    }

    /**
     * The direct form of the credential path, on the factory alone: a factory whose CM credential has no
     * source at all reports a clean verdict, attempts no physical connect, and describes the current attempt.
     */
    public void aCredentialLessFactoryReportsACleanVerdictWithoutConnecting() {
        IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        IbmCmSessionFactory factory = IbmFakes.credentialLessFactory("credential-less", connections);

        CreationFailure verdict = assertThrows(CreationFailure.class, factory::create,
                "A: a credential-less creation must be reported as a CreationFailure to the pool");

        assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, verdict.cleanup(),
                "A: no credential means no physical attempt, so the verdict must be PROVEN_CLEAN");
        assertEquals(0, connections.connectAttempts(),
                "A: the physical layer must not be touched at all");
        assertFalse(factory.lastAttemptLeftResources(),
                "A: the attempt diagnostic must report this attempt, which left nothing behind");
        assertTrue(verdict.getCause() instanceof IbmCmFailure,
                "A: the sanitised configuration failure must survive as the cause; got " + verdict.getCause());
        assertEquals("configuration", ((IbmCmFailure) verdict.getCause()).category(),
                "A: the cause must be classified as a configuration failure");
        assertTrue(verdict.getMessage().contains(RepositoryProfile.CM_USER_KEY),
                "A: the operator text must name the missing credential key: " + verdict.getMessage());
    }

    /**
     * The diagnostic defect Goal 02B calls out: {@code lastAttemptLeftResources} must describe the CURRENT
     * attempt on EVERY failure path.
     *
     * <p>The flag is read by diagnostics and by these suites to answer "did the last creation attempt leave
     * something behind". Before the fix it was written only inside the post-allocation try/catch, so an
     * UNPROVEN attempt followed by a known-clean PRE-allocation failure left it saying {@code true} about an
     * attempt that allocated nothing. The four steps below walk the flag through every path - unproven,
     * known-clean pre-allocation, unproven again, success - so a single missed assignment fails here.
     *
     * <p>Note the honest limit: a fatal JVM {@code Error} raised before the allocation boundary stays
     * untyped and therefore keeps the conservative {@code true} (see the decision recorded on
     * {@code IbmCmSessionFactory.createFor}). No ordinary validation or configuration failure is an
     * {@code Error}, so the paths an operator can cause are all covered below.
     */
    public void theAttemptDiagnosticDescribesTheCurrentAttemptOnEveryFailurePath() throws Exception {
        Fixture fixture = new Fixture("stale-diagnostic");
        try {
            // (a) an UNPROVEN attempt: the diagnostic says resources may have been left behind.
            fixture.connections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("destroy threw"));
            CreationFailure unproven = assertThrows(CreationFailure.class, fixture.sessions::create,
                    "A: the unproven attempt must be reported");
            assertEquals(CreationFailure.Cleanup.UNPROVEN, unproven.cleanup(), "A: teardown was not proven");
            assertTrue(fixture.sessions.lastAttemptLeftResources(),
                    "A: an unproven attempt must be recorded as having left resources behind");

            // (b) a known-clean PRE-ALLOCATION failure immediately after it. The flag must NOT stay true.
            fixture.hideCredentials();
            CreationFailure clean = assertThrows(CreationFailure.class, fixture.sessions::create,
                    "A: the credential failure must be reported");
            assertEquals(CreationFailure.Cleanup.PROVEN_CLEAN, clean.cleanup(),
                    "A: a missing credential allocates nothing, so the verdict is clean");
            assertFalse(fixture.sessions.lastAttemptLeftResources(),
                    "A: the diagnostic must describe THIS attempt. A stale true from the previous UNPROVEN"
                            + " attempt would misreport a failure that provably allocated nothing");

            // (c) and the other direction: a later UNPROVEN attempt must set it again.
            fixture.restoreCredentials();
            fixture.connections.failNext(IbmFakes.UnexpectedFailure.cleanupUnproven("destroy threw again"));
            CreationFailure unprovenAgain = assertThrows(CreationFailure.class, fixture.sessions::create,
                    "A: the second unproven attempt must be reported");
            assertEquals(CreationFailure.Cleanup.UNPROVEN, unprovenAgain.cleanup(),
                    "A: teardown was not proven again");
            assertTrue(fixture.sessions.lastAttemptLeftResources(),
                    "A: a later unproven attempt must be recorded as leaving resources behind");

            // (d) and a successful attempt must clear it.
            CmSession session = fixture.sessions.create();
            assertNotNull(session, "A: a successful creation returns a session");
            assertFalse(fixture.sessions.lastAttemptLeftResources(),
                    "A: a successful attempt left nothing unaccounted for");
            session.close();
        } finally {
            fixture.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Drives the pool's own retirement path: borrow the only session, mark it unusable as a failed operation
     * would, and return the lease. The return path rotates it as UNHEALTHY, closes it (its fake datastore
     * destroys cleanly), and frees the slot - so the next borrow needs a replacement creation.
     */
    private static IbmCmSession retireTheOnlySession(BoundedPool<CmSession> pool) throws Exception {
        IbmCmSession retired;
        try (Lease<CmSession> lease = pool.borrow()) {
            retired = IbmCmSessionFactory.icmSession(lease);
            retired.markUnusable("section A: force a replacement creation");
        }
        return retired;
    }

    /**
     * The whole test fixture: a temporary secrets directory holding the two CM credentials, the REAL
     * {@link IbmCmSessionFactory} reading them through a {@link SecretResolver}, and the fake physical layer.
     *
     * <p>The credentials live in secret FILES rather than in the resolver's environment map, because that is
     * what makes "the credential source disappears after activation" a real event inside one test: the value
     * is resolved per attempt, exactly as the production resolver resolves it, and removing the file makes the
     * resolution fail the same way a rotated/removed secret does.
     *
     * <p>The directory is created under the suite's own scratch root - see {@link #scratchRoot()} - and not
     * under {@code java.io.tmpdir}, which a locked-down host may deny. This mirrors the core suite's
     * {@code TestSupport}, which solved exactly that problem in Goal 01.
     */
    private static final class Fixture implements AutoCloseable {

        final Path secretsDir;
        final RepositoryProfile profile;
        final IbmFakes.FakeConnections connections = new IbmFakes.FakeConnections();
        final IbmCmSessionFactory sessions;

        Fixture(String id) throws IOException {
            secretsDir = Files.createTempDirectory(scratchRoot(), "cm-insight-prealloc-" + id + "-");
            writeSecret(secretsDir, CM_USER_FILE, CM_USER_VALUE);
            writeSecret(secretsDir, CM_PASSWORD_FILE, CM_PASSWORD_VALUE);
            profile = fileProfile(id);
            sessions = new IbmCmSessionFactory(profile,
                    CmAdapterSettings.defaults(new SecretResolver(Map.of(), secretsDir)), connections);
        }

        /** The core's pool seam: a pool over the production ResourceFactory shape around this factory. */
        BoundedPool<CmSession> pool(int size, Duration borrowTimeout) {
            return pool(size, borrowTimeout, IbmFakes.resourceFactory(profile, sessions));
        }

        /** A pool over a resource factory the test supplies, for the request-validation case. */
        BoundedPool<CmSession> pool(int size, Duration borrowTimeout, ResourceFactory<CmSession> resources) {
            return new BoundedPool<>("prealloc:" + profile.id(), size, borrowTimeout, resources);
        }

        /** A valid profile for another repository id, naming the same credential files. */
        RepositoryProfile profileFor(String id) {
            return fileProfile(id);
        }

        /** Makes the CM credential source unavailable, exactly as a removed/rotated secret would. */
        void hideCredentials() throws IOException {
            Files.deleteIfExists(secretsDir.resolve(CM_USER_FILE));
        }

        /** Restores the credential source, so the next attempt can resolve it again. */
        void restoreCredentials() throws IOException {
            writeSecret(secretsDir, CM_USER_FILE, CM_USER_VALUE);
        }

        @Override
        public void close() {
            deleteRecursively(secretsDir);
        }
    }

    /**
     * A profile whose two CM credentials come from secret FILES, so their availability can change between two
     * attempts.
     *
     * <p>The JDBC pair is declared the same way and its files are deliberately never created: the adapter
     * resolves only the two CM credentials (an ItemType/retention read never opens the JDBC side), so if this
     * test ever needed a JDBC value to connect, it would fail for a reason section A is not about.
     */
    private static RepositoryProfile fileProfile(String id) {
        return new RepositoryProfile(
                id,
                "Repository " + id,
                "SSID-" + id,
                DatabaseVendor.DB2,
                "jdbc:db2://db.example:50000/" + id,
                "ICMADMIN",
                RepositoryProfile.credentialFromSecretFile(RepositoryProfile.CM_USER_KEY, CM_USER_FILE),
                RepositoryProfile.credentialFromSecretFile(RepositoryProfile.CM_PASSWORD_KEY, CM_PASSWORD_FILE),
                RepositoryProfile.credentialFromSecretFile(RepositoryProfile.JDBC_USER_KEY, "unused-jdbc-user.txt"),
                RepositoryProfile.credentialFromSecretFile(RepositoryProfile.JDBC_PASSWORD_KEY,
                        "unused-jdbc-password.txt"),
                null,
                null);
    }

    /**
     * The core's pool seam with a test-chosen request, so the adapter's "bound to a different repository"
     * guard can be driven through a pool instead of only directly.
     *
     * <p>Production asks for the profile the factory is bound to; asking for another one is the wiring mistake
     * the guard exists to catch, and this class makes that mistake deliberate and reversible so the same pool
     * can be shown to recover.
     *
     * <p>{@code isHealthy} is stated explicitly rather than inherited: Goal 02B removed
     * {@code ResourceFactory}'s permissive default on purpose, so every factory must say what health means for
     * its resource type. For a CM session that is the session's own local flag - one volatile read, no I/O,
     * which is what the pool's lock rule requires.
     */
    private static final class RequestProfileFactory implements ResourceFactory<CmSession> {

        private final CmSessionFactory sessions;
        private volatile RepositoryProfile requested;

        RequestProfileFactory(CmSessionFactory sessions, RepositoryProfile requested) {
            this.sessions = sessions;
            this.requested = requested;
        }

        void request(RepositoryProfile profile) {
            this.requested = profile;
        }

        @Override
        public CmSession create() throws Exception {
            return sessions.open(requested);
        }

        @Override
        public boolean isHealthy(CmSession resource) {
            // Cheap, local, non-blocking: the session's own in-memory flag, never the vendor's isConnected().
            return resource != null && resource.isHealthy();
        }

        @Override
        public String describe() {
            return "prealloc-request-guard";
        }
    }

    private static void writeSecret(Path dir, String name, String value) throws IOException {
        Files.writeString(dir.resolve(name), value + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    /**
     * The scratch root this suite's secret directories live under.
     *
     * <p>Deliberately NOT {@code java.io.tmpdir}: a locked-down host - and the harness sandbox this project
     * is developed in - denies {@code Files.createTempDirectory} there, while the build tree the suite was
     * started from is writable. A suite that cannot create its fixture directory reports failures that look
     * like adapter defects but are environmental, and on a Linux CI runner (where {@code /tmp} is writable)
     * the problem is invisible - green in CI, red locally. The core suite solved this in Goal 01 by deriving
     * the scratch root from the compiled test classes, and this mirrors that answer rather than inventing a
     * second one; the OS temporary directory remains only as a fallback for a class-path shape with no
     * location at all.
     */
    private static Path scratchRoot() throws IOException {
        try {
            CodeSource codeSource = IbmCmPreAllocationCleanTest.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path classesDir = Paths.get(codeSource.getLocation().toURI());
                Path parent = classesDir.getParent();
                if (parent != null) {
                    return Files.createDirectories(parent.resolve("test-tmp"));
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Fall through to the OS temporary directory.
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."));
    }

    /** Best-effort recursive cleanup: a leftover scratch directory must never fail a test. */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort only.
                }
            }
        } catch (IOException ignored) {
            // Best effort only.
        }
    }
}
