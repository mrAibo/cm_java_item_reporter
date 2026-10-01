package com.mraibo.cminsight.ibm.internal;

/**
 * The two resolved CM credential values for one connect attempt, held only as long as that call needs them.
 *
 * <h2>Why a type at all, for two strings</h2>
 *
 * <p>Because the alternative - a bare {@code (String user, String password)} parameter pair - has a
 * failure mode this project cannot accept: at some call site, somebody prints the argument list, or a
 * debugger's friendly rendering of an arguments object, and a customer's CM password is in a log. A type
 * whose {@link #toString()} deliberately shows sources and never values removes that possibility
 * structurally rather than by discipline.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>Not a record, and not {@code equals}/{@code hashCode}-friendly on the values: a record's generated
 * {@code toString()} would print both strings verbatim, which is exactly the leak to prevent. And although
 * the two strings exist in the JVM while a connect attempt runs, they are never stored on the factory, the
 * pool, the session or any part of the repository context - the reference is dropped when the attempt
 * returns.
 *
 * <p>Accessors are package-private: only {@link IbmCmSessionFactory} constructs one and only
 * {@link IbmCmConnectionFactory} consumes it, so the values cannot travel further without somebody
 * deliberately adding a method.
 */
final class IbmCmCredentials {

    private final String user;
    private final String password;

    IbmCmCredentials(String user, String password) {
        this.user = user;
        this.password = password;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    /** Names only. Never the value of either credential. */
    @Override
    public String toString() {
        return "IbmCmCredentials[user=<resolved>, password=<redacted>]";
    }
}
