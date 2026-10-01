package com.mraibo.cminsight.ibm.internal;

/**
 * A sanitised IBM CM failure, carried as a runtime exception so an adapter-internal read chain does not
 * have to declare the vendor's checked types on every helper.
 *
 * <h2>Why a dedicated type instead of {@code DKException} everywhere</h2>
 *
 * <p>There are two good reasons and one specific to this project's guards:
 *
 * <ul>
 *   <li>The services implement IBM-free core interfaces ({@code MetadataRepository},
 *       {@code RetentionRepository}). Throwing a {@code com.ibm} type through them would break the
 *       isolation the whole optional-source-set design rests on.</li>
 *   <li>Sanitisation has to happen exactly once, where the SDK call is made. Carrying the ORIGINAL
 *       failure as the cause keeps it available to a developer reading a log while the <em>message</em>
 *       stays value-free: a class name and a category, never the vendor text verbatim.</li>
 *   <li>{@link #backendUnusable()} is what tells the caller whether the session that produced this
 *       failure may go back into the pool. Reusing a session whose server call just broke is how a
 *       repository starts answering nonsense instead of reporting a problem.</li>
 * </ul>
 *
 * <p>The flag is a VIEW of a decision that was already taken elsewhere: it records what
 * {@link IbmErrorSanitizer#backendUnusable(Throwable)} decided and, crucially, the borrowed session was
 * marked unusable at the same moment (see {@link IbmCmApi}, which owns both halves). A
 * {@code backendUnusable() == true} instance whose session was not marked is exactly the Goal 02A defect,
 * which is why the classification lives where the marking does rather than in this type.
 *
 * <p>A {@code DKNotExistException} - "the repository has no such ItemType/policy" - is deliberately NOT
 * carried as this type: it is an answer, not a failure, and a caller must be able to tell the two apart.
 * The API layer maps that distinction to {@code Optional.empty()}.
 */
public final class IbmCmFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Value-free classification of what went wrong, for diagnostics and logs. */
    private final String category;

    /** True when the physical session that produced this failure must not be reused. */
    private final boolean backendUnusable;

    public IbmCmFailure(String category, String message, Throwable cause, boolean backendUnusable) {
        super(message, cause);
        this.category = category == null || category.isBlank() ? "cm" : category;
        this.backendUnusable = backendUnusable;
    }

    public String category() {
        return category;
    }

    /**
     * True when the physical session that produced this failure must not be reused.
     *
     * <p>Named after the flag the Goal 02A review refers to, and the only accessor for it: the session was
     * marked unusable where this value was decided, so a caller that reads this has nothing further to do.
     */
    public boolean backendUnusable() {
        return backendUnusable;
    }

    @Override
    public String toString() {
        return "IbmCmFailure[category=" + category + ", backendUnusable=" + backendUnusable
                + ", message=" + getMessage() + "]";
    }
}
