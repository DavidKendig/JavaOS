package javaos.apps;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.table.AbstractTableModel;

/**
 * The grid behind Calc: raw cell text in, displayed values out, with a small
 * recursive-descent formula evaluator in between.
 */
public class SheetModel extends AbstractTableModel {

    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.####");

    private final int rows;
    private final int cols;
    private final String[][] raw;
    private final Set<Long> evaluating = new HashSet<>();
    private boolean bulk;

    public SheetModel(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.raw = new String[rows][cols];
    }

    // ---- table model ---------------------------------------------------

    @Override public int getRowCount() {
        return rows;
    }

    @Override public int getColumnCount() {
        return cols;
    }

    @Override public String getColumnName(int column) {
        return columnName(column);
    }

    @Override public boolean isCellEditable(int row, int column) {
        return true;
    }

    @Override public Object getValueAt(int row, int column) {
        return display(row, column);
    }

    @Override public void setValueAt(Object value, int row, int column) {
        String text = value == null ? "" : value.toString();
        raw[row][column] = text.isEmpty() ? null : text;
        if (!bulk) {
            // Any cell may feed a formula somewhere else, so repaint the lot.
            fireTableRowsUpdated(0, rows - 1);
        }
    }

    /**
     * Runs a batch of cell writes with a single repaint at the end. Loading a
     * sheet one cell at a time otherwise repaints the whole grid per cell.
     */
    public void bulkUpdate(Runnable work) {
        bulk = true;
        try {
            work.run();
        } finally {
            bulk = false;
            fireTableDataChanged();
        }
    }

    public String rawAt(int row, int column) {
        String value = raw[row][column];
        return value == null ? "" : value;
    }

    public void clear(int row, int column) {
        raw[row][column] = null;
        if (!bulk) {
            fireTableRowsUpdated(0, rows - 1);
        }
    }

