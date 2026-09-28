package com.mraibo.cminsight.ibm.internal;

import com.ibm.mm.sdk.common.DKDatastoreAccessError;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKNotExistException;
import com.ibm.mm.sdk.common.DKSystemError;
import com.ibm.mm.sdk.common.DKUsageError;

/**
 * Turns a vendor failure into diagnostic text that is safe to store, log and return over HTTP.
 *
 * <h2>What may never leave this class</h2>
 *
 * <p>An IBM CM exception message is free text chosen by the server. It can carry a repository name, a
 * user id, a database alias, a connection string fragment or a SQL statement - the reconnaissance of
 * {@code CM_Migrator} shows several of those in practice. So the raw message is <strong>never</strong>
 * the returned text on its own. What is returned is:
 *
 * <pre>
 *   &lt;category&gt;(&lt;exception class simple name&gt;[, error id])
 * </pre>
 *
 * <p>For example {@code cm-access(DKNotExistException)} or {@code cm-system(DKSystemError, errorId=1234)}.
 * An operator gets what they need - which layer failed and which IBM type reported it - and no part of a
 * message the server wrote can reach a log or an API response.
 *
 * <h2>Why the error id is kept, and why it is read defensively</h2>
 *
 * <p>{@code DKException.getErrorId()} is a server-supplied integer and is exactly the value an IBM
 * support case asks for, so dropping it would make a real incident harder to resolve. The call is
 * wrapped because an accessor on a half-constructed SDK exception can itself throw, and a failure while
 * describing a failure is the worst possible place to lose the original information.
 *
 * <h2>The cause is preserved, the text is not</h2>
 *
 * <p>Every {@link IbmCmFailure} keeps the original exception as its cause, so a developer can read the
 * real message in a debugger while the field an operator sees stays sanitised. That split is deliberate:
 * redacting at the point of logging is one forgotten call away from a leaked repository name, whereas
 * redacting at the point of creation cannot be forgotten.
 */
public final class IbmErrorSanitizer {

    private IbmErrorSanitizer() {
    }

    /**
     * Classifies a CM failure into a value-free category.
     *
     * <p>The categories are deliberately coarse: they answer "which layer", which is what a diagnostics
     * page and an operator need, without inventing a taxonomy the SDK's own error codes would contradict.
     */
    public static String category(Throwable failure) {
        if (failure == null) {
            return "cm";
        }
        if (failure instanceof DKNotExistException) {
            // An answer, not a fault. Kept in its own category because a caller is allowed to translate
            // it into "no such ItemType" rather than into an error.
            return "cm-not-found";
        }
        if (failure instanceof DKDatastoreAccessError) {
            return "cm-access";
        }
        if (failure instanceof DKUsageError) {
            return "cm-usage";
        }
        if (failure instanceof DKSystemError) {
            return "cm-system";
        }
        if (failure instanceof DKException) {
            return "cm";
        }
        if (failure instanceof InterruptedException) {
            return "interrupted";
        }
        if (failure instanceof Error) {
            return "fatal";
        }
        return "cm";
    }

    /**
     * A sanitised one-line description of a failure, never the vendor message verbatim.
     *
     * @param context short operation label such as {@code connect}, {@code listItemTypes} or
     *                {@code disconnect}; included so an operator knows where it happened
     */
    public static String describe(String context, Throwable failure) {
        String prefix = context == null || context.isBlank() ? "cm" : context.trim();
        if (failure == null) {
            return prefix + ": unknown failure";
        }
        StringBuilder text = new StringBuilder(64);
        text.append(prefix).append(' ').append(category(failure)).append('(')
                .append(failure.getClass().getSimpleName());
        Integer errorId = errorId(failure);
        if (errorId != null) {
            text.append(", errorId=").append(errorId);
        }
        text.append(')');
        return text.toString();
    }

    /**
     * The SDK's own error id when one is exposed, or {@code null}.
     *
     * <p>Read defensively: the accessor is SDK code and a failure inside it must not replace the failure
     * being described.
     */
    public static Integer errorId(Throwable failure) {
        if (!(failure instanceof DKException cmFailure)) {
            return null;
        }
        try {
            return cmFailure.getErrorId();
        } catch (RuntimeException | Error ignored) {
            // The accessor itself failed. A missing error id is a smaller loss than a suppressed cause.
            return null;
        }
    }

    /**
     * True when the failure means the physical session must not be returned to the pool.
     *
     * <p>A not-found answer is a healthy server replying "no", so the session is fine. Everything else
     * from the SDK is treated as a broken session: the conservative reading costs one session
     * re-creation, and the optimistic reading risks serving wrong data from a wedged connection.
     */
    public static boolean backendUnusable(Throwable failure) {
        if (failure == null) {
            return true;
        }
        if (failure instanceof DKNotExistException) {
            return false;
        }
        return failure instanceof DKException
                || failure instanceof Error
                || !(failure instanceof RuntimeException);
    }
}
