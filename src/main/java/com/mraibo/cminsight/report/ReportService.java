package com.mraibo.cminsight.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The only authority that turns a rendered report into a file, and the only place a report path is built.
 *
 * <h2>The output rule, and how it is enforced</h2>
 *
 * <p>Goal 04 section 6: all reports are written below {@code reports.dir} using generated opaque ids and file
 * names, and <strong>no HTTP parameter ever becomes a filesystem path</strong>. Three mechanisms make that
 * true rather than intended:
 *
 * <ol>
 *   <li><strong>There is no parameter to pass.</strong> {@link #generate(ReportModel, ReportFormat)} takes an
 *       immutable model and a format constant. No method in this class accepts a name, a suffix, a
 *       subdirectory or a path, so a route has nothing it could forward even by mistake.</li>
 *   <li><strong>The name is generated here.</strong> It is always {@code report-<id>.<extension>}, where the id
 *       is a fresh {@link ReportId} (a bounded lowercase alphanumeric token) and the extension comes from the
 *       format enum. Nothing else in the application can spell a report file name.</li>
 *   <li><strong>The resolved path is checked, not the string.</strong> Every candidate is resolved against the
 *       normalised base and then verified:</li>
 * </ol>
 *
 * <pre>{@code
 * Path candidate = base.resolve(fileName).normalize();
 * if (!candidate.startsWith(base) || candidate.getParent() == null || !candidate.getParent().equals(base)) {
 *     throw new ReportException(ReportException.Reason.PATH_OUTSIDE_OUTPUT_DIRECTORY);
 * }
 * }</pre>
 *
 * <p>where {@code base} is {@code reportsDir.toAbsolutePath().normalize()}, computed once in the constructor.
 * Requiring the file to be a <em>direct child</em> of the base is deliberately stronger than a prefix test
 * alone: a report is never in a subdirectory, so a path that reaches one is refused even if it is still
 * inside the tree. Metadata lookup still refuses a symbolic-link leaf with
 * {@code LinkOption.NOFOLLOW_LINKS}, but metadata is never download authority. {@link #readForDownload(ReportId,
 * ReportFormat)} performs the decisive open itself with {@code READ + NOFOLLOW_LINKS}; the bytes are then read
 * from that same already-open handle while the size bound is enforced. Replacing a previously described report
 * with a symlink therefore cannot redirect the later read to the link target. A symbolic link planted in the
 * output directory is neither listed nor downloadable, and a Linux-capable regression proves the old plain-open
 * control would expose the target while this path refuses it.
 *
 * <h2>An interrupted export is never presented as complete</h2>
 *
 * <p>The bytes are rendered in memory first, then written to a temporary file created <em>inside the same
 * directory</em> (so the rename cannot cross a filesystem boundary), and only then moved into place with
 * {@link StandardCopyOption#ATOMIC_MOVE}, falling back to a single
 * {@link StandardCopyOption#REPLACE_EXISTING} move only when the provider cannot promise atomicity. The
 * temporary file is deleted on every failure path, and its {@code .part} suffix can never match the report
 * name pattern - so an export that died half way is invisible to {@link #list(int)} and unavailable to
 * {@link #find(ReportId, ReportFormat)} instead of being offered to an operator as a report.
 *
 * <h2>Resource posture</h2>
 *
 * <p>Rendering happens before any file is opened, and the immutable model holds no CM or JDBC handle, so this
 * class never holds a lease while writing. A single artifact is bounded by {@link #MAX_REPORT_BYTES}; a list
 * request is bounded by {@link #MAX_LIST_LIMIT}; every failure is a {@link ReportException} whose message is a
 * constant, so no path, URL, credential or driver message can reach a response body through it.
 */
public final class ReportService {

    /** The largest single artifact this application will write: a bound, not a target. */
    public static final long MAX_REPORT_BYTES = 4L * 1024L * 1024L;

    /** The largest report list a caller may ask for in one request. */
    public static final int MAX_LIST_LIMIT = 200;

    /**
     * One download body after confinement has performed the decisive open. It deliberately carries no
     * filesystem path: a web caller can label and send these bytes, but cannot reopen a checked name later.
     */
    public record DownloadedReport(ReportId id, ReportFormat format, String fileName, byte[] bytes) {
        public DownloadedReport {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(format, "format");
            fileName = Objects.requireNonNull(fileName, "fileName");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Every artifact name starts with this, so a name in the output directory is either ours or ignored. */
    private static final String FILE_PREFIX = "report-";

    /** The suffix of an in-progress export. It can never collide with a report name. */
    private static final String TEMPORARY_SUFFIX = ".part";

    /** The resolved, normalised output directory every path is checked against. */
    private final Path base;

    private final Clock clock;

    /** Uses the system clock. */
    public ReportService(Path reportsDir) {
        this(reportsDir, Clock.systemUTC());
    }

    /**
     * The same service with an explicit clock, for a test that needs a deterministic identity prefix or an
     * operator who wants report timestamps from a trusted source.
     */
    public ReportService(Path reportsDir, Clock clock) {
        Objects.requireNonNull(reportsDir, "reportsDir");
        this.base = reportsDir.toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The absolute, normalised output directory, for diagnostics. Never a secret: it is a local directory. */
    public Path directory() {
        return base;
    }

    /** True when the output directory exists right now. A cheap local stat; it opens nothing and writes nothing. */
    public boolean directoryPresent() {
        return Files.isDirectory(base);
    }

    /**
     * Renders one report and writes it below the output directory.
     *
     * <p>The order is the contract: render in memory, refuse an oversized artifact, then create the directory,
     * write the temporary file and move it into place. A failure at any point before the move leaves no
     * artifact at all, and the returned value is the only signal that one exists.
     *
     * @throws ReportException with a fixed, value-free reason for every failure
     */
    public GeneratedReport generate(ReportModel model, ReportFormat format) {
        Objects.requireNonNull(model, "model");
        ReportFormat target = Objects.requireNonNull(format, "format");
        if (!target.available()) {
            throw new ReportException(ReportException.Reason.FORMAT_UNAVAILABLE);
        }
        byte[] content = ReportRenderer.forFormat(target).render(model);
        if (content.length > MAX_REPORT_BYTES) {
            throw new ReportException(ReportException.Reason.CONTENT_TOO_LARGE);
        }

        Instant generatedAt = clock.instant();
        ReportId id = ReportId.generate(generatedAt);
        String fileName = fileNameFor(id, target);
        Path artifact = confined(fileName);

        ensureDirectory();
        writeTemporaryThenMove(artifact, fileName, content);
        return new GeneratedReport(id, target, fileName, artifact, content.length, generatedAt);
    }

    /**
     * One stored artifact, for a download.
     *
     * <p>An unknown identity is {@link Optional#empty()} rather than an exception: "there is no such report" is
     * an ordinary answer for a route. A file that exists but cannot be read is a failure, because the operator
     * needs to know the difference between a missing artifact and an unreadable one.
     */
    public Optional<GeneratedReport> find(ReportId id, ReportFormat format) {
        Objects.requireNonNull(id, "id");
        ReportFormat target = Objects.requireNonNull(format, "format");
        String fileName = fileNameFor(id, target);
        Path candidate = confined(fileName);
        if (!Files.isDirectory(base) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path realBase = base.toRealPath();
            Path realArtifact = candidate.toRealPath();
            if (!realArtifact.startsWith(realBase) || !realBase.equals(realArtifact.getParent())) {
                // Metadata lookup resolves the path so a planted link is not described as one of our files.
                // This check is deliberately NOT download authority: a later replacement can still happen.
                // readForDownload() closes that later window by opening the final component with NOFOLLOW_LINKS.
                throw new ReportException(ReportException.Reason.PATH_OUTSIDE_OUTPUT_DIRECTORY);
            }
            return Optional.of(new GeneratedReport(id, target, fileName, realArtifact,
                    Files.size(realArtifact), Files.getLastModifiedTime(realArtifact).toInstant()));
        } catch (IOException unreadable) {
            throw new ReportException(ReportException.Reason.OUTPUT_UNREADABLE);
        }
    }

    /**
     * Opens and reads one report for HTTP download under the same confinement authority that owns the path.
     *
     * <p>The final component is opened with {@link LinkOption#NOFOLLOW_LINKS}; the returned bytes come from
     * that exact already-open handle, never from a path that was checked earlier and reopened later. The size
     * bound is enforced against the open channel and again while bytes are consumed, so a growing artifact
     * cannot outrun an earlier attribute check.
     */
    public Optional<DownloadedReport> readForDownload(ReportId id, ReportFormat format) {
        Objects.requireNonNull(id, "id");
        ReportFormat target = Objects.requireNonNull(format, "format");
        String fileName = fileNameFor(id, target);
        Path candidate = confined(fileName);

        // Cheap rejection only. This is NOT the security decision: the decisive operation is the
        // NOFOLLOW_LINKS open below, which cannot be redirected by replacing this leaf with a symlink.
        if (!Files.isDirectory(base) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }

        try (SeekableByteChannel channel = Files.newByteChannel(
                candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long openedSize = channel.size();
            if (openedSize > MAX_REPORT_BYTES) {
                throw new ReportException(ReportException.Reason.CONTENT_TOO_LARGE);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(0L, openedSize), MAX_REPORT_BYTES));
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
            long total = 0L;
            while (true) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    buffer.clear();
                    continue;
                }
                total += read;
                if (total > MAX_REPORT_BYTES) {
                    throw new ReportException(ReportException.Reason.CONTENT_TOO_LARGE);
                }
                out.write(buffer.array(), 0, read);
                buffer.clear();
            }
            return Optional.of(new DownloadedReport(id, target, fileName, out.toByteArray()));
        } catch (NoSuchFileException vanished) {
            return Optional.empty();
        } catch (IOException | SecurityException | UnsupportedOperationException unreadable) {
            throw new ReportException(ReportException.Reason.OUTPUT_UNREADABLE);
        }
    }

    /**
     * The generated artifacts, newest first, bounded by {@code limit}.
     *
     * <p>Ordering is by write instant and then by name descending, so two artifacts written in the same
     * millisecond still come back in one deterministic order and a paged UI cannot skip or repeat a row. A file
     * that does not match the generated name shape - a stray {@code .part}, a symbolic link, an operator's own
     * document - is ignored rather than listed or deleted: this directory is the application's, but ignoring is
     * the only safe behaviour for something this application did not write.
     */
    public List<GeneratedReport> list(int limit) {
        if (limit <= 0 || limit > MAX_LIST_LIMIT) {
            throw new ReportException(ReportException.Reason.INVALID_LIMIT);
        }
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<GeneratedReport> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(base)) {
            for (Path entry : entries.toList()) {
                GeneratedReport report = describe(entry);
                if (report != null) {
                    found.add(report);
                }
            }
        } catch (IOException unreadable) {
            // An output directory that cannot be listed has no reports to offer; the route answers with an
            // empty list rather than failing a screen that has nothing wrong with it.
            return List.of();
        }
        found.sort(Comparator.comparing(GeneratedReport::writtenAt).reversed()
                .thenComparing(GeneratedReport::fileName, Comparator.reverseOrder()));
        return List.copyOf(found.subList(0, Math.min(limit, found.size())));
    }

    private GeneratedReport describe(Path entry) {
        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String name = entry.getFileName().toString();
        if (!name.startsWith(FILE_PREFIX)) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= FILE_PREFIX.length() || dot == name.length() - 1) {
            return null;
        }
        Optional<ReportId> id = ReportId.parse(name.substring(FILE_PREFIX.length(), dot));
        String extension = name.substring(dot + 1);
        Optional<ReportFormat> format = ReportFormat.parse(extension)
                .filter(candidate -> candidate.extension().equals(extension));
        if (id.isEmpty() || format.isEmpty()) {
            return null;
        }
        try {
            return new GeneratedReport(id.get(), format.get(), name, entry, Files.size(entry),
                    Files.getLastModifiedTime(entry).toInstant());
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String fileNameFor(ReportId id, ReportFormat format) {
        return FILE_PREFIX + id.value() + "." + format.extension();
    }

    private Path confined(String fileName) {
        Path candidate = base.resolve(fileName).normalize();
        if (!candidate.startsWith(base) || candidate.getParent() == null
                || !candidate.getParent().equals(base)) {
            throw new ReportException(ReportException.Reason.PATH_OUTSIDE_OUTPUT_DIRECTORY);
        }
        return candidate;
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(base);
        } catch (IOException | SecurityException | UnsupportedOperationException unusable) {
            // Parents are created rather than assumed, and a failure is a capability answer with a fixed
            // reason - never a crash, and never a message that could quote a local path or a driver string.
            throw new ReportException(ReportException.Reason.OUTPUT_DIRECTORY_UNUSABLE);
        }
        if (!Files.isDirectory(base)) {
            throw new ReportException(ReportException.Reason.OUTPUT_DIRECTORY_UNUSABLE);
        }
    }

    private void writeTemporaryThenMove(Path artifact, String fileName, byte[] content) {
        Path temporary = null;
        try {
            // Created INSIDE the output directory: the later move is a same-filesystem rename, which is the
            // only form of move that can be atomic.
            temporary = Files.createTempFile(base, fileName + "-", TEMPORARY_SUFFIX);
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveIntoPlace(temporary, artifact);
            temporary = null;
        } catch (IOException failed) {
            throw new ReportException(ReportException.Reason.WRITE_FAILED);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    private static void moveIntoPlace(Path temporary, Path artifact) throws IOException {
        try {
            Files.move(temporary, artifact, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary, artifact, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Nothing useful can be done, and nothing unsafe happens if it stays: a .part name is not a report
            // name, so a leftover is never listed, downloadable or presented as a complete artifact.
        }
    }
}
