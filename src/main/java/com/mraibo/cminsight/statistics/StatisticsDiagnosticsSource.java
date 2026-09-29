package com.mraibo.cminsight.statistics;

/**
 * Supplies the safe pool/scan diagnostics view for the analytics half.
 *
 * <p>Implemented by the JDBC bridge, which is the only place that can read the pool's counters and the
 * factory's physical facts. The interface exists so that the typed service can answer a diagnostics request
 * without holding a pool, a connection or any {@code java.sql} type, and so that "there is no pool at all"
 * (disabled feature, missing driver, missing credential) is expressible as a value rather than as an
 * exception.
 *
 * <p>An implementation must never throw and must never return {@code null}: a diagnostics endpoint is
 * exactly the surface that has to keep answering while everything else is broken. The service treats a
 * throwing implementation as "no diagnostics available" rather than letting the failure escape.
 */
public interface StatisticsDiagnosticsSource {

    /**
     * @param availability the availability verdict the service already resolved; an implementation should
     *                     report it verbatim rather than re-deriving it, so the two surfaces cannot disagree
     */
    StatisticsDiagnostics diagnostics(StatisticsAvailability availability);
}
