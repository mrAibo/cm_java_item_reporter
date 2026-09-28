package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.RepositoryProfile;
import com.mraibo.cminsight.connection.BoundedPool;
import com.mraibo.cminsight.connection.PoolMetrics;
import com.mraibo.cminsight.core.CloseOutcomeAware;
import com.mraibo.cminsight.repository.RepositoryContext;
import com.mraibo.cminsight.repository.RepositoryException;
import com.mraibo.cminsight.repository.RepositoryManager;
import com.mraibo.cminsight.repository.RepositoryState;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Goal 01B (A and H): a pool quarantine must propagate GENERICALLY from {@code BoundedPool} to
 * {@link RepositoryContext} and {@link RepositoryManager}, so the fail-closed switch rule works before
 * any real CM/JDBC adapter exists.
 *
 * <p>The defect this pins: {@code BoundedPool.close()} quarantines the slot of a pooled resource whose
 * {@code close()} threw and then returns <em>normally</em>. A context that only watched for an exception
 * therefore reported a clean shutdown, and the manager was allowed to create the next context while a
 * physical session might still exist. The decisive test below uses a REAL {@code BoundedPool<FakeResource>}
 * - not a hand-crafted {@link AutoCloseable} that simply throws, which is explicitly not acceptable for
 * this goal - and proves the whole chain: quarantine, context report, manager refusal, factory never
 * called, quarantined slot still visible in the metrics.
 *
 * <p>Every test in this class fails against the pre-fix wiring (the context consulting only thrown
 * exceptions) except the ones that pin the CLEAN path, which must keep passing: an ordinary pool
 * shutdown may never turn a switch into FAILED.
 */
public class RepositoryClosePropagationTest {

    /** Borrow timeout of the pool fixtures; nothing here waits, and no test may hang. */
    private static final Duration IMPATIENT = Duration.ofMillis(200);

    /** The pool bound used by every fixture, so "one quarantined slot" is a meaningful statement. */
    private static final int POOL_SIZE = 2;

    private static RepositoryProfile profile(String id) {
        return TestSupport.profile(id);
    }

    /**
     * A pool of {@value #POOL_SIZE} real fakes, eagerly opened exactly as a production adapter would do
     * at repository activation, so the shutdown under test has physical resources to release.
     */
    private static BoundedPool<FakeResource> initializedPool(FakePoolFactory factory, String name)
            throws Exception {
        BoundedPool<FakeResource> pool = new BoundedPool<>(name, POOL_SIZE, IMPATIENT, factory);
        pool.initialize();
        return pool;
    }

    /**
     * Goal 01B (A) and H. The deployed shape end to end:
     *
     * <ol>
     *   <li>a real {@link BoundedPool} of {@link FakeResource} is owned by the first context;</li>
     *   <li>one POOLED fake throws from {@code close()} before proving itself closed, so the physical
     *       session may still exist;</li>
     *   <li>the pool quarantines that slot and still returns normally from {@code close()};</li>
     *   <li>the context reports the uncertain close even though no exception escaped;</li>
     *   <li>the manager refuses the switch, publishes FAILED and names the cause;</li>
     *   <li>the factory invocation count proves the next context was never created;</li>
     *   <li>the pool metrics still show the quarantined physical slot.</li>
     * </ol>
     *
     * <p>Fails against the pre-fix wiring in exactly one place, and that place is the defect: the factory
     * is called a second time for 'beta' (and the switch succeeds), because
     * {@code RepositoryContext.close()} saw no exception.
     */
    public void poolQuarantineDuringThePreviousCloseRefusesTheSwitchBeforeTheFactory() throws Exception {
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");
        Assert.assertEquals(POOL_SIZE, poolFactory.liveCount(),
                "the pool eagerly opened its physical resources");
        Assert.assertEquals(0, pool.metrics().quarantined(), "a healthy pool starts certain");

        // (2) The real pooled fake: close() throws BEFORE it marks itself closed and before the factory's
        // live count drops. BoundedPool treats that as an uncertain physical outcome.
        FakeResource poisoned = poolFactory.created().get(0);
        poisoned.failCloseUncertain(new IOException("simulated CM session close failure"));

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] firstContext = new RepositoryContext[1];
        RepositoryManager manager = new RepositoryManager(profile -> {
            factoryCalls.add(profile.id());
            if (factoryCalls.size() == 1) {
                RepositoryContext context = new RepositoryContext(profile, List.of(pool));
                firstContext[0] = context;
                return context;
            }
            return new RepositoryContext(profile);
        });

