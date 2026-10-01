package com.mraibo.cminsight.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The immutable column/row model behind {@link FakeJdbc}'s fake {@link java.sql.ResultSet}.
 *
 * <h2>Why a table model instead of a canned result set</h2>
 *
 * <p>A test that hands the production query path a result set it built by hand proves that the path can
 * read <em>those</em> rows. What the Goal 03 suites need to prove is stronger and different: that the
 * aggregate the code computes is the aggregate of a <em>physical table model</em> the test owns, so a
 * segment that the implementation forgot to include, or a duplicate ItemID that it failed to collapse,
 * changes the number the test sees. That is why the rows live in a plain table here and the fake driver
 * answers questions about it, rather than each test inventing its own result set.
 *
 * <p>The model is deliberately tiny: labelled columns, object cells, and a case-insensitive label lookup
 * with JDBC's 1-based indexes. Cells are converted on read, so a test can put an {@code Integer} in a
 * column the code reads with {@code getLong}, exactly as a real driver would.
 */
final class FakeResultTable {

    private final List<String> columns;
    private final List<Object[]> rows;

    private FakeResultTable(List<String> columns, List<Object[]> rows) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
    }

    /**
     * A table with the given column labels and rows.
     *
     * <p>Every row must have exactly one cell per column; a short row is a test bug that would otherwise
     * surface as a confusing {@code ArrayIndexOutOfBoundsException} inside a proxy handler.
     */
    static FakeResultTable of(List<String> columns, List<Object[]> rows) {
        List<String> copy = new ArrayList<>(columns);
        for (Object[] row : rows) {
            Assert.assertEquals(columns.size(), row.length,
                    "every fake result row must hold one cell per column " + columns);
        }
        return new FakeResultTable(copy, rows);
    }

    /** A table with columns and no rows: the shape a zero-row existence probe needs. */
    static FakeResultTable empty(String... columns) {
        return new FakeResultTable(Arrays.asList(columns), List.of());
    }

    /** A table with columns and exactly one row. */
    static FakeResultTable oneRow(List<String> columns, Object... row) {
        // Explicit type witness: without it, List.of(row) would infer List<Object> from the varargs array
        // and the row would no longer be the single row it is meant to be.
        return of(columns, List.<Object[]>of(row));
    }

    /** A single unnamed {@code long} column, one row per value: the shape a count query returns. */
    static FakeResultTable longs(String column, long... values) {
        List<Object[]> rows = new ArrayList<>(values.length);
        for (long value : values) {
            rows.add(new Object[] {value});
        }
        return new FakeResultTable(List.of(column), rows);
    }

    List<String> columns() {
        return columns;
    }

    int columnCount() {
        return columns.size();
    }

    int rowCount() {
        return rows.size();
    }

    Object[] row(int index) {
        return rows.get(index);
    }

    /**
     * The JDBC 1-based index of a column label, case-insensitively.
     *
     * @throws IllegalArgumentException when the label is not part of this table, which the fake driver
     *         converts into a {@link java.sql.SQLException}: a query that names a column the fake does not
     *         have is a test that expected the wrong SQL, and it must not silently read {@code null}
     */
    int columnIndex(String label) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).equalsIgnoreCase(label)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("the fake result table has no column '" + label + "'; it has "
                + columns);
    }

    /** A value converted for a {@code getLong} read, exactly as a lenient driver would. */
    long longAt(int columnIndex, int rowIndex) {
        Object value = valueAt(columnIndex, rowIndex);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString().trim());
    }

    Object valueAt(int columnIndex, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new IllegalStateException("the fake result set is positioned outside its rows (row "
                    + rowIndex + " of " + rows.size() + ")");
        }
        return rows.get(rowIndex)[columnIndex - 1];
    }

    /**
     * A value converted for a {@code getDate} read.
     *
     * <p>Accepts the three forms a test might reasonably put in a cell for a date column: a {@link
     * java.sql.Date}, a {@link java.time.LocalDate}, or an ISO {@code yyyy-MM-dd} string. A {@code null}
     * cell stays {@code null}, which is what the production date reader treats as "the driver returned no
     * date".
     */
    java.sql.Date dateAt(int columnIndex, int rowIndex) {
        Object value = valueAt(columnIndex, rowIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date;
        }
        if (value instanceof java.time.LocalDate local) {
            return java.sql.Date.valueOf(local);
        }
        return java.sql.Date.valueOf(value.toString().trim());
    }

    /** True when this table has the given label, used to decide which JDBC getter a column serves. */
    boolean hasColumn(String label) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    /** The label of a column, upper-cased for stable reporting. */
    String label(int columnIndex) {
        return columns.get(columnIndex - 1).toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "FakeResultTable" + columns + " rows=" + rows.size();
    }
}
