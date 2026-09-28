package com.mraibo.cminsight.ibm.internal;

import java.util.Objects;

/**
 * The assertion vocabulary of the IBM adapter suites.
 *
 * <p>Copied deliberately rather than shared with {@code com.mraibo.cminsight.test.Assert}: that helper is
 * package-private to the core suite, and the adapter tree must stay installable on its own. The semantics
 * are identical on purpose - every failure is an {@link AssertionError} carrying the caller's message, so
 * the runner prints one actionable line per failed test - and no test may depend on the differences.
 */
final class Assert {

    /** A test step that may throw anything, which is what most assertions wrap. */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private Assert() {
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
        }
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected <" + expected + "> but was <" + actual + ">)");
        }
    }

    static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    /** Runs an action and returns the expected exception, or fails with a clear message. */
    static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                @SuppressWarnings("unchecked")
                T typed = (T) thrown;
                return typed;
            }
            throw new AssertionError(message + " (expected " + type.getName() + " but got "
                    + thrown.getClass().getName() + ": " + thrown.getMessage() + ")", thrown);
        }
        throw new AssertionError(message + " (expected " + type.getName() + " but nothing was thrown)");
    }

    static void fail(String message) {
        throw new AssertionError(message);
    }
}
