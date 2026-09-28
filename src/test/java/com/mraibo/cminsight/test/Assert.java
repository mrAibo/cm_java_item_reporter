package com.mraibo.cminsight.test;

import java.util.Objects;

/**
 * The whole assertion vocabulary of this suite: no JUnit, no dependency, plain JDK 17.
 *
 * <p>Every failure is an {@link AssertionError} carrying the message the test passed in, so the
 * runner can print one actionable line per failed test.
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

    static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " (was <" + value + ">)");
        }
    }

    /** Runs an action and returns the expected exception, or fails with a clear message. */
    static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) { // NOPMD - the action may throw any checked exception
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
