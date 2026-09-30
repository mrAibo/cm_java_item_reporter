package com.mraibo.cminsight.report;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * One report artifact that exists below {@code reports.dir}.
 *
 * <p>What a route needs and nothing more: the opaque identity, the format (which carries the content type),
 * the file name a download may be labelled with, the confined path the writer resolved, the size and the
 * instant it was written. The file name is never derived from a request parameter - it is built inside
 * {@link ReportService} from a validated {@link ReportId} and a {@link ReportFormat} constant, and that is the
 * only place in this package where a report file name exists at all.
 *
 * @param id        the generated identity
 * @param format    the format the bytes are in
 * @param fileName  the artifact's leaf name, always {@code report-<id>.<extension>}
 * @param path      the confined, absolute, normalised path below the configured reports directory
 * @param sizeBytes the size of the written artifact
 * @param writtenAt when it was written
 */
public record GeneratedReport(ReportId id, ReportFormat format, String fileName, Path path, long sizeBytes,
                              Instant writtenAt) {

    public GeneratedReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
        fileName = fileName == null ? "" : fileName.trim();
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("a generated report must carry its file name");
        }
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(writtenAt, "writtenAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("a report size must not be negative");
        }
    }

    /**
     * The response content type for this artifact.
     *
     * @return the format's content type, never {@code null}
     */
    public String contentType() {
        return format.contentType();
    }

    /**
     * A short, value-free description for diagnostics.
     *
     * @return a one-line description carrying no path and no content
     */
    public String describe() {
        return "report[" + id + ", " + format.token() + ", " + sizeBytes + " byte(s)]";
    }
}