        manager.switchTo(profile("crm"));
        Assert.assertEquals(List.of("crm"), List.copyOf(factoryCalls), "only the first context was created");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "the first repository is active");
        Assert.assertEquals(0, pool.metrics().quarantined(),
                "nothing is quarantined while the pool is still in service");

        RepositoryException refusal = Assert.assertThrows(RepositoryException.class,
                () -> manager.switchTo(profile("beta")),
                "a quarantined pool slot during the previous close must refuse the switch");

        // (6) The decisive assertion: the new-context factory was never called for the refused switch.
        Assert.assertEquals(List.of("crm"), List.copyOf(factoryCalls),
                "the factory was never called for the refused switch");

        // (3) and (7): the quarantine really happened, the pool itself ended its shutdown normally, and
        // the quarantined physical slot is still visible in the metrics afterwards.
        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(1, metrics.quarantined(), "the uncertain close quarantined its slot");
        Assert.assertTrue(metrics.degraded(), "the degraded pool state is visible");
        Assert.assertEquals(1, metrics.capacityInUse(),
                "the quarantined slot still consumes capacity after the shutdown");
        Assert.assertEquals(1, poolFactory.liveCount(),
                "the resource that never proved itself closed is still physically live");
        Assert.assertTrue(pool.isClosed(), "the pool itself did finish its shutdown");
        Assert.assertEquals(0, metrics.retiring(), "no retirement is left in flight");
        Assert.assertTrue(pool.closedWithUncertainResources(), "the pool reports the uncertain shutdown");
        Assert.assertTrue(pool.uncertainCloseDetail().contains("quarantined"),
                "the pool explains itself without values: " + pool.uncertainCloseDetail());

        // (4) The context saw the uncertainty even though BoundedPool.close() never threw.
        RepositoryContext closed = firstContext[0];
        Assert.assertTrue(closed.isClosed(), "the previous context is closed");
        Assert.assertTrue(closed.closedWithUncertainResources(),
                "the context reports the uncertain close that BoundedPool.close() swallowed");
        Assert.assertTrue(closed.closeFailures().isEmpty(),
                "no resource threw out of close(), so there is no close failure to report");
        Assert.assertEquals(1, closed.uncertainCloseReports().size(),
                "the pool's own report is carried by the context: " + closed.uncertainCloseReports());
        Assert.assertTrue(closed.uncertainCloseReports().get(0).contains("quarantined"),
                "the context names the quarantine: " + closed.uncertainCloseReports());
        Assert.assertTrue(closed.uncertainCloseDetail().contains("cm-sessions"),
                "the context explains itself in the contract's vocabulary: " + closed.uncertainCloseDetail());

        // (5) The manager refused BEFORE the factory call, in the state and with the diagnostics that say so.
        Assert.assertEquals(RepositoryState.FAILED, manager.state(), "the manager reports FAILED");
        Assert.assertTrue(manager.activeContext().isEmpty(), "nothing is published");
        Assert.assertTrue(manager.activeRepositoryId() == null, "no repository id is published");
        Assert.assertFalse(manager.status().usable(), "there is no usable context after the refused switch");
        Assert.assertTrue(refusal.getMessage().contains("uncertain"),
                "the refusal explains the uncertainty: " + refusal.getMessage());
        Assert.assertTrue(refusal.getMessage().contains("crm"),
                "the refusal names the previous repository: " + refusal.getMessage());
        Assert.assertTrue(refusal.getMessage().contains("quarantined"),
                "the refusal names the pool quarantine: " + refusal.getMessage());
        Assert.assertTrue(manager.diagnostics().stream()
                        .anyMatch(d -> d.contains("uncertain resource shutdown")),
                "the diagnostics record the uncertain shutdown: " + manager.diagnostics());
        Assert.assertTrue(manager.diagnostics().stream().anyMatch(d -> d.contains("quarantined")),
                "the diagnostics name the quarantine: " + manager.diagnostics());
        Assert.assertTrue(manager.lastFailure().orElseThrow().contains("quarantined"),
                "lastFailure explains the refusal: " + manager.lastFailure());

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(),
                "shutdown still works after a refused switch");
    }

    /**
     * Goal 01B (A), second half: the mechanism must not turn every normal pool shutdown into FAILED.
     *
     * <p>This is the counter-regression for the fix above. An implementation that marks a context
     * uncertain merely because it owns a {@link CloseOutcomeAware} resource - instead of asking it -
     * passes the decisive test and fails here.
     */
    public void aCleanPoolShutdownStillAllowsTheSwitchAndReportsNoUncertainty() throws Exception {
        FakePoolFactory poolFactory = new FakePoolFactory();
        BoundedPool<FakeResource> pool = initializedPool(poolFactory, "cm-sessions");

        List<String> factoryCalls = Collections.synchronizedList(new ArrayList<>());
        RepositoryContext[] firstContext = new RepositoryContext[1];
        RepositoryManager manager = new RepositoryManager(profile -> {
            factoryCalls.add(profile.id());
            if (factoryCalls.size() == 1) {
                RepositoryContext context = new RepositoryContext(profile, List.of(pool));
                firstContext[0] = context;
                return context;
            }
            return new RepositoryContext(profile);
        });

        manager.switchTo(profile("crm"));
        manager.switchTo(profile("beta"));

        Assert.assertEquals(List.of("crm", "beta"), List.copyOf(factoryCalls),
                "a clean pool shutdown does not refuse the next context");
        Assert.assertEquals(RepositoryState.ACTIVE, manager.state(), "the switch completed");
        Assert.assertEquals("beta", manager.activeRepositoryId(), "the new repository is active");
        Assert.assertTrue(manager.lastFailure().isEmpty(), "a clean pool shutdown is not a failure");
        Assert.assertTrue(manager.status().usable(), "the new context is usable");

        RepositoryContext closed = firstContext[0];
        Assert.assertTrue(closed.isClosed(), "the previous context is closed");
        Assert.assertFalse(closed.closedWithUncertainResources(),
                "every pooled resource proved itself closed, so the shutdown is certain");
        Assert.assertTrue(closed.closeFailures().isEmpty(), "no close failure was invented");
        Assert.assertTrue(closed.uncertainCloseReports().isEmpty(), "nothing reported an uncertain shutdown");
        Assert.assertEquals("", closed.uncertainCloseDetail(), "a certain close has no detail text");

        PoolMetrics metrics = pool.metrics();
        Assert.assertEquals(0, metrics.quarantined(), "no slot was quarantined");
        Assert.assertEquals(0, metrics.capacityInUse(), "every slot was returned");
        Assert.assertEquals(0, metrics.closeFailures(), "every close succeeded");
        Assert.assertEquals(0, poolFactory.liveCount(), "no physical resource is left alive");
        Assert.assertEquals(poolFactory.created().size(), poolFactory.closed().size(),
                "every resource was closed exactly once");
        Assert.assertFalse(pool.closedWithUncertainResources(), "the pool itself reports a certain shutdown");
        Assert.assertEquals("", pool.uncertainCloseDetail(), "a clean pool has nothing to explain");

        manager.close();
        Assert.assertEquals(RepositoryState.CLOSED, manager.state(), "the manager closes cleanly");
    }

    /**
     * Goal 01B (A): the existing behaviour is unchanged for a resource that cannot report anything - an
     * ordinary {@link AutoCloseable} is still trusted, its message still reaches {@code closeFailures()},
     * and a throwing close still marks the context uncertain.
     */
    public void ordinaryAutoCloseablesKeepTheirExistingSemantics() throws Exception {
        List<String> order = new ArrayList<>();
        AutoCloseable first = () -> order.add("first");
        AutoCloseable failing = () -> {
            order.add("failing");
            throw new IOException("resource refused to close");
        };
        AutoCloseable last = () -> order.add("last");
        RepositoryContext context = new RepositoryContext(profile("crm"), List.of(first, failing, last));

        // Compile-time proof that the context itself speaks the generic contract, like the pool does.
        CloseOutcomeAware contractView = context;
        Assert.assertFalse(contractView.closedWithUncertainResources(), "an open context is not uncertain");
        Assert.assertEquals("", contractView.uncertainCloseDetail(), "an open context explains nothing");
        Assert.assertTrue(context.uncertainCloseReports().isEmpty(), "nothing reported anything yet");

        context.close();
        Assert.assertEquals(List.of("last", "failing", "first"), order,
                "reverse order, and a plain AutoCloseable is handled exactly as before");
        Assert.assertEquals(1, context.closeFailures().size(), "the refusal is still a close failure");
        Assert.assertTrue(context.closeFailures().get(0).contains("resource refused to close"),
                "the failure keeps its message: " + context.closeFailures());
        Assert.assertTrue(contractView.closedWithUncertainResources(), "a throwing close is still uncertain");
        Assert.assertTrue(context.uncertainCloseReports().isEmpty(),
                "a resource that cannot report its outcome contributes no report");
        Assert.assertTrue(contractView.uncertainCloseDetail().contains("did not close"),
                "the contract's detail names the failure: " + contractView.uncertainCloseDetail());
        Assert.assertTrue(contractView.uncertainCloseDetail().contains("resource refused to close"),
                "the detail is the same text as closeFailures(): " + contractView.uncertainCloseDetail());
    }

    /**
     * Goal 01B (A): a close-aware resource whose report itself fails is NOT evidence of a clean shutdown,
     * so it must fail closed - while the other resources are still released.
     */
    public void aCloseAwareResourceWhoseReportCannotBeReadFailsClosed() {
        List<String> order = new ArrayList<>();
        AutoCloseable healthy = () -> order.add("healthy");
        RepositoryContext context = new RepositoryContext(profile("crm"),
                List.of(new UnreadableReport(), healthy));

        context.close();

        Assert.assertEquals(List.of("healthy"), order,
                "the remaining resource is still released when the report fails");
        Assert.assertTrue(context.closedWithUncertainResources(),
                "an unreadable close outcome is not a clean close");
        Assert.assertEquals(1, context.uncertainCloseReports().size(),
                "the unreadable outcome is reported: " + context.uncertainCloseReports());
        Assert.assertTrue(context.uncertainCloseReports().get(0).contains("IllegalStateException"),
                "the report names the failure type: " + context.uncertainCloseReports());
        Assert.assertFalse(context.uncertainCloseReports().get(0).contains("metrics are offline"),
                "the probe's own message is not copied into diagnostics: " + context.uncertainCloseReports());
        Assert.assertTrue(context.closeFailures().isEmpty(),
                "the resource did close, so it is not listed as a close failure");
    }

    /**
     * Goal 01B (A): an {@link Error} thrown by a report must not be swallowed either - it is recorded as
     * uncertainty and then reaches the caller, after every resource has been released.
     */
    public void aFatalReportFailureFailsClosedAndStillReachesTheCaller() {
        List<String> order = new ArrayList<>();
        AutoCloseable healthy = () -> order.add("healthy");
        RepositoryContext context = new RepositoryContext(profile("crm"),
                List.of(new FatalReport(), healthy));

        ResourceCloseError thrown = Assert.assertThrows(ResourceCloseError.class, context::close,
                "an Error from a resource's report must not be swallowed");
        Assert.assertEquals("close report failed fatally", thrown.getMessage(), "the same Error is rethrown");
        Assert.assertEquals(List.of("healthy"), order,
                "the remaining resource was released before the Error surfaced");
        Assert.assertTrue(context.closedWithUncertainResources(),
                "the fatal report is not evidence of a clean shutdown");
        Assert.assertEquals(1, context.uncertainCloseReports().size(),
                "the fatal report is still recorded: " + context.uncertainCloseReports());
        Assert.assertTrue(context.uncertainCloseReports().get(0).contains("ResourceCloseError"),
                "the report names the fatal type: " + context.uncertainCloseReports());
    }

    /**
     * A close-aware resource whose own report fails: it cannot answer the question, so it cannot be
     * trusted. The message of the failure is deliberately not part of the assertion contract.
     */
    private static final class UnreadableReport implements CloseOutcomeAware {

        @Override
        public void close() {
            // The resource itself closes fine; only its report is broken.
        }

        @Override
        public boolean closedWithUncertainResources() {
            throw new IllegalStateException("metrics are offline");
        }

        @Override
        public String uncertainCloseDetail() {
            return "unreachable";
        }
    }

    /** A close-aware resource whose report fails with an {@link Error}, which must not be swallowed. */
    private static final class FatalReport implements CloseOutcomeAware {

        @Override
        public void close() {
        }

        @Override
        public boolean closedWithUncertainResources() {
            throw new ResourceCloseError("close report failed fatally");
        }
    }
}
