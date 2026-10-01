package com.mraibo.cminsight.test;

import com.mraibo.cminsight.history.HistoryDetail;
import com.mraibo.cminsight.history.HistoryId;
import com.mraibo.cminsight.history.HistoryItemType;
import com.mraibo.cminsight.history.HistoryMetric;
import com.mraibo.cminsight.history.HistorySummary;
import com.mraibo.cminsight.report.CsvReportRenderer;
import com.mraibo.cminsight.report.GeneratedReport;
import com.mraibo.cminsight.report.ReportException;
import com.mraibo.cminsight.report.ReportFormat;
import com.mraibo.cminsight.report.ReportId;
import com.mraibo.cminsight.report.ReportModel;
import com.mraibo.cminsight.report.ReportService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Goal 04 sections 6 and 12 "Reports": output confinement, completion semantics and the zero-I/O guarantee.
 *
 * <h2>The three properties, and how each is made observable</h2>
 *
 * <ul>
 *   <li><strong>No HTTP parameter becomes a filesystem path.</strong> Asserted from both directions: every
 *       path-shaped identifier fails to parse, and no public operation accepts a file name, a relative path
 *       or a directory at all - so there is no parameter that COULD reach the filesystem.</li>
 *   <li><strong>An interrupted export is never presented as complete.</strong> The measuring device is the
 *       directory itself: after every failure mode it must contain no report and no temporary fragment.</li>
 *   <li><strong>A historical report reads no database.</strong> The measuring device is a registered
 *       {@link FakeJdbc} whose every driver call is counted, plus a structural closure check that the report
 *       model cannot even hold a live CM/JDBC handle. Both are needed: a counter proves this run, the
 *       closure proves there is no field a later change could route I/O through.</li>
 * </ul>
 */
public class ReportOutputConfinementTest {

    private static final Instant GENERATED_AT = Instant.parse("2024-06-16T08:30:00Z");

    /** Every shape an attacker would try to make a report id into a path. */
    private static final List<String> TRAVERSAL_IDS = List.of(
            "..", "../", "..\\", "../../etc/passwd", "..%2f..%2fetc%2fpasswd", "%2e%2e%2f",
            "/etc/passwd", "\\\\server\\share\\x", "C:\\Windows\\win.ini", "report-1.csv",
            "a/../../b", "h1/../../x", "h1.csv", "h1.txt", ".", "./", "~/x", "a b", "a?b", "a#b",
            "a%00b", "\u0000", "a\nb", "=<script>", "H1", "-h1", "_h1",
            "0".repeat(80));

    // ------------------------------------------------------------------ ids are not paths

    /** No traversal, separator, extension or oversize shape is a valid report identity. */
    public void everyPathShapedReportIdIsRefused() {
        for (String hostile : TRAVERSAL_IDS) {
            Optional<ReportId> parsed = ReportId.parse(hostile);
            Assert.assertTrue(parsed.isEmpty(),
                    "the report id '" + printable(hostile) + "' must not parse: a valid identity is an opaque"
                            + " token, and anything a filesystem could read as a path must be refused here"
                            + " rather than sanitised later");
        }
        Assert.assertTrue(ReportId.parse(null).isEmpty(), "a null id is not an identity");
        Assert.assertTrue(ReportId.parse("").isEmpty(), "a blank id is not an identity");
    }

    /** A generated id is opaque, and its report lands directly below the configured directory. */
    public void aGeneratedReportLandsDirectlyBelowTheConfiguredDirectory() throws Exception {
        Path base = TestSupport.newTempDir("reports-confine-");
        try {
            ReportService service = new ReportService(base);
            ReportModel model = sampleModel("ItemType1");
            GeneratedReport generated = service.generate(model, ReportFormat.CSV);

            Assert.assertEquals(base.toAbsolutePath().normalize(),
                    generated.path().toAbsolutePath().normalize().getParent(),
                    "a report must be written directly under reports.dir: a subdirectory would be a second"
                            + " containment rule someone has to remember");
            Assert.assertTrue(Files.isRegularFile(generated.path()), "and must really exist");
            Assert.assertTrue(generated.fileName().startsWith("report-"),
                    "the produced file name must be generated from the report id, but was "
                            + generated.fileName());
            Assert.assertTrue(generated.fileName().endsWith(".csv"),
                    "and carry the format's extension: " + generated.fileName());
            Assert.assertTrue(generated.id().value().matches("[a-z][a-z0-9]{15,31}"),
                    "the id must be an opaque token of the documented shape, but was "
                            + generated.id().value());
            Assert.assertTrue(generated.sizeBytes() > 0, "and the report must not be empty");

            // A second report must not overwrite the first: ids are unique per generation.
            GeneratedReport second = service.generate(sampleModel("ItemType2"), ReportFormat.CSV);
            Assert.assertFalse(second.path().equals(generated.path()),
                    "two generations must not collide on one file");
            Assert.assertTrue(Files.isRegularFile(generated.path()),
                    "and the earlier report must still be there");
        } finally {
            TestSupport.deleteRecursively(base);
        }
    }

