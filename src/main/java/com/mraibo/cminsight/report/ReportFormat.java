package com.mraibo.cminsight.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The report formats this build can produce, with the capability facts the API and the UI publish.
 *
 * <h2>Capability is a property of the format, not of a response</h2>
 *
 * <p>Goal 04 requires XLSX to be either a real workbook or <em>explicitly</em> unavailable, and never a CSV
 * renamed {@code .xlsx}. {@link #XLSX} here is real: {@link XlsxReportRenderer} writes a minimal OOXML
 * workbook with the JDK's own zip and XML support and no third-party library. {@link #available()} is
 * therefore {@code true} for every constant in this build, and it exists so that the UI can render
 * "unavailable" from the same single source if that ever changes - rather than omitting the format
 * silently, which the goal forbids.
 *
 * <p>{@link #detail()} is a fixed sentence with no dynamic content: it is published verbatim in a
 * diagnostics or capabilities response, so it must not be able to carry anything but the truth about this
 * build's own implementation.
 */
public enum ReportFormat {

    /** A standalone escaped HTML document with inline CSS only: no scripts and no external assets. */
    HTML("text/html; charset=utf-8", true,
            "standalone escaped HTML with inline CSS only; no scripts, no CDN and no external assets"),

    /** RFC 4180 CSV with spreadsheet formula neutralisation; measured metrics stay numeric. */
    CSV("text/csv; charset=utf-8", true,
            "RFC 4180 CSV with formula neutralisation in text fields; measured metrics remain numeric"),

    /**
     * A real minimal OOXML workbook, written with {@code java.util.zip} only.
     *
     * <p>Text is an inline string cell, a measured metric is a numeric cell, and no cell is ever a formula,
     * a macro or an external relationship - so a hostile ItemType name cannot become executable content.
     */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", true,
            "real minimal OOXML workbook written with the JDK zip support; text is text, metrics are"
                    + " numbers, and there are no formulas, macros or external relationships");

    private final String contentType;
    private final boolean available;
    private final String detail;

    ReportFormat(String contentType, boolean available, String detail) {
        this.contentType = contentType;
        this.available = available;
        this.detail = detail;
    }

    /** The lowercase token used in a URL query, a filename extension and an API value. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The filename extension, without the dot. Equal to {@link #token()} by construction. */
    public String extension() {
        return token();
    }

    /** The response content type for a served artifact. */
    public String contentType() {
        return contentType;
    }

    /** True when this build can actually produce the format. */
    public boolean available() {
        return available;
    }

    /** A fixed, value-free sentence describing what the produced artifact is. */
    public String detail() {
        return detail;
    }

    /**
     * Parses a caller-supplied format token.
     *
     * <p>Case-insensitive but otherwise exact: an unknown token yields empty, so a route answers
     * {@code invalid_format} rather than guessing a default. There is deliberately no fallback, because a
     * request that asked for {@code .xlsx} and silently received CSV is the exact defect the goal names.
     */
    public static Optional<ReportFormat> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String candidate = token.trim().toLowerCase(Locale.ROOT);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        for (ReportFormat format : values()) {
            if (format.token().equals(candidate)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The formats this build can produce. */
    public static List<ReportFormat> availableFormats() {
        return select(true);
    }

    /** The formats this build cannot produce; empty when every format is real. */
    public static List<ReportFormat> unavailableFormats() {
        return select(false);
    }

    private static List<ReportFormat> select(boolean wanted) {
        List<ReportFormat> selected = new ArrayList<>(values().length);
        for (ReportFormat format : values()) {
            if (format.available == wanted) {
                selected.add(format);
            }
        }
        return List.copyOf(selected);
    }
}
