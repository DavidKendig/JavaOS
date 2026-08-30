package javaos.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The front door to the office engine: pick a handler by file extension.
 *
 * <p>Everything here is pure Java on top of the JDK. JavaOS reads and writes
 * these formats whether or not LibreOffice, or anything else, is installed.
 */
public final class OfficeFormats {

    /** Word-processor formats JavaOS reads and writes itself. */
    public static final List<String> TEXT_FORMATS = List.of("odt", "docx");

    /** Spreadsheet formats JavaOS reads and writes itself. */
    public static final List<String> SHEET_FORMATS = List.of("ods", "xlsx");

    private OfficeFormats() {
    }

    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isTextFormat(String extension) {
        return TEXT_FORMATS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static boolean isSheetFormat(String extension) {
        return SHEET_FORMATS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static TextDocument readText(Path file) throws IOException {
        checkPackage(file);
        return switch (extensionOf(file)) {
            case "odt" -> Odf.readText(file);
            case "docx" -> Ooxml.readText(file);
            default -> throw new IOException(
                    "JavaOS does not read " + extensionOf(file) + " documents");
        };
    }

    public static void writeText(TextDocument document, Path file) throws IOException {
        switch (extensionOf(file)) {
            case "odt" -> Odf.writeText(document, file);
            case "docx" -> Ooxml.writeText(document, file);
            default -> throw new IOException(
                    "JavaOS does not write " + extensionOf(file) + " documents");
        }
    }

    public static SheetDocument readSheet(Path file) throws IOException {
        checkPackage(file);
        return switch (extensionOf(file)) {
            case "ods" -> Odf.readSheet(file);
            case "xlsx" -> Ooxml.readSheet(file);
            default -> throw new IOException(
                    "JavaOS does not read " + extensionOf(file) + " spreadsheets");
        };
    }

    public static void writeSheet(SheetDocument sheet, Path file) throws IOException {
        switch (extensionOf(file)) {
            case "ods" -> Odf.writeSheet(sheet, file);
            case "xlsx" -> Ooxml.writeSheet(sheet, file);
            default -> throw new IOException(
                    "JavaOS does not write " + extensionOf(file) + " spreadsheets");
        }
    }

    /**
     * Both families are ZIPs. Catching a non-package early gives a better message
     * than a stack trace out of the zip reader, and catches the common case of a
     * pre-2007 .doc or .xls wearing a modern extension.
     */
    private static void checkPackage(Path file) throws IOException {
        if (!Zip.isZip(file)) {
            throw new IOException(file.getFileName() + " is not an office package.\n"
                    + "Pre-2007 binary formats such as .doc and .xls are not supported.");
        }
    }

    /** A human label for a format, for status bars and file dialogs. */
    public static String describe(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "odt" -> "OpenDocument Text";
            case "ods" -> "OpenDocument Spreadsheet";
            case "docx" -> "Word Document";
            case "xlsx" -> "Excel Workbook";
            default -> extension.toUpperCase(Locale.ROOT) + " file";
        };
    }
}
