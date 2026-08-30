package javaos.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javaos.office.TextDocument.Align;
import javaos.office.TextDocument.Format;
import javaos.office.TextDocument.Paragraph;
import javaos.office.TextDocument.Run;

/**
 * Office Open XML: Word documents (.docx) and Excel workbooks (.xlsx). Like
 * ODF these are ZIPs of XML, so the same two primitives cover both families.
 */
public final class Ooxml {

    private static final String CONTENT_TYPES =
            "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String PACKAGE_RELS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String DOCX_MAIN = "application/vnd.openxmlformats-officedocument"
            + ".wordprocessingml.document.main+xml";
    private static final String XLSX_MAIN = "application/vnd.openxmlformats-officedocument"
            + ".spreadsheetml.sheet.main+xml";
    private static final String XLSX_SHEET = "application/vnd.openxmlformats-officedocument"
            + ".spreadsheetml.worksheet+xml";
    private static final String RELS_TYPE =
            "application/vnd.openxmlformats-package.relationships+xml";

    private Ooxml() {
    }

    // ================= word documents =================

    public static TextDocument readText(Path file) throws IOException {
        Map<String, byte[]> parts = Zip.read(file);
        byte[] content = parts.get("word/document.xml");
        if (content == null) {
            throw new IOException("not a Word document: word/document.xml is missing");
        }
        Document dom = Xml.parse(content);
        Element body = Xml.firstDeep(dom, Xml.W, "body");
        if (body == null) {
            throw new IOException("the Word document has no body");
        }

        TextDocument document = new TextDocument();
        readBlocks(body, document);
        if (document.paragraphs().isEmpty()) {
            document.addParagraph();
        }
        return document;
    }

    private static void readBlocks(Node parent, TextDocument document) {
        for (Element element : Xml.children(parent)) {
            if (Xml.matches(element, Xml.W, "p")) {
                document.add(readParagraph(element));
            } else if (Xml.matches(element, Xml.W, "tbl")
                    || Xml.matches(element, Xml.W, "tr")
                    || Xml.matches(element, Xml.W, "tc")
                    || Xml.matches(element, Xml.W, "sdt")
                    || Xml.matches(element, Xml.W, "sdtContent")) {
                readBlocks(element, document);
            }
        }
    }

    private static Paragraph readParagraph(Element element) {
        Paragraph paragraph = new Paragraph();
        Element properties = Xml.first(element, Xml.W, "pPr");
        if (properties != null) {
            Element jc = Xml.first(properties, Xml.W, "jc");
            if (jc != null) {
                paragraph.setAlign(alignFrom(Xml.attr(jc, Xml.W, "val")));
            }
        }
        collectRuns(element, paragraph);
        return paragraph;
    }

    private static void collectRuns(Node node, Paragraph paragraph) {
        for (Element element : Xml.children(node)) {
            if (Xml.matches(element, Xml.W, "r")) {
                Format format = readRunFormat(Xml.first(element, Xml.W, "rPr"));
                for (Element piece : Xml.children(element)) {
                    if (Xml.matches(piece, Xml.W, "t")) {
                        paragraph.add(new Run(Xml.textOf(piece), format));
                    } else if (Xml.matches(piece, Xml.W, "tab")) {
                        paragraph.add(new Run("\t", format));
                    } else if (Xml.matches(piece, Xml.W, "br")
                            || Xml.matches(piece, Xml.W, "cr")) {
                        paragraph.add(new Run("\n", format));
                    }
                }
            } else if (Xml.matches(element, Xml.W, "hyperlink")
                    || Xml.matches(element, Xml.W, "smartTag")
                    || Xml.matches(element, Xml.W, "sdt")
                    || Xml.matches(element, Xml.W, "sdtContent")
                    || Xml.matches(element, Xml.W, "ins")) {
                collectRuns(element, paragraph);
            }
        }
    }

    private static Format readRunFormat(Element properties) {
        Format format = Format.PLAIN;
        if (properties == null) {
            return format;
        }
        if (toggle(properties, "b")) {
            format = format.withBold(true);
        }
        if (toggle(properties, "i")) {
            format = format.withItalic(true);
        }
        Element underline = Xml.first(properties, Xml.W, "u");
        if (underline != null && !"none".equals(Xml.attr(underline, Xml.W, "val"))) {
            format = format.withUnderline(true);
        }
        Element color = Xml.first(properties, Xml.W, "color");
        if (color != null) {
            String value = Xml.attr(color, Xml.W, "val");
            if (value != null && value.matches("[0-9A-Fa-f]{6}")) {
                format = format.withColor("#" + value.toUpperCase());
            }
        }
        Element size = Xml.first(properties, Xml.W, "sz");
        if (size != null) {
            // w:sz counts half-points.
            int halfPoints = Xml.intAttr(size, Xml.W, "val", 0);
            if (halfPoints > 0) {
                format = format.withSize(Math.max(1, halfPoints / 2));
            }
        }
        Element fonts = Xml.first(properties, Xml.W, "rFonts");
        if (fonts != null) {
            String font = Xml.attr(fonts, Xml.W, "ascii");
            if (font != null) {
                format = format.withFont(font);
            }
        }
        return format;
    }

