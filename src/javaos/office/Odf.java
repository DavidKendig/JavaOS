package javaos.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javaos.office.TextDocument.Align;
import javaos.office.TextDocument.Format;
import javaos.office.TextDocument.Paragraph;
import javaos.office.TextDocument.Run;

/**
 * OpenDocument text (.odt) and spreadsheets (.ods), read and written directly.
 * An ODF file is a ZIP holding content.xml, so nothing here needs more than the
 * JDK's zip and XML support.
 */
public final class Odf {

    public static final String TEXT_MIME = "application/vnd.oasis.opendocument.text";
    public static final String SHEET_MIME = "application/vnd.oasis.opendocument.spreadsheet";

    private static final String VERSION = "1.3";

    private Odf() {
    }

    // ================= text documents =================

    public static TextDocument readText(Path file) throws IOException {
        Map<String, byte[]> parts = Zip.read(file);
        byte[] content = parts.get("content.xml");
        if (content == null) {
            throw new IOException("not an OpenDocument file: content.xml is missing");
        }
        Document dom = Xml.parse(content);
        Map<String, StyleInfo> styles = readStyles(dom);
        if (parts.containsKey("styles.xml")) {
            styles.putAll(readStyles(Xml.parse(parts.get("styles.xml"))));
        }

        TextDocument document = new TextDocument();
        Element body = Xml.firstDeep(dom, Xml.OFFICE, "body");
        Element text = body == null ? null : Xml.first(body, Xml.OFFICE, "text");
        if (text == null) {
            throw new IOException("this OpenDocument file is not a text document");
        }
        readBlocks(text, document, styles);
        if (document.paragraphs().isEmpty()) {
            document.addParagraph();
        }
        return document;
    }

    /** Paragraphs, headings, lists and tables all reduce to a run of paragraphs. */
    private static void readBlocks(Node parent, TextDocument document,
            Map<String, StyleInfo> styles) {
        for (Element element : Xml.children(parent)) {
            if (Xml.matches(element, Xml.TEXT, "p") || Xml.matches(element, Xml.TEXT, "h")) {
                document.add(readParagraph(element, styles));
            } else if (Xml.matches(element, Xml.TEXT, "list")
                    || Xml.matches(element, Xml.TEXT, "list-item")
                    || Xml.matches(element, Xml.TEXT, "section")
                    || Xml.matches(element, Xml.TABLE, "table")
                    || Xml.matches(element, Xml.TABLE, "table-row")
                    || Xml.matches(element, Xml.TABLE, "table-cell")) {
                readBlocks(element, document, styles);
            }
        }
    }

    private static Paragraph readParagraph(Element element, Map<String, StyleInfo> styles) {
        Paragraph paragraph = new Paragraph();
        String styleName = Xml.attr(element, Xml.TEXT, "style-name");
        Resolved resolved = resolve(styleName, styles);
        paragraph.setAlign(resolved.align);
        collectRuns(element, resolved.format, paragraph, styles);
        return paragraph;
    }

