package com.mraibo.cminsight.db;

import java.util.List;
import java.util.Objects;

/**
 * A fully-formed, parameterised aggregate statement: SQL text plus the values to bind, in order.
 *
 * <p>Exists so that the shape of a vendor statement and the values it binds cannot drift apart. A caller
 * cannot "forget" a parameter or bind them in a different order than the SQL expects, because the two
 * travel together and the constructor checks that their counts agree.
 *
 * @param sql        SELECT-only SQL containing exactly {@code parameters.size()} {@code ?} markers
 * @param parameters values to bind in ascending marker order; never null
 * @param description a short label naming the operation for diagnostics, never containing SQL text, a URL
 *                    or a credential
 */
public record AggregateQuery(String sql, List<Object> parameters, String description) {

    public AggregateQuery {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(description, "description");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        int markers = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                markers++;
            }
        }
        if (markers != parameters.size()) {
            throw new IllegalArgumentException("AggregateQuery '" + description + "' has " + markers
                    + " parameter marker(s) in its SQL but " + parameters.size() + " bound value(s)");
        }
    }

    /** The number of bound values, which equals the number of markers in {@link #sql()}. */
    public int parameterCount() {
        return parameters.size();
    }
}
