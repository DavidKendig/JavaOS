package javaos.office;

import java.util.ArrayList;
import java.util.List;

/**
 * A spreadsheet as a grid of raw cell text, using the same convention as JavaOS
 * Calc: a cell either holds literal text, or a formula beginning with {@code =}.
 * Formulas are translated into this form on the way in and back out again, so
 * {@code =SUM(A1:A3)} survives a trip through a .ods or .xlsx file.
 *
 * <p>A second grid holds the last computed value of each formula. Office files
 * carry that cached result alongside the formula, and a reader that opens the
 * file before recalculating shows it, so writing it keeps the sheet looking
 * right the moment it is opened.
 */
public final class SheetDocument {

    /** Import guard: a hostile or merely enormous sheet should not exhaust the heap. */
    public static final int MAX_ROWS = 20000;
    public static final int MAX_COLUMNS = 1024;

    /** A sparse grid of strings that grows as it is written to. */
    private static final class Grid {
        private final List<List<String>> rows = new ArrayList<>();

        String get(int row, int column) {
            if (row < 0 || row >= rows.size()) {
                return "";
            }
            List<String> cells = rows.get(row);
            if (column < 0 || column >= cells.size()) {
                return "";
            }
            String value = cells.get(column);
            return value == null ? "" : value;
        }

        void set(int row, int column, String value) {
            if (row < 0 || column < 0 || row >= MAX_ROWS || column >= MAX_COLUMNS) {
                return;
            }
            if (value == null || value.isEmpty()) {
                if (row < rows.size() && column < rows.get(row).size()) {
                    rows.get(row).set(column, null);
                }
                return;
            }
            while (rows.size() <= row) {
                rows.add(new ArrayList<>());
            }
            List<String> cells = rows.get(row);
            while (cells.size() <= column) {
                cells.add(null);
            }
            cells.set(column, value);
        }

        int rowCount() {
            return rows.size();
        }

        int columnCount() {
            int widest = 0;
            for (List<String> row : rows) {
                widest = Math.max(widest, row.size());
            }
            return widest;
        }

        void trim() {
            while (!rows.isEmpty() && isEmpty(rows.size() - 1)) {
                rows.remove(rows.size() - 1);
            }
        }

        private boolean isEmpty(int row) {
            for (String cell : rows.get(row)) {
                if (cell != null && !cell.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    private final Grid content = new Grid();
    private final Grid cached = new Grid();
    private String name = "Sheet1";

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public int rowCount() {
        return content.rowCount();
    }

    public int columnCount() {
        return content.columnCount();
    }

    /** The cell as typed: literal text, or a formula starting with {@code =}. */
    public String get(int row, int column) {
        return content.get(row, column);
    }

    public void set(int row, int column, String value) {
        content.set(row, column, value);
    }

    /** The last computed result of a formula cell, if one is known. */
    public String cachedValue(int row, int column) {
        return cached.get(row, column);
    }

    public void setCachedValue(int row, int column, String value) {
        cached.set(row, column, value);
    }

    /** What a reader should display: the cached result for formulas, else the text. */
    public String displayValue(int row, int column) {
        String raw = get(row, column);
        if (raw.startsWith("=")) {
            String value = cachedValue(row, column);
            return value.isEmpty() ? "" : value;
        }
        return raw;
    }

    public boolean isFormula(int row, int column) {
        return get(row, column).startsWith("=");
    }

    /** Drops trailing empty rows left behind by generous producers. */
    public void trim() {
        content.trim();
        cached.trim();
    }

    /** Parses a cell as a number, or returns null when it is text. */
    public static Double asNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Formats a number the way a spreadsheet file wants it: no grouping, no exponent. */
    public static String plainNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)
                && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ---- column names --------------------------------------------------

    /** 0 becomes A, 25 becomes Z, 26 becomes AA. */
    public static String columnName(int column) {
        StringBuilder name = new StringBuilder();
        int n = column;
        do {
            name.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return name.toString();
    }

    public static int columnIndex(String letters) {
        int index = 0;
        for (int i = 0; i < letters.length(); i++) {
            char ch = Character.toUpperCase(letters.charAt(i));
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            index = index * 26 + (ch - 'A' + 1);
        }
        return index - 1;
    }

    /** Splits an A1-style reference into row and column indices, or null. */
    public static int[] parseReference(String reference) {
        String cleaned = reference.replace("$", "");
        int split = 0;
        while (split < cleaned.length() && Character.isLetter(cleaned.charAt(split))) {
            split++;
        }
        if (split == 0 || split == cleaned.length()) {
            return null;
        }
        try {
            int row = Integer.parseInt(cleaned.substring(split)) - 1;
            return new int[] {row, columnIndex(cleaned.substring(0, split))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
