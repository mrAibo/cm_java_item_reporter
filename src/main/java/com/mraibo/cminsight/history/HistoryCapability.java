package com.mraibo.cminsight.history;

import com.mraibo.cminsight.core.HistorySettings;

import java.util.Objects;
import java.util.Optional;

/**
 * The application-local history capability: the settings it was built from and the store it uses.
 *
 * <h2>One store per process, not one per repository</h2>
 *
 * <p>Persistent history deliberately outlives a {@code RepositoryContext}: switching repository must not
 * make yesterday's snapshots unreachable, and a context is replaced on every switch. So the store is created
 * once for the whole process and handed to each activation, while the {@link HistoryRecorder} - which IS
 * per-repository, because it captures one repository's identity and retention names - is built from it at
 * activation time.
 *
 * <h2>Why a bundle rather than two parameters</h2>
 *
 * <p>Every wiring point that needs history needs both halves together: the recorder needs the store to
 * write to, and the routes need the store plus the feature switch to decide whether to answer or report the
 * capability unavailable. Passing them as one value keeps that pairing from being broken by a call site that
 * supplies one and forgets the other, and it keeps the constructor churn in the shared wiring to a single
 * parameter.
 *
 * @param settings the validated history configuration, including the feature switch and the retention bound
 * @param store    the store to use; never null, and an unavailable store is a real object rather than null
 */
public record HistoryCapability(HistorySettings settings, HistoryStore store) {

    public HistoryCapability {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(store, "store");
    }

    /**
     * A capability with no store at all, for a runtime that has no history wiring.
     *
     * <p>Deliberately reports the reason rather than behaving like a silent no-op: a caller that asks why
     * history is unavailable should get an answer that names the wiring, not an empty list that looks like a
     * repository with no history.
     */
    public static HistoryCapability unwired(HistorySettings settings) {
        return new HistoryCapability(settings,
                HistoryStores.unavailable("No history capability is wired into this runtime"));
    }

    /** True when the store can actually persist and read right now. */
    public boolean available() {
        return store.available();
    }

    /** A fixed, value-free reason when the store is unavailable, or empty when it works. */
    public Optional<String> unavailableReason() {
        return store.unavailableReason();
    }

    /** A short value-free description for a diagnostics line. */
    public String describe() {
        if (!settings.enabled()) {
            return "disabled (feature.history=false)";
        }
        if (!store.available()) {
            return "unavailable: " + store.unavailableReason().orElse("no reason reported");
        }
        return "ready at " + settings.databasePath() + " (at most " + settings.maxSnapshotsPerRepository()
                + " snapshot(s) per repository)";
    }

    /** Closes the store. Never throws; a history problem must not fail repository shutdown. */
    public void close() {
        store.close();
    }
}