    /** A toggle is on when present, unless it explicitly says {@code w:val="0"}. */
    private static boolean toggle(Element properties, String name) {
        Element element = Xml.first(properties, Xml.W, name);
        if (element == null) {
            return false;
        }
        String value = Xml.attr(element, Xml.W, "val");
        return value == null || !(value.equals("0") || value.equals("false"));
    }

    public static void writeText(TextDocument document, Path file) throws IOException {
        Xml.Builder xml = new Xml.Builder();
        xml.start("w:document").attr("xmlns:w", Xml.W);
        xml.start("w:body");
        for (Paragraph paragraph : document.paragraphs()) {
            xml.start("w:p");
            if (paragraph.align() != Align.LEFT) {
                xml.start("w:pPr")
                        .start("w:jc").attr("w:val", alignTo(paragraph.align())).end()
                        .end();
            }
            for (Run run : paragraph.runs()) {
                writeRun(xml, run);
            }
            xml.end();
        }
        // A section says how big the page is; readers cope without one, but
        // every real document has it and some are happier when it is there.
        xml.start("w:sectPr")
                .start("w:pgSz").attr("w:w", "12240").attr("w:h", "15840").end()
                .start("w:pgMar").attr("w:top", "1440").attr("w:right", "1440")
                .attr("w:bottom", "1440").attr("w:left", "1440").end()
                .end();
        xml.end().end();

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes(Map.of(
                "/word/document.xml", DOCX_MAIN)));
        parts.put("_rels/.rels", packageRels("word/document.xml"));
        parts.put("word/_rels/document.xml.rels", emptyRels());
        parts.put("word/document.xml", xml.bytes());
        Zip.write(file, parts, null);
    }

    private static void writeRun(Xml.Builder xml, Run run) {
        Format format = run.format();
        xml.start("w:r");
        if (!format.isPlain()) {
            xml.start("w:rPr");
            if (format.bold()) {
                xml.start("w:b").end();
            }
            if (format.italic()) {
                xml.start("w:i").end();
            }
            if (format.underline()) {
                xml.start("w:u").attr("w:val", "single").end();
            }
            if (format.font() != null) {
                xml.start("w:rFonts")
                        .attr("w:ascii", format.font())
                        .attr("w:hAnsi", format.font())
                        .end();
            }
            if (format.sizePt() > 0) {
                String halfPoints = String.valueOf(format.sizePt() * 2);
                xml.start("w:sz").attr("w:val", halfPoints).end();
                xml.start("w:szCs").attr("w:val", halfPoints).end();
            }
            if (format.color() != null) {
                xml.start("w:color")
                        .attr("w:val", format.color().replace("#", "").toUpperCase())
                        .end();
            }
            xml.end();
        }
        // Tabs and breaks are their own elements, so split the text around them.
        StringBuilder pending = new StringBuilder();
        for (int i = 0; i < run.text().length(); i++) {
            char ch = run.text().charAt(i);
            if (ch == '\t' || ch == '\n') {
                flushText(xml, pending);
                xml.start(ch == '\t' ? "w:tab" : "w:br").end();
            } else {
                pending.append(ch);
            }
        }
        flushText(xml, pending);
        xml.end();
    }

    private static void flushText(Xml.Builder xml, StringBuilder pending) {
        if (pending.length() > 0) {
            xml.start("w:t").attr("xml:space", "preserve").text(pending.toString()).end();
            pending.setLength(0);
        }
    }

    private static Align alignFrom(String value) {
        if (value == null) {
            return Align.LEFT;
        }
        return switch (value) {
            case "center" -> Align.CENTER;
            case "right", "end" -> Align.RIGHT;
            case "both", "distribute" -> Align.JUSTIFY;
            default -> Align.LEFT;
        };
    }

    private static String alignTo(Align align) {
        return switch (align) {
            case CENTER -> "center";
            case RIGHT -> "right";
            case JUSTIFY -> "both";
            default -> "left";
        };
    }

    // ================= workbooks =================

    public static SheetDocument readSheet(Path file) throws IOException {
        Map<String, byte[]> parts = Zip.read(file);
        List<String> sharedStrings = readSharedStrings(parts);

        String sheetName = "Sheet1";
        String sheetPart = null;
        byte[] workbook = parts.get("xl/workbook.xml");
        if (workbook != null) {
            Document dom = Xml.parse(workbook);
            Element sheet = Xml.firstDeep(dom, Xml.SHEET, "sheet");
            if (sheet != null) {
                sheetName = Xml.attr(sheet, Xml.SHEET, "name", sheetName);
                String id = Xml.attr(sheet, Xml.REL, "id");
                sheetPart = resolveSheetPart(parts, id);
            }
        }
        if (sheetPart == null || !parts.containsKey(sheetPart)) {
            sheetPart = parts.keySet().stream()
                    .filter(name -> name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
        if (sheetPart == null) {
            throw new IOException("not a workbook: no worksheet part was found");
        }

        SheetDocument sheet = new SheetDocument();
        sheet.setName(sheetName);
        Document dom = Xml.parse(parts.get(sheetPart));
        Element sheetData = Xml.firstDeep(dom, Xml.SHEET, "sheetData");
        if (sheetData == null) {
            return sheet;
        }

        int fallbackRow = 0;
        for (Element rowElement : Xml.children(sheetData)) {
            if (!Xml.matches(rowElement, Xml.SHEET, "row")) {
                continue;
            }
            int row = Xml.intAttr(rowElement, Xml.SHEET, "r", fallbackRow + 1) - 1;
            fallbackRow = row;
            if (row < 0 || row >= SheetDocument.MAX_ROWS) {
                continue;
            }
            int fallbackColumn = 0;
            for (Element cell : Xml.children(rowElement)) {
                if (!Xml.matches(cell, Xml.SHEET, "c")) {
                    continue;
                }
                String reference = Xml.attr(cell, Xml.SHEET, "r");
                int column = fallbackColumn;
                if (reference != null) {
                    int[] position = SheetDocument.parseReference(reference);
                    if (position != null) {
                        column = position[1];
                    }
                }
                fallbackColumn = column + 1;
                if (column < 0 || column >= SheetDocument.MAX_COLUMNS) {
                    continue;
                }
                readCell(cell, sheet, row, column, sharedStrings);
            }
        }
        sheet.trim();
        return sheet;
    }

    private static void readCell(Element cell, SheetDocument sheet, int row, int column,
            List<String> sharedStrings) {
        String type = Xml.attr(cell, Xml.SHEET, "t", "n");
        Element formula = Xml.first(cell, Xml.SHEET, "f");
        Element value = Xml.first(cell, Xml.SHEET, "v");
        String cached = value == null ? null : Xml.textOf(value);

        if (formula != null) {
            String text = Xml.textOf(formula).trim();
            if (!text.isEmpty()) {
                sheet.set(row, column, "=" + text);
                if (cached != null && !cached.isEmpty()) {
                    sheet.setCachedValue(row, column, "s".equals(type)
                            ? sharedString(sharedStrings, cached) : cached);
                }
                return;
            }
        }

        String content = switch (type) {
            case "s" -> cached == null ? "" : sharedString(sharedStrings, cached);
            case "inlineStr" -> {
                Element inline = Xml.first(cell, Xml.SHEET, "is");
                yield inline == null ? "" : Xml.textOf(inline);
            }
            case "b" -> "1".equals(cached) ? "TRUE" : "FALSE";
            case "str" -> cached == null ? "" : cached;
            default -> {
                if (cached == null) {
                    yield "";
                }
                Double number = SheetDocument.asNumber(cached);
                yield number == null ? cached : SheetDocument.plainNumber(number);
            }
        };
        if (!content.isEmpty()) {
            sheet.set(row, column, content);
        }
    }

    private static String sharedString(List<String> sharedStrings, String index) {
        try {
            int at = Integer.parseInt(index.trim());
            return at >= 0 && at < sharedStrings.size() ? sharedStrings.get(at) : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static List<String> readSharedStrings(Map<String, byte[]> parts) throws IOException {
        List<String> strings = new ArrayList<>();
        byte[] content = parts.get("xl/sharedStrings.xml");
        if (content == null) {
            return strings;
        }
        Document dom = Xml.parse(content);
        Element table = Xml.firstDeep(dom, Xml.SHEET, "sst");
        if (table == null) {
            return strings;
        }
        for (Element item : Xml.children(table)) {
            if (Xml.matches(item, Xml.SHEET, "si")) {
                // A shared string may be one <t>, or several formatted <r> runs.
                strings.add(Xml.textOf(item));
            }
        }
        return strings;
    }

    private static String resolveSheetPart(Map<String, byte[]> parts, String relationshipId)
            throws IOException {
        byte[] content = parts.get("xl/_rels/workbook.xml.rels");
        if (content == null || relationshipId == null) {
            return null;
        }
        Document dom = Xml.parse(content);
        for (Element relationship : Xml.descendants(dom, PACKAGE_RELS, "Relationship")) {
            if (relationshipId.equals(Xml.attr(relationship, null, "Id"))) {
                String target = Xml.attr(relationship, null, "Target");
                if (target == null) {
                    return null;
                }
                return target.startsWith("/") ? target.substring(1) : "xl/" + target;
            }
        }
        return null;
    }

    public static void writeSheet(SheetDocument sheet, Path file) throws IOException {
        Xml.Builder xml = new Xml.Builder();
        xml.start("worksheet").attr("xmlns", Xml.SHEET);
        xml.start("sheetData");
        int rows = sheet.rowCount();
        int columns = sheet.columnCount();
        for (int row = 0; row < rows; row++) {
            boolean rowStarted = false;
            for (int column = 0; column < columns; column++) {
                String raw = sheet.get(row, column);
                if (raw.isEmpty()) {
                    continue;
                }
                if (!rowStarted) {
                    xml.start("row").attr("r", String.valueOf(row + 1));
                    rowStarted = true;
                }
                String reference = SheetDocument.columnName(column) + (row + 1);
                if (raw.startsWith("=")) {
                    String cached = sheet.cachedValue(row, column);
                    Double number = SheetDocument.asNumber(cached);
                    xml.start("c").attr("r", reference);
                    if (number == null && !cached.isEmpty()) {
                        xml.attr("t", "str");
                    }
                    xml.element("f", raw.substring(1));
                    if (!cached.isEmpty()) {
                        xml.element("v", number == null
                                ? cached : SheetDocument.plainNumber(number));
                    }
                    xml.end();
                } else {
                    Double number = SheetDocument.asNumber(raw);
                    if (number != null) {
                        xml.start("c").attr("r", reference)
                                .element("v", SheetDocument.plainNumber(number))
                                .end();
                    } else {
                        // Inline strings keep the workbook to a single part.
                        xml.start("c").attr("r", reference).attr("t", "inlineStr")
                                .start("is").element("t", raw).end()
                                .end();
                    }
                }
            }
            if (rowStarted) {
                xml.end();
            }
        }
        xml.end().end();

        Xml.Builder workbook = new Xml.Builder();
        workbook.start("workbook").attr("xmlns", Xml.SHEET).attr("xmlns:r", Xml.REL)
                .start("sheets")
                .start("sheet")
                .attr("name", sheet.name())
                .attr("sheetId", "1")
                .attr("r:id", "rId1")
                .end()
                .end()
                .end();

        Xml.Builder rels = new Xml.Builder();
        rels.start("Relationships").attr("xmlns", PACKAGE_RELS)
                .start("Relationship")
                .attr("Id", "rId1")
                .attr("Type", "http://schemas.openxmlformats.org/officeDocument/2006"
                        + "/relationships/worksheet")
                .attr("Target", "worksheets/sheet1.xml")
                .end()
                .end();

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes(new LinkedHashMap<>(Map.of(
                "/xl/workbook.xml", XLSX_MAIN,
                "/xl/worksheets/sheet1.xml", XLSX_SHEET))));
        parts.put("_rels/.rels", packageRels("xl/workbook.xml"));
        parts.put("xl/workbook.xml", workbook.bytes());
        parts.put("xl/_rels/workbook.xml.rels", rels.bytes());
        parts.put("xl/worksheets/sheet1.xml", xml.bytes());
        Zip.write(file, parts, null);
    }

    // ================= package parts =================

    private static byte[] contentTypes(Map<String, String> overrides) {
        Xml.Builder xml = new Xml.Builder();
        xml.start("Types").attr("xmlns", CONTENT_TYPES);
        xml.start("Default").attr("Extension", "rels").attr("ContentType", RELS_TYPE).end();
        xml.start("Default").attr("Extension", "xml").attr("ContentType", "application/xml")
                .end();
        for (Map.Entry<String, String> override : overrides.entrySet()) {
            xml.start("Override")
                    .attr("PartName", override.getKey())
                    .attr("ContentType", override.getValue())
                    .end();
        }
        xml.end();
        return xml.bytes();
    }

    private static byte[] packageRels(String target) {
        Xml.Builder xml = new Xml.Builder();
        xml.start("Relationships").attr("xmlns", PACKAGE_RELS)
                .start("Relationship")
                .attr("Id", "rId1")
                .attr("Type", "http://schemas.openxmlformats.org/officeDocument/2006"
                        + "/relationships/officeDocument")
                .attr("Target", target)
                .end()
                .end();
        return xml.bytes();
    }

    private static byte[] emptyRels() {
        Xml.Builder xml = new Xml.Builder();
        xml.start("Relationships").attr("xmlns", PACKAGE_RELS).end();
        return xml.bytes();
    }
}