    /**
     * No public report operation accepts a file name, a relative path or a directory.
     *
     * <p>This is the structural half of "no HTTP parameter becomes a filesystem path": with no parameter of
     * type {@code String} or {@code Path} on any operation, a route cannot pass one even by mistake. The
     * configured directory is a constructor input, which is application configuration rather than request
     * data, so it is deliberately not caught by this rule.
     */
    public void noPublicReportOperationAcceptsAFileNameOrPath() {
        List<Method> offenders = new ArrayList<>();
        for (Method method : ReportService.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter == String.class || parameter == Path.class
                        || parameter == java.io.File.class) {
                    offenders.add(method);
                    break;
                }
            }
        }
        Assert.assertTrue(offenders.isEmpty(),
                "no report operation may accept a caller-supplied file name or path, or an HTTP parameter"
                        + " could become one. Offending methods: " + offenders);
    }

    // ------------------------------------------------------------------ completion semantics

    /** Every failure mode leaves the directory exactly as it was: no report, no temporary fragment. */
    public void aFailedExportLeavesNoCompletedArtifact() throws Exception {
        Path base = TestSupport.newTempDir("reports-failure-");
        try {
            ReportService service = new ReportService(base);

            // 1. Content beyond the documented size bound is refused before any file exists.
            ReportModel oversized = modelWithName("x".repeat(5 * 1024 * 1024));
            ReportException tooLarge = Assert.assertThrows(ReportException.class,
                    () -> service.generate(oversized, ReportFormat.HTML),
                    "content above the documented bound must be refused rather than written");
            Assert.assertTrue(tooLarge.reason().name().contains("TOO_LARGE"),
                    "and the reason must name the size bound: " + tooLarge.reason());
            assertDirectoryContainsOnly(base);

            // 2. An unusable output directory is reported, and nothing is created next to it.
            Path blockedParent = TestSupport.newTempDir("reports-blocked-");
            Path regularFile = blockedParent.resolve("not-a-directory");
            Files.writeString(regularFile, "x");
            try {
                ReportService unusable = new ReportService(regularFile.resolve("nested"));
                ReportException refused = Assert.assertThrows(ReportException.class,
                        () -> unusable.generate(sampleModel("ItemType1"), ReportFormat.CSV),
                        "a reports directory that cannot be created must be refused, not guessed at");
                Assert.assertTrue(refused.reason().name().contains("OUTPUT_DIRECTORY"),
                        "and the reason must name the output directory: " + refused.reason());
                Assert.assertTrue(Files.isRegularFile(regularFile),
                        "and nothing may have replaced the file that was in the way");
            } finally {
                TestSupport.deleteRecursively(blockedParent);
            }

            // 3. A failure after a SUCCESS must not damage the successful report or add a fragment.
            GeneratedReport good = service.generate(sampleModel("ItemType1"), ReportFormat.CSV);
            Assert.assertThrows(ReportException.class,
                    () -> service.generate(modelWithName("y".repeat(5 * 1024 * 1024)), ReportFormat.CSV),
                    "the oversized export must still be refused");
            assertDirectoryContainsOnly(base, good.fileName());
            Assert.assertTrue(Files.isRegularFile(good.path()),
                    "the earlier completed report must still be intact and downloadable");
        } finally {
            TestSupport.deleteRecursively(base);
        }
    }

    /** A guard against the three ways an interrupted write shows up: a report, a fragment, or a temp name. */
    private static void assertDirectoryContainsOnly(Path base, String... expected) throws Exception {
        if (!Files.isDirectory(base)) {
            Assert.assertEquals(0, expected.length,
                    "a directory that was never created cannot contain the expected artifacts "
                            + java.util.Arrays.toString(expected));
            return;
        }
        try (Stream<Path> entries = Files.list(base)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            List<String> allowed = new ArrayList<>(List.of(expected));
            allowed.sort(String::compareTo);
            Assert.assertEquals(allowed, names,
                    "the reports directory must contain exactly the completed artifacts and nothing else: a"
                            + " leftover temporary or partially written file would be an export presented as"
                            + " an export that never finished");
            for (String name : names) {
                Assert.assertFalse(name.contains(".part") || name.contains(".tmp")
                                || name.startsWith(".report"),
                        "no temporary fragment may be left behind: " + name);
            }
        }
    }

    // ------------------------------------------------------------------ zero database I/O

    /**
     * A report built from a stored history snapshot performs ZERO CM/JDBC calls.
     *
     * <p>Goal 04 section 6: a missing value is rendered as unavailable rather than filled in by hidden I/O.
     * The counter is a registered {@link FakeJdbc} driver - the only database this JVM can reach - and the
     * suite then makes one deliberate connection to prove the counter can move at all, so a zero cannot be
     * the counter being dead.
     */
    public void aStoredHistoryReportPerformsZeroCmOrJdbcCalls() throws Exception {
        Path base = TestSupport.newTempDir("reports-zeroio-");
        FakeJdbc.loadVendorDriverClass(FakeJdbc.DB2_DRIVER_CLASS);
        try (FakeJdbc fake = FakeJdbc.register("jdbc:db2:")) {
            ReportService service = new ReportService(base);
            ReportModel model = sampleModel("ItemType1");

            int rendered = 0;
            for (ReportFormat format : ReportFormat.availableFormats()) {
                GeneratedReport report = service.generate(model, format);
                Assert.assertTrue(Files.isRegularFile(report.path()),
                        "the " + format + " report must really have been written");
                rendered++;
            }
            Assert.assertTrue(rendered >= 2, "HTML and CSV are mandatory, so at least two formats render");

            Assert.assertEquals(0, fake.driverMethodCalls(),
                    "rendering and writing a report from a STORED snapshot must perform zero JDBC calls, but"
                            + " the driver saw " + fake.driverMethodCalls() + " call(s)");
            Assert.assertEquals(0, fake.connectRequests(),
                    "and must not request a connection: the report model has to be immutable input, not a"
                            + " reason to go and read something");
            Assert.assertEquals(0, fake.metadataCalls(), "and must not read database metadata");
            Assert.assertTrue(fake.calls().isEmpty(),
                    "and must execute no SQL at all: " + fake.executedSql());

            // The control: the counter is live, so the zero above is a fact and not an inert instrument.
            try (java.sql.Connection ignored = java.sql.DriverManager.getConnection(fake.url(), "x", "y")) {
                Assert.assertTrue(fake.driverMethodCalls() > 0,
                        "the driver counter must move when a connection is really opened, otherwise the"
                                + " zero asserted above would prove nothing");
            }
        } finally {
            TestSupport.deleteRecursively(base);
        }
    }

    /**
     * The report model cannot hold a live CM or JDBC handle, transitively.
     *
     * <p>Part of {@link #aStoredHistoryReportPerformsZeroCmOrJdbcCalls()}: a counter measures this run,
     * while this closure removes the possibility - there is no field through which a later change could
     * route a query without also changing a type this test inspects.
     */
    public void theReportModelCannotHoldALiveCmOrJdbcHandle() {
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(ReportModel.class);
        pending.add(CsvReportRenderer.class);
        List<String> offenders = new ArrayList<>();
        List<Class<?>> visited = new ArrayList<>();

        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (visited.contains(type) || visited.size() > 60) {
                continue;
            }
            visited.add(type);
            for (Class<?> component : componentsOf(type)) {
                String name = component.getName();
                if (name.startsWith("java.sql.") || name.startsWith("javax.sql.")
                        || name.startsWith("com.ibm.")
                        || name.contains("JdbcSession") || name.contains("BoundedPool")
                        || name.contains("Lease")) {
                    offenders.add(type.getSimpleName() + " -> " + name);
                    continue;
                }
                if (name.startsWith("com.mraibo.cminsight.") && !component.isEnum()) {
                    pending.add(component);
                }
            }
        }
        Assert.assertTrue(offenders.isEmpty(),
                "a report model must be pure values, so no live connection or session can travel with it: "
                        + offenders);
        Assert.assertTrue(visited.size() >= 5,
                "the closure check must actually have walked the model, but it visited only "
                        + visited.size() + " type(s)");
    }

    /** The HTTP-facing download value cannot carry a filesystem path that a caller might reopen later. */
    public void downloadTransportCarriesNoFilesystemPath() {
        List<Class<?>> components = new ArrayList<>();
        for (RecordComponent component : ReportService.DownloadedReport.class.getRecordComponents()) {
            components.add(component.getType());
        }
        Assert.assertFalse(components.contains(Path.class),
                "the download transport must carry bytes and metadata only; a Path would recreate check/use authority");
        Assert.assertTrue(components.contains(byte[].class),
                "the confinement owner must hand the web layer already-read bytes");
    }
    /**
     * The download read is the confinement operation: a stale description must never authorise a later open.
     *
     * <p>The symlink part is mandatory on Linux/CI. Windows developer filesystems can deny symbolic-link
     * creation to an unprivileged process; in that case the non-symlink assertions still run and CI supplies
     * the primitive-specific proof.
     */
    public void downloadOwnsTheNoFollowOpenAndOpenedHandleSizeBound() throws Exception {
        // Use the platform temp filesystem rather than the repository checkout. On Linux/CI this gives the
        // regression a filesystem with real symlink semantics even when the checkout itself is a mounted drive.
        Path base = Files.createTempDirectory("reports-download-");
        Path outsideDir = Files.createTempDirectory("reports-outside-");
        try {
            ReportService service = new ReportService(base);

            GeneratedReport normal = service.generate(sampleModel("normal"), ReportFormat.CSV);
            byte[] expected = Files.readAllBytes(normal.path());
            ReportService.DownloadedReport opened = service.readForDownload(normal.id(), normal.format())
                    .orElseThrow(() -> new AssertionError("a normal completed artifact must be downloadable"));
            Assert.assertTrue(java.util.Arrays.equals(expected, opened.bytes()),
                    "the confined read must return exactly the normal artifact bytes");

            GeneratedReport oversized = service.generate(sampleModel("oversized"), ReportFormat.CSV);
            Files.write(oversized.path(), new byte[(int) ReportService.MAX_REPORT_BYTES + 1]);
            ReportException tooLarge = Assert.assertThrows(ReportException.class,
                    () -> service.readForDownload(oversized.id(), oversized.format()),
                    "the download bound must be enforced by the component that owns the opened handle");
            Assert.assertEquals(ReportException.Reason.CONTENT_TOO_LARGE, tooLarge.reason(),
                    "an oversized opened artifact must be refused as CONTENT_TOO_LARGE");

            GeneratedReport raced = service.generate(sampleModel("race"), ReportFormat.CSV);
            GeneratedReport discovered = service.find(raced.id(), raced.format())
                    .orElseThrow(() -> new AssertionError("the control needs a metadata result before the swap"));
            Path outside = outsideDir.resolve("outside-secret.txt");
            byte[] secret = "OUTSIDE_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(outside, secret);

            Files.delete(raced.path());
            try {
                Files.createSymbolicLink(raced.path(), outside.toAbsolutePath());
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                    throw new AssertionError("the Linux-capable symlink regression could not create its link",
                            unavailable);
                }
                return;
            }

            Assert.assertTrue(service.find(raced.id(), raced.format()).isEmpty(),
                    "a report whose current final component is a symlink must not even be described as an artifact");

            // Mutation control: this is the old check/use design. The metadata Path was safe when find()
            // returned, but a plain later open follows the replacement and exposes the outside target.
            byte[] leakedByPlainOpen = Files.readAllBytes(discovered.path());
            Assert.assertTrue(java.util.Arrays.equals(secret, leakedByPlainOpen),
                    "the control must prove a normal following open WOULD expose the outside target");

            Optional<ReportService.DownloadedReport> secured;
            try {
                secured = service.readForDownload(raced.id(), raced.format());
            } catch (ReportException refused) {
                Assert.assertEquals(ReportException.Reason.OUTPUT_UNREADABLE, refused.reason(),
                        "a raced symlink may be reported as unreadable, but must never be followed");
                secured = Optional.empty();
            }
            Assert.assertTrue(secured.isEmpty(),
                    "a symlink leaf, including one swapped in after metadata discovery, must be refused");
        } finally {
            TestSupport.deleteRecursively(base);
            TestSupport.deleteRecursively(outsideDir);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static List<Class<?>> componentsOf(Class<?> type) {
        List<Class<?>> components = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                components.add(component.getType());
            }
            return components;
        }
        for (Field field : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                components.add(field.getType());
            }
        }
        return components;
    }

    private static ReportModel sampleModel(String itemTypeName) {
        return modelWithName(itemTypeName);
    }

    private static ReportModel modelWithName(String itemTypeName) {
        HistoryItemType row = new HistoryItemType(1, itemTypeName, "SAP", "", HistoryItemType.Status.OK,
                HistoryMetric.available(10L), HistoryMetric.available(1L), HistoryMetric.available(2L),
                HistoryMetric.available(3L), HistoryMetric.available(4L), 5L, "");
        HistorySummary summary = new HistorySummary(new HistoryId("h1"), "alpha", "Repository alpha", "DB2",
                Instant.parse("2024-06-16T08:00:00Z"), Instant.parse("2024-06-16T07:59:00Z"), 60_000L,
                LocalDate.of(2024, 6, 16), 1L, 1, 0, true, 10L);
        return ReportModel.fromHistory(new HistoryDetail(summary, List.of(row), ""), GENERATED_AT);
    }

    private static String printable(String value) {
        return "'" + value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "'";
    }

}

