package com.mraibo.cminsight.report;

import java.util.List;
import java.util.Objects;

/**
 * One format's renderer: a pure function from the immutable report model to bytes.
 *
 * <h2>The signature is the safety property</h2>
 *
 * <p>{@link #render(ReportModel)} takes the model and nothing else - no store, no session, no repository
 * handle, no filesystem, no clock. A renderer therefore <em>cannot</em> perform a hidden IBM CM or
 * repository-database read to fill a gap, and it cannot hold a lease while it writes, because it never had
 * one to hold. Goal 04 section 6 states that rule; this interface is what makes it structural rather than a
 * promise in a document.
 *
 * <p>Rendering is side-effect free: it produces bytes and touches no file. Writing them below
 * {@code reports.dir} is {@link ReportService}'s job, which is the other half of the same split - the
 * renderer has no path to build and no name to trust.
 */
public interface ReportRenderer {

    /**
     * The format this renderer produces.
     *
     * @return the format constant, never {@code null}
     */
    ReportFormat format();

    /**
     * Renders the model. Never writes anything; throws only {@link ReportException}.
     *
     * @param model the immutable captured input; the renderer reads nothing else
     * @return the artifact's bytes, encoded for that format
     */
    byte[] render(ReportModel model);

    /**
     * The renderer for one format.
     *
     * @param format the requested format
     * @return the renderer for that format, never {@code null}
     * @throws ReportException with {@link ReportException.Reason#FORMAT_UNAVAILABLE} when this build cannot
     *                         produce the format, so a caller cannot be handed a renderer that would fake it
     */
    static ReportRenderer forFormat(ReportFormat format) {
        Objects.requireNonNull(format, "format");
        if (!format.available()) {
            throw new ReportException(ReportException.Reason.FORMAT_UNAVAILABLE);
        }
        return switch (format) {
            case HTML -> new HtmlReportRenderer();
            case CSV -> new CsvReportRenderer();
            case XLSX -> new XlsxReportRenderer();
        };
    }

    /**
     * One renderer per format, in the vocabulary's declaration order.
     *
     * @return every renderer this build has, never {@code null}
     */
    static List<ReportRenderer> all() {
        return List.of(new HtmlReportRenderer(), new CsvReportRenderer(), new XlsxReportRenderer());
    }
}