    private static void collectRuns(Node node, Format inherited, Paragraph paragraph,
            Map<String, StyleInfo> styles) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                paragraph.add(new Run(child.getNodeValue(), inherited));
                continue;
            }
            if (!(child instanceof Element element)) {
                continue;
            }
            if (Xml.matches(element, Xml.TEXT, "s")) {
                int count = Xml.intAttr(element, Xml.TEXT, "c", 1);
                paragraph.add(new Run(" ".repeat(Math.max(1, Math.min(count, 1000))),
                        inherited));
            } else if (Xml.matches(element, Xml.TEXT, "tab")) {
                paragraph.add(new Run("\t", inherited));
            } else if (Xml.matches(element, Xml.TEXT, "line-break")) {
                paragraph.add(new Run("\n", inherited));
            } else if (Xml.matches(element, Xml.TEXT, "span")) {
                Resolved span = resolve(Xml.attr(element, Xml.TEXT, "style-name"), styles);
                collectRuns(element, merge(inherited, span.format), paragraph, styles);
            } else if (Xml.matches(element, Xml.TEXT, "note")
                    || Xml.matches(element, Xml.TEXT, "tracked-changes")) {
                // Footnote bodies and revision history are not part of the text flow.
                continue;
            } else {
                collectRuns(element, inherited, paragraph, styles);
            }
        }
    }

    public static void writeText(TextDocument document, Path file) throws IOException {
        // Collect the distinct formats first; ODF names every one of them.
        Map<Align, String> paragraphStyles = new LinkedHashMap<>();
        Map<Format, String> runStyles = new LinkedHashMap<>();
        for (Paragraph paragraph : document.paragraphs()) {
            paragraphStyles.computeIfAbsent(paragraph.align(),
                    a -> "P" + paragraphStyles.size());
            for (Run run : paragraph.runs()) {
                if (!run.format().isPlain()) {
                    runStyles.computeIfAbsent(run.format(), f -> "T" + runStyles.size());
                }
            }
        }

        Xml.Builder xml = new Xml.Builder();
        xml.start("office:document-content")
                .attr("xmlns:office", Xml.OFFICE)
                .attr("xmlns:text", Xml.TEXT)
                .attr("xmlns:style", Xml.STYLE)
                .attr("xmlns:fo", Xml.FO)
                .attr("office:version", VERSION);

        xml.start("office:automatic-styles");
        for (Map.Entry<Align, String> entry : paragraphStyles.entrySet()) {
            xml.start("style:style")
                    .attr("style:name", entry.getValue())
                    .attr("style:family", "paragraph")
                    .attr("style:parent-style-name", "Standard");
            xml.start("style:paragraph-properties")
                    .attr("fo:text-align", alignToOdf(entry.getKey()))
                    .attr("style:justify-single-word", "false")
                    .end();
            xml.end();
        }
        for (Map.Entry<Format, String> entry : runStyles.entrySet()) {
            Format format = entry.getKey();
            xml.start("style:style")
                    .attr("style:name", entry.getValue())
                    .attr("style:family", "text");
            xml.start("style:text-properties");
            if (format.bold()) {
                xml.attr("fo:font-weight", "bold").attr("style:font-weight-asian", "bold");
            }
            if (format.italic()) {
                xml.attr("fo:font-style", "italic").attr("style:font-style-asian", "italic");
            }
            if (format.underline()) {
                xml.attr("style:text-underline-style", "solid")
                        .attr("style:text-underline-width", "auto")
                        .attr("style:text-underline-color", "font-color");
            }
            if (format.font() != null) {
                xml.attr("style:font-name", format.font());
            }
            if (format.sizePt() > 0) {
                xml.attr("fo:font-size", format.sizePt() + "pt");
            }
            if (format.color() != null) {
                xml.attr("fo:color", format.color());
            }
            xml.end().end();
        }
        xml.end();

        xml.start("office:body").start("office:text");
        for (Paragraph paragraph : document.paragraphs()) {
            xml.start("text:p").attr("text:style-name", paragraphStyles.get(paragraph.align()));
            for (Run run : paragraph.runs()) {
                String styleName = runStyles.get(run.format());
                if (styleName == null) {
                    xml.raw(encodeText(run.text()));
                } else {
                    xml.start("text:span").attr("text:style-name", styleName)
                            .raw(encodeText(run.text())).end();
                }
            }
            xml.end();
        }
        xml.end().end().end();

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("mimetype", Zip.bytes(TEXT_MIME));
        parts.put("META-INF/manifest.xml", manifest(TEXT_MIME));
        parts.put("meta.xml", meta());
        parts.put("styles.xml", emptyStyles());
        parts.put("content.xml", xml.bytes());
        Zip.write(file, parts, "mimetype");
    }

    /**
     * ODF collapses runs of whitespace, so spaces, tabs and breaks travel as
     * their own elements rather than as literal characters.
     */
    private static String encodeText(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                int run = 0;
                while (i + run < text.length() && text.charAt(i + run) == ' ') {
                    run++;
                }
                if (run == 1) {
                    out.append(' ');
                } else {
                    // The first space stays literal; the rest become one text:s.
                    out.append(' ').append("<text:s text:c=\"").append(run - 1).append("\"/>");
                }
                i += run;
            } else if (ch == '\t') {
                out.append("<text:tab/>");
                i++;
            } else if (ch == '\n') {
                out.append("<text:line-break/>");
                i++;
            } else {
                out.append(Xml.escape(String.valueOf(ch), false));
                i++;
            }
        }
        return out.toString();
    }

    // ================= spreadsheets =================

    public static SheetDocument readSheet(Path file) throws IOException {
        Map<String, byte[]> parts = Zip.read(file);
        byte[] content = parts.get("content.xml");
        if (content == null) {
            throw new IOException("not an OpenDocument file: content.xml is missing");
        }
        Document dom = Xml.parse(content);
        Element body = Xml.firstDeep(dom, Xml.OFFICE, "body");
        Element spreadsheet = body == null ? null : Xml.first(body, Xml.OFFICE, "spreadsheet");
        if (spreadsheet == null) {
            throw new IOException("this OpenDocument file is not a spreadsheet");
        }
        Element table = Xml.first(spreadsheet, Xml.TABLE, "table");
        if (table == null) {
            throw new IOException("the spreadsheet has no sheets");
        }

        SheetDocument sheet = new SheetDocument();
        sheet.setName(Xml.attr(table, Xml.TABLE, "name", "Sheet1"));

        int row = 0;
        for (Element rowElement : Xml.children(table)) {
            if (!Xml.matches(rowElement, Xml.TABLE, "table-row")) {
                continue;
            }
            int repeatRows = Xml.intAttr(rowElement, Xml.TABLE, "number-rows-repeated", 1);
            // A trailing row repeated a million times carries no content; only
            // repeat rows that actually hold something.
            List<Element> cells = Xml.children(rowElement);
            boolean rowHasContent = false;
            for (Element cell : cells) {
                if (!Xml.textOf(cell).isBlank()
                        || Xml.attr(cell, Xml.TABLE, "formula") != null
                        || Xml.attr(cell, Xml.OFFICE, "value") != null) {
                    rowHasContent = true;
                    break;
                }
            }
            if (!rowHasContent) {
                row += Math.min(repeatRows, 2);
                if (row >= SheetDocument.MAX_ROWS) {
                    break;
                }
                continue;
            }
            for (int copy = 0; copy < Math.min(repeatRows, 64); copy++) {
                readRow(cells, sheet, row);
                row++;
                if (row >= SheetDocument.MAX_ROWS) {
                    break;
                }
            }
            if (row >= SheetDocument.MAX_ROWS) {
                break;
            }
        }
        sheet.trim();
        return sheet;
    }

    private static void readRow(List<Element> cells, SheetDocument sheet, int row) {
        int column = 0;
        for (Element cell : cells) {
            if (!Xml.matches(cell, Xml.TABLE, "table-cell")
                    && !Xml.matches(cell, Xml.TABLE, "covered-table-cell")) {
                continue;
            }
            int repeat = Xml.intAttr(cell, Xml.TABLE, "number-columns-repeated", 1);
            String formula = Xml.attr(cell, Xml.TABLE, "formula");
            String valueType = Xml.attr(cell, Xml.OFFICE, "value-type");
            String value = Xml.attr(cell, Xml.OFFICE, "value");
            String display = Xml.textOf(cell);

            String raw;
            String cached = null;
            if (formula != null) {
                raw = fromOdfFormula(formula);
                cached = value != null ? value : (display.isBlank() ? null : display);
            } else if ("float".equals(valueType) || "percentage".equals(valueType)
                    || "currency".equals(valueType)) {
                raw = value != null ? normalizeNumber(value) : display;
            } else if ("boolean".equals(valueType)) {
                raw = Xml.attr(cell, Xml.OFFICE, "boolean-value", display);
            } else if ("date".equals(valueType)) {
                raw = Xml.attr(cell, Xml.OFFICE, "date-value", display);
            } else {
                raw = display;
            }

            if (raw != null && !raw.isEmpty()) {
                for (int copy = 0; copy < Math.min(repeat, 256); copy++) {
                    if (column + copy >= SheetDocument.MAX_COLUMNS) {
                        break;
                    }
                    sheet.set(row, column + copy, raw);
                    if (cached != null) {
                        sheet.setCachedValue(row, column + copy, cached);
                    }
                }
            }
            column += repeat;
            if (column >= SheetDocument.MAX_COLUMNS) {
                break;
            }
        }
    }

    private static String normalizeNumber(String value) {
        Double number = SheetDocument.asNumber(value);
        return number == null ? value : SheetDocument.plainNumber(number);
    }

    public static void writeSheet(SheetDocument sheet, Path file) throws IOException {
        int rows = sheet.rowCount();
        int columns = Math.max(sheet.columnCount(), 1);

        Xml.Builder xml = new Xml.Builder();
        xml.start("office:document-content")
                .attr("xmlns:office", Xml.OFFICE)
                .attr("xmlns:table", Xml.TABLE)
                .attr("xmlns:text", Xml.TEXT)
                .attr("xmlns:style", Xml.STYLE)
                .attr("xmlns:fo", Xml.FO)
                .attr("xmlns:of", Xml.OF)
                .attr("office:version", VERSION);
        xml.start("office:body").start("office:spreadsheet");
        xml.start("table:table").attr("table:name", sheet.name());
        xml.start("table:table-column")
                .attr("table:number-columns-repeated", String.valueOf(columns)).end();

        for (int row = 0; row < rows; row++) {
            xml.start("table:table-row");
            for (int column = 0; column < columns; column++) {
                String raw = sheet.get(row, column);
                if (raw.isEmpty()) {
                    xml.start("table:table-cell").end();
                    continue;
                }
                xml.start("table:table-cell");
                if (raw.startsWith("=")) {
                    xml.attr("table:formula", toOdfFormula(raw));
                    String cached = sheet.cachedValue(row, column);
                    Double number = SheetDocument.asNumber(cached);
                    if (number != null) {
                        xml.attr("office:value-type", "float")
                                .attr("office:value", SheetDocument.plainNumber(number));
                    } else {
                        xml.attr("office:value-type", "string");
                    }
                    if (!cached.isEmpty()) {
                        xml.start("text:p").text(cached).end();
                    }
                } else {
                    Double number = SheetDocument.asNumber(raw);
                    if (number != null) {
                        xml.attr("office:value-type", "float")
                                .attr("office:value", SheetDocument.plainNumber(number));
                        xml.start("text:p").text(raw).end();
                    } else {
                        xml.attr("office:value-type", "string");
                        xml.start("text:p").text(raw).end();
                    }
                }
                xml.end();
            }
            xml.end();
        }
        xml.end().end().end().end();

        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("mimetype", Zip.bytes(SHEET_MIME));
        parts.put("META-INF/manifest.xml", manifest(SHEET_MIME));
        parts.put("meta.xml", meta());
        parts.put("styles.xml", emptyStyles());
        parts.put("content.xml", xml.bytes());
        Zip.write(file, parts, "mimetype");
    }

    // ================= formulas =================

    private static final Pattern REFERENCE = Pattern.compile(
            "(\\$?[A-Za-z]{1,3}\\$?[0-9]+)(?::(\\$?[A-Za-z]{1,3}\\$?[0-9]+))?");

    /** {@code =SUM(A1:A3)} becomes {@code of:=SUM([.A1:.A3])}. */
    static String toOdfFormula(String formula) {
        String body = formula.substring(1);
        StringBuilder out = new StringBuilder("of:=");
        Matcher matcher = REFERENCE.matcher(body);
        int at = 0;
        while (matcher.find()) {
            // LOG10( and similar are function names, not references.
            int after = matcher.end();
            if (after < body.length() && body.charAt(after) == '(') {
                continue;
            }
            out.append(separators(body.substring(at, matcher.start())));
            if (matcher.group(2) == null) {
                out.append("[.").append(matcher.group(1).replace("$", "")).append(']');
            } else {
                out.append("[.").append(matcher.group(1).replace("$", ""))
                        .append(":.").append(matcher.group(2).replace("$", "")).append(']');
            }
            at = after;
        }
        out.append(separators(body.substring(at)));
        return out.toString();
    }

    /** ODF separates arguments with semicolons. */
    private static String separators(String fragment) {
        return fragment.replace(',', ';');
    }

    /** {@code of:=SUM([.A1:.A3])} becomes {@code =SUM(A1:A3)}. */
    static String fromOdfFormula(String formula) {
        String body = formula;
        if (body.startsWith("of:")) {
            body = body.substring(3);
        }
        if (body.startsWith("=")) {
            body = body.substring(1);
        }
        StringBuilder out = new StringBuilder("=");
        int i = 0;
        while (i < body.length()) {
            char ch = body.charAt(i);
            if (ch == '[') {
                int close = body.indexOf(']', i);
                if (close < 0) {
                    out.append(body.substring(i));
                    break;
                }
                String reference = body.substring(i + 1, close);
                // Strip any sheet qualifier and the leading dot from each part.
                String[] parts = reference.split(":");
                for (int p = 0; p < parts.length; p++) {
                    String cell = parts[p];
                    int dot = cell.lastIndexOf('.');
                    if (dot >= 0) {
                        cell = cell.substring(dot + 1);
                    }
                    out.append(p > 0 ? ":" : "").append(cell.replace("$", ""));
                }
                i = close + 1;
            } else if (ch == ';') {
                out.append(',');
                i++;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    // ================= styles =================

    /** The parts of a style that were actually specified; anything null inherits. */
    private static final class StyleInfo {
        String parent;
        Boolean bold;
        Boolean italic;
        Boolean underline;
        String font;
        Integer size;
        String color;
        Align align;
    }

    private record Resolved(Format format, Align align) {
    }

    private static Map<String, StyleInfo> readStyles(Document dom) {
        Map<String, StyleInfo> styles = new HashMap<>();
        for (Element style : Xml.descendants(dom, Xml.STYLE, "style")) {
            String name = Xml.attr(style, Xml.STYLE, "name");
            if (name == null) {
                continue;
            }
            StyleInfo info = new StyleInfo();
            info.parent = Xml.attr(style, Xml.STYLE, "parent-style-name");

            Element text = Xml.first(style, Xml.STYLE, "text-properties");
            if (text != null) {
                String weight = Xml.attr(text, Xml.FO, "font-weight");
                if (weight != null) {
                    info.bold = weight.equals("bold") || weight.matches("[6-9]00");
                }
                String posture = Xml.attr(text, Xml.FO, "font-style");
                if (posture != null) {
                    info.italic = posture.equals("italic") || posture.equals("oblique");
                }
                String underline = Xml.attr(text, Xml.STYLE, "text-underline-style");
                if (underline != null) {
                    info.underline = !underline.equals("none");
                }
                String font = Xml.attr(text, Xml.STYLE, "font-name");
                if (font == null) {
                    font = Xml.attr(text, Xml.FO, "font-family");
                }
                info.font = font;
                String size = Xml.attr(text, Xml.FO, "font-size");
                if (size != null && size.endsWith("pt")) {
                    try {
                        info.size = (int) Math.round(
                                Double.parseDouble(size.substring(0, size.length() - 2)));
                    } catch (NumberFormatException e) {
                        info.size = null;
                    }
                }
                String color = Xml.attr(text, Xml.FO, "color");
                if (color != null && color.startsWith("#")) {
                    info.color = color;
                }
            }

            Element paragraph = Xml.first(style, Xml.STYLE, "paragraph-properties");
            if (paragraph != null) {
                info.align = alignFromOdf(Xml.attr(paragraph, Xml.FO, "text-align"));
            }
            styles.put(name, info);
        }
        return styles;
    }

    private static Resolved resolve(String styleName, Map<String, StyleInfo> styles) {
        Format format = Format.PLAIN;
        Align align = Align.LEFT;
        // Walk to the root of the parent chain, then apply back down.
        java.util.ArrayDeque<StyleInfo> chain = new java.util.ArrayDeque<>();
        String name = styleName;
        int guard = 0;
        while (name != null && guard++ < 16) {
            StyleInfo info = styles.get(name);
            if (info == null) {
                break;
            }
            chain.push(info);
            name = info.parent;
        }
        for (StyleInfo info : chain) {
            if (info.bold != null) {
                format = format.withBold(info.bold);
            }
            if (info.italic != null) {
                format = format.withItalic(info.italic);
            }
            if (info.underline != null) {
                format = format.withUnderline(info.underline);
            }
            if (info.font != null) {
                format = format.withFont(info.font);
            }
            if (info.size != null) {
                format = format.withSize(info.size);
            }
            if (info.color != null) {
                format = format.withColor(info.color);
            }
            if (info.align != null) {
                align = info.align;
            }
        }
        return new Resolved(format, align);
    }

    /** A span's own style wins over whatever it inherits from its paragraph. */
    private static Format merge(Format inherited, Format own) {
        Format merged = inherited;
        if (own.bold()) {
            merged = merged.withBold(true);
        }
        if (own.italic()) {
            merged = merged.withItalic(true);
        }
        if (own.underline()) {
            merged = merged.withUnderline(true);
        }
        if (own.font() != null) {
            merged = merged.withFont(own.font());
        }
        if (own.sizePt() > 0) {
            merged = merged.withSize(own.sizePt());
        }
        if (own.color() != null) {
            merged = merged.withColor(own.color());
        }
        return merged;
    }

    private static Align alignFromOdf(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "center" -> Align.CENTER;
            case "end", "right" -> Align.RIGHT;
            case "justify" -> Align.JUSTIFY;
            case "start", "left" -> Align.LEFT;
            default -> null;
        };
    }

    private static String alignToOdf(Align align) {
        return switch (align) {
            case CENTER -> "center";
            case RIGHT -> "end";
            case JUSTIFY -> "justify";
            default -> "start";
        };
    }

    // ================= package parts =================

    private static byte[] manifest(String mime) {
        Xml.Builder xml = new Xml.Builder();
        xml.start("manifest:manifest")
                .attr("xmlns:manifest", Xml.MANIFEST)
                .attr("manifest:version", VERSION);
        entry(xml, "/", mime);
        entry(xml, "content.xml", "text/xml");
        entry(xml, "styles.xml", "text/xml");
        entry(xml, "meta.xml", "text/xml");
        xml.end();
        return xml.bytes();
    }

    private static void entry(Xml.Builder xml, String path, String mime) {
        xml.start("manifest:file-entry")
                .attr("manifest:full-path", path)
                .attr("manifest:media-type", mime)
                .end();
    }

    private static byte[] meta() {
        Xml.Builder xml = new Xml.Builder();
        xml.start("office:document-meta")
                .attr("xmlns:office", Xml.OFFICE)
                .attr("xmlns:meta", "urn:oasis:names:tc:opendocument:xmlns:meta:1.0")
                .attr("office:version", VERSION);
        xml.start("office:meta")
                .element("meta:generator", javaos.Version.GENERATOR)
                .end();
        xml.end();
        return xml.bytes();
    }

    private static byte[] emptyStyles() {
        Xml.Builder xml = new Xml.Builder();
        xml.start("office:document-styles")
                .attr("xmlns:office", Xml.OFFICE)
                .attr("xmlns:style", Xml.STYLE)
                .attr("xmlns:fo", Xml.FO)
                .attr("xmlns:text", Xml.TEXT)
                .attr("office:version", VERSION);
        xml.start("office:styles")
                .start("style:style")
                .attr("style:name", "Standard")
                .attr("style:family", "paragraph")
                .end()
                .end();
        xml.start("office:automatic-styles").end();
        xml.start("office:master-styles").end();
        xml.end();
        return xml.bytes();
    }
}
