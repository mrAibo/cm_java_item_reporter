package com.mraibo.cminsight.ibm.internal;

/**
 * Where an adapter failure is reported so that diagnostics can show the most recent one.
 *
 * <h2>Why an interface instead of a direct reference to the pool</h2>
 *
 * <p>{@link IbmCmSession} and {@link IbmCmApi} need to publish a sanitised failure for the diagnostics
 * page, and {@link IbmCmSessionPool} is what ultimately holds that value. Depending on the pool directly
 * would work, but it would give every session a reference to the whole pool - including its borrow and
 * close paths - purely to write one string. This is the entire dependency the session needs, so it is the
 * entire dependency it gets.
 *
 * <h2>What may be recorded</h2>
 *
 * <p>Only text that has already passed through {@link IbmErrorSanitizer}: a category, an operation label
 * and an IBM exception <em>class</em> name. Never a vendor message verbatim, never a repository name out
 * of that message, never a user id and never any part of a credential. The implementation is required to
 * treat the argument as already sanitised and must not append anything of its own to it.
 *
 * <p>An implementation must be cheap and non-blocking: it is called from failure paths, including
 * ones taken while another thread is waiting to borrow a session.
 */
interface AdapterErrorSink {

    /**
     * Records the most recent sanitised adapter failure.
     *
     * @param sanitisedFailure value-free description; a blank value is ignored by implementations
     */
    void recordAdapterError(String sanitisedFailure);
}
