package com.mraibo.cminsight.ibm.internal;

/**
 * An adapter read failure, reported unchecked, with a message that is already safe to show.
 *
 * <h2>Why unchecked, when the core interface declares a checked failure</h2>
 *
 * <p>{@code RepositoryException} is checked, and the read-service interfaces declare it - which is the
 * honest contract for a caller that genuinely wants to handle a read failure. What it must NOT do is force
 * every consumer up the stack to declare it or catch-and-ignore it. The consumers here are authenticated
 * HTTP handlers and the single {@code --check-repository} CLI, and the failure mode that creates is
 * specifically the one Goal 02 exists to prevent: a handler that catches the checked exception to keep
 * compiling, then renders an empty list. A viewer showing "0 ItemTypes" for a repository it could not reach
 * is worse than an error, because it looks like an answer.
 *
 * <p>So the adapter reports a read failure in the form its consumers can actually act on - an unchecked
 * exception that aborts the request and is mapped to a clean {@code 502 cm_unavailable} - while the checked
 * contract on the core interface stays available to any caller that wants it. A consumer that wants the
 * checked type can wrap this one: it carries the same information, and its cause chain is intact.
 *
 * <h2>What the message may contain</h2>
 *
 * <p>Only text produced by {@link IbmErrorSanitizer}: an operation label, a category and an IBM exception
 * class name. Never a vendor message verbatim, never a repository name taken from one, never a user id and
 * never any part of a credential. The original failure is kept as the CAUSE, so a developer reading a
 * debugger sees the real message while every field an operator or an HTTP response exposes stays sanitised.
 * Redacting at logging time is one forgotten call away from a leak; redacting at creation time cannot be
 * forgotten.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>Not a signal for "the repository has no such ItemType": that is an ANSWER, it travels as
 * {@link java.util.Optional#empty()}, and conflating the two would turn a typo in a URL into an outage
 * report. Nor is it used for backpressure - an exhausted pool reports that separately, because a busy
 * repository and a broken one need different operator responses.
 */
public final class IbmCmReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Value-free classification of what went wrong, for diagnostics and log lines. */
    private final String category;

    public IbmCmReadException(String category, String sanitisedMessage, Throwable cause) {
        super(sanitisedMessage, cause);
        this.category = category == null || category.isBlank() ? "cm" : category;
    }

    public String category() {
        return category;
    }

    @Override
    public String toString() {
        return "IbmCmReadException[category=" + category + ", message=" + getMessage() + "]";
    }
}