    public void clearAll() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                raw[r][c] = null;
            }
        }
        fireTableDataChanged();
    }

    // ---- naming --------------------------------------------------------

    public static String columnName(int column) {
        StringBuilder sb = new StringBuilder();
        int n = column;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    public static int columnIndex(String name) {
        int index = 0;
        for (char ch : name.toUpperCase().toCharArray()) {
            index = index * 26 + (ch - 'A' + 1);
        }
        return index - 1;
    }

    public static String cellName(int row, int column) {
        return columnName(column) + (row + 1);
    }

    // ---- evaluation ----------------------------------------------------

    /** What the grid shows: text as typed, formulas as their result. */
    public String display(int row, int column) {
        String text = raw[row][column];
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!text.startsWith("=")) {
            return text;
        }
        try {
            return format(evaluate(row, column, text.substring(1)));
        } catch (Circular e) {
            return "#CIRC!";
        } catch (ArithmeticException e) {
            return "#DIV/0!";
        } catch (RuntimeException e) {
            return "#ERR!";
        }
    }

    /** The numeric value of a cell: formulas evaluated, text counted as zero. */
    public double numberAt(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= cols) {
            return 0;
        }
        String text = raw[row][column];
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.startsWith("=")) {
            return evaluate(row, column, text.substring(1));
        }
        try {
            return Double.parseDouble(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double evaluate(int row, int column, String expression) {
        long key = (long) row * cols + column;
        if (!evaluating.add(key)) {
            throw new Circular();
        }
        try {
            Parser parser = new Parser(expression);
            double value = parser.parseAll();
            return value;
        } finally {
            evaluating.remove(key);
        }
    }

    public static String format(double value) {
        if (Double.isNaN(value)) {
            return "#NUM!";
        }
        if (Double.isInfinite(value)) {
            return "#DIV/0!";
        }
        return NUMBER.format(value);
    }

    /** Raised when a formula ends up asking for its own value. */
    private static class Circular extends RuntimeException {
    }

    // ---- csv -----------------------------------------------------------

    /** Writes raw cell text, so formulas survive a round trip. */
    public String toCsv() {
        int lastRow = -1;
        int lastCol = -1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (raw[r][c] != null && !raw[r][c].isEmpty()) {
                    lastRow = Math.max(lastRow, r);
                    lastCol = Math.max(lastCol, c);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= lastRow; r++) {
            for (int c = 0; c <= lastCol; c++) {
                String value = raw[r][c] == null ? "" : raw[r][c];
                if (value.contains(",") || value.contains("\"")) {
                    value = "\"" + value.replace("\"", "\"\"") + "\"";
                }
                sb.append(value);
                if (c < lastCol) {
                    sb.append(',');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void fromCsv(String csv) {
        clearAll();
        String[] lines = csv.split("\r?\n", -1);
        for (int r = 0; r < lines.length && r < rows; r++) {
            List<String> fields = splitCsvLine(lines[r]);
            for (int c = 0; c < fields.size() && c < cols; c++) {
                String value = fields.get(c);
                raw[r][c] = value.isEmpty() ? null : value;
            }
        }
        fireTableDataChanged();
    }

    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    // ---- formula parser ------------------------------------------------

    /**
     * Grammar: expr := term (('+'|'-') term)* ; term := power (('*'|'/') power)* ;
     * power := unary ('^' power)? ; unary := '-'? atom ;
     * atom := number | '(' expr ')' | function '(' args ')' | cell reference.
     */
    private class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        double parseAll() {
            double value = expr();
            skipSpace();
            if (at < text.length()) {
                throw new IllegalArgumentException("unexpected '" + text.charAt(at) + "'");
            }
            return value;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean eat(char ch) {
            skipSpace();
            if (at < text.length() && text.charAt(at) == ch) {
                at++;
                return true;
            }
            return false;
        }

        double expr() {
            double value = term();
            while (true) {
                if (eat('+')) {
                    value += term();
                } else if (eat('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        double term() {
            double value = power();
            while (true) {
                if (eat('*')) {
                    value *= power();
                } else if (eat('/')) {
                    double divisor = power();
                    if (divisor == 0) {
                        throw new ArithmeticException("divide by zero");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        double power() {
            double base = unary();
            if (eat('^')) {
                return Math.pow(base, power());
            }
            return base;
        }

        double unary() {
            if (eat('-')) {
                return -unary();
            }
            eat('+');
            return atom();
        }

        double atom() {
            skipSpace();
            if (at >= text.length()) {
                throw new IllegalArgumentException("unexpected end of formula");
            }
            char ch = text.charAt(at);
            if (ch == '(') {
                at++;
                double value = expr();
                if (!eat(')')) {
                    throw new IllegalArgumentException("missing )");
                }
                return value;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = at;
                while (at < text.length()
                        && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                    at++;
                }
                return Double.parseDouble(text.substring(start, at));
            }
            if (Character.isLetter(ch)) {
                int start = at;
                while (at < text.length() && Character.isLetter(text.charAt(at))) {
                    at++;
                }
                String word = text.substring(start, at);
                if (at < text.length() && Character.isDigit(text.charAt(at))) {
                    int digitStart = at;
                    while (at < text.length() && Character.isDigit(text.charAt(at))) {
                        at++;
                    }
                    int row = Integer.parseInt(text.substring(digitStart, at)) - 1;
                    return numberAt(row, columnIndex(word));
                }
                if (eat('(')) {
                    List<Double> args = arguments();
                    if (!eat(')')) {
                        throw new IllegalArgumentException("missing ) after " + word);
                    }
                    return apply(word.toUpperCase(), args);
                }
                throw new IllegalArgumentException("unknown name " + word);
            }
            throw new IllegalArgumentException("unexpected '" + ch + "'");
        }

        /** Arguments are expressions, except ranges like A1:B4 which expand in place. */
        private List<Double> arguments() {
            List<Double> values = new ArrayList<>();
            skipSpace();
            if (at < text.length() && text.charAt(at) == ')') {
                return values;
            }
            do {
                int mark = at;
                Range range = tryRange();
                if (range != null) {
                    for (int r = range.firstRow; r <= range.lastRow; r++) {
                        for (int c = range.firstCol; c <= range.lastCol; c++) {
                            values.add(numberAt(r, c));
                        }
                    }
                } else {
                    at = mark;
                    values.add(expr());
                }
            } while (eat(','));
            return values;
        }

        private Range tryRange() {
            skipSpace();
            int mark = at;
            Integer[] first = readReference();
            if (first == null || !eat(':')) {
                at = mark;
                return null;
            }
            Integer[] second = readReference();
            if (second == null) {
                at = mark;
                return null;
            }
            return new Range(Math.min(first[0], second[0]), Math.min(first[1], second[1]),
                    Math.max(first[0], second[0]), Math.max(first[1], second[1]));
        }

        private Integer[] readReference() {
            skipSpace();
            int start = at;
            while (at < text.length() && Character.isLetter(text.charAt(at))) {
                at++;
            }
            if (at == start) {
                return null;
            }
            String letters = text.substring(start, at);
            int digitStart = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            if (at == digitStart) {
                at = start;
                return null;
            }
            return new Integer[] {Integer.parseInt(text.substring(digitStart, at)) - 1,
                    columnIndex(letters)};
        }

        private double apply(String function, List<Double> args) {
            switch (function) {
                case "SUM":
                    return args.stream().mapToDouble(Double::doubleValue).sum();
                case "AVG":
                case "AVERAGE":
                    return args.isEmpty() ? 0
                            : args.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                case "MIN":
                    return args.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                case "MAX":
                    return args.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                case "COUNT":
                    return args.size();
                case "ABS":
                    return Math.abs(single(function, args));
                case "SQRT":
                    return Math.sqrt(single(function, args));
                case "ROUND": {
                    double value = args.get(0);
                    int places = args.size() > 1 ? args.get(1).intValue() : 0;
                    double scale = Math.pow(10, places);
                    return Math.round(value * scale) / scale;
                }
                case "PRODUCT": {
                    double product = 1;
                    for (double v : args) {
                        product *= v;
                    }
                    return product;
                }
                case "PI":
                    return Math.PI;
                default:
                    throw new IllegalArgumentException("unknown function " + function);
            }
        }

        private double single(String function, List<Double> args) {
            if (args.size() != 1) {
                throw new IllegalArgumentException(function + " takes one argument");
            }
            return args.get(0);
        }
    }

    private record Range(int firstRow, int firstCol, int lastRow, int lastCol) {
    }
}
