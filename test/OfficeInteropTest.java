import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javaos.office.OfficeFormats;
import javaos.office.SheetDocument;
import javaos.office.TextDocument;
import javaos.office.TextDocument.Align;
import javaos.office.TextDocument.Format;
import javaos.office.TextDocument.Paragraph;
import javaos.office.TextDocument.Run;
import javaos.office.Zip;
import javaos.soffice.LibreOffice;

/**
 * Interop test: JavaOS writes an office file, real LibreOffice converts it to the
 * other family, and JavaOS reads LibreOffice's output back. That exercises the
 * writer and the reader against an implementation neither of them controls.
 */
public final class OfficeInteropTest {

    private static int failures;
    private static Path work;

    public static void main(String[] args) throws Exception {
        work = Files.createTempDirectory("javaos-office");
        System.out.println("work dir: " + work);
        System.out.println("LibreOffice: " + LibreOffice.find()
                .map(i -> i.version() + " at " + i.executable()).orElse("NOT FOUND"));
        System.out.println();

        selfRoundTrip();
        alignmentSpelling();
        cellWhitespace();
        if (LibreOffice.isAvailable()) {
            interop("odt", "docx");
            interop("docx", "odt");
            sheetInterop("ods", "xlsx");
            sheetInterop("xlsx", "ods");
            plainTextCheck();
        } else {
            System.out.println("SKIPPED interop: LibreOffice is not installed");
        }

        System.out.println();
        System.out.println(failures == 0 ? "ALL OFFICE CHECKS PASSED"
                : failures + " OFFICE CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- fixtures ------------------------------------------------------

    private static TextDocument sampleText() {
        TextDocument document = new TextDocument();

        Paragraph title = document.addParagraph();
        title.setAlign(Align.CENTER);
        title.add(new Run("Quarterly Report",
                Format.PLAIN.withBold(true).withSize(20).withFont("Times New Roman")));

        Paragraph body = document.addParagraph();
        body.add(Run.of("Plain text, then "));
        body.add(new Run("bold", Format.PLAIN.withBold(true)));
        body.add(Run.of(", then "));
        body.add(new Run("italic", Format.PLAIN.withItalic(true)));
        body.add(Run.of(", then "));
        body.add(new Run("underlined", Format.PLAIN.withUnderline(true)));
        body.add(Run.of(", then "));
        body.add(new Run("red", Format.PLAIN.withColor("#FF0000")));
        body.add(Run.of("."));

        Paragraph right = document.addParagraph();
        right.setAlign(Align.RIGHT);
        right.add(Run.of("Aligned right"));

        Paragraph spaces = document.addParagraph();
        spaces.add(Run.of("Spaces:   three, tab:\tdone, ampersand & angle <tag>"));

        Paragraph justified = document.addParagraph();
        justified.setAlign(Align.JUSTIFY);
        justified.add(Run.of("Aligned justified"));

        document.addParagraph();
        return document;
    }

    private static SheetDocument sampleSheet() {
        SheetDocument sheet = new SheetDocument();
        sheet.setName("Budget");
        sheet.set(0, 0, "Item");
        sheet.set(0, 1, "Qty");
        sheet.set(0, 2, "Unit");
        sheet.set(0, 3, "Total");
        String[] items = {"Workstation", "Monitor", "Coffee"};
        double[][] numbers = {{4, 2400}, {8, 600}, {999, 3}};
        for (int i = 0; i < items.length; i++) {
            int row = i + 1;
            sheet.set(row, 0, items[i]);
            sheet.set(row, 1, SheetDocument.plainNumber(numbers[i][0]));
            sheet.set(row, 2, SheetDocument.plainNumber(numbers[i][1]));
            sheet.set(row, 3, "=B" + (row + 1) + "*C" + (row + 1));
            sheet.setCachedValue(row, 3,
                    SheetDocument.plainNumber(numbers[i][0] * numbers[i][1]));
        }
        sheet.set(4, 2, "Grand total");
        sheet.set(4, 3, "=SUM(D2:D4)");
        sheet.setCachedValue(4, 3, "17397");
        sheet.set(5, 0, "Text with, comma");
        sheet.set(5, 1, "spaced   out");
        return sheet;
    }

    // ---- checks --------------------------------------------------------

    /** JavaOS writes and JavaOS reads: catches plain bugs before blaming anyone else. */
    private static void selfRoundTrip() throws Exception {
        for (String format : List.of("odt", "docx")) {
            Path file = work.resolve("self." + format);
            OfficeFormats.writeText(sampleText(), file);
            checkText(format + " self round trip", OfficeFormats.readText(file));
        }
        for (String format : List.of("ods", "xlsx")) {
            Path file = work.resolve("self." + format);
            OfficeFormats.writeSheet(sampleSheet(), file);
            checkSheet(format + " self round trip", OfficeFormats.readSheet(file));
        }
    }

    /**
     * Which of ODF's two spellings of alignment we write.
     *
     * <p>{@code start} and {@code end} follow the writing direction, {@code
     * left} and {@code right} do not, and the standard allows either. But
     * LibreOffice quietly drops {@code end} when it reads a file, so a
     * right-aligned paragraph written that way survives our own round trip and
     * is then lost by every conversion LibreOffice performs. Checked here as
     * well as through the conversion, because the conversion is skipped on a
     * machine with no LibreOffice on it -- which is where the temptation to
     * write the tidier spelling would go unnoticed.
     */
    private static void alignmentSpelling() throws Exception {
        Path file = work.resolve("alignment.odt");
        OfficeFormats.writeText(sampleText(), file);
        String content = Zip.text(Zip.read(file), "content.xml");
        expect("odt writes the alignment LibreOffice reads",
                content.contains("fo:text-align=\"right\""));
        expect("and not the one it drops", !content.contains("fo:text-align=\"end\""));

        // A style:font-name pointing at a face nobody declared is discarded the
        // same way, so the declarations have to be there and the literal family
        // alongside them.
        expect("odt declares the fonts its styles name",
                content.contains("<office:font-face-decls>")
                        && content.contains("style:font-face")
                        && content.contains("style:name=\"Times New Roman\""));
        expect("and spells the family out as well",
                content.contains("fo:font-family=\"&apos;Times New Roman&apos;\""),
                "got <" + slice(content, "fo:font-family=") + ">");
    }

    /** A little of {@code content} from {@code marker} on, to make a failure legible. */
    private static String slice(String content, String marker) {
        int at = content.indexOf(marker);
        return at < 0 ? "(absent)"
                : content.substring(at, Math.min(content.length(), at + 44));
    }

    /**
     * A spreadsheet cell's whitespace, which ODF cannot store literally.
     *
     * <p>A run of spaces goes out as {@code <text:s text:c="n"/>} and a tab as
     * {@code <text:tab/>}. Writing them literally loses them to any reader that
     * folds whitespace, and reading only the text nodes shortens every run to a
     * single space -- which is what a cell converted by LibreOffice came back
     * as. The .ods leg is what exercises the encoding; the .xlsx leg is here so
     * the check reads as being about the value, not about one format.
     */
    private static void cellWhitespace() throws Exception {
        for (String format : List.of("ods", "xlsx")) {
            SheetDocument sheet = new SheetDocument();
            sheet.set(0, 0, "trailing   spaces   ");
            sheet.set(0, 1, "tab\there");
            Path file = work.resolve("whitespace." + format);
            OfficeFormats.writeSheet(sheet, file);
            SheetDocument back = OfficeFormats.readSheet(file);
            expect(format + " keeps a run of spaces",
                    "trailing   spaces   ".equals(back.get(0, 0)),
                    "got <" + back.get(0, 0) + ">");
            expect(format + " keeps a tab", "tab\there".equals(back.get(0, 1)),
                    "got <" + back.get(0, 1) + ">");
        }
    }

    /** JavaOS writes {@code from}, LibreOffice converts to {@code to}, JavaOS reads it. */
    private static void interop(String from, String to) throws Exception {
        Path source = work.resolve("interop_" + from + "." + from);
        OfficeFormats.writeText(sampleText(), source);
        Path converted = LibreOffice.convert(source, to, work.resolve("out_" + from + "_" + to));
        checkText("text " + from + " -> LibreOffice -> " + to,
                OfficeFormats.readText(converted));
    }

    private static void sheetInterop(String from, String to) throws Exception {
        Path source = work.resolve("interop_" + from + "." + from);
        OfficeFormats.writeSheet(sampleSheet(), source);
        Path converted = LibreOffice.convert(source, to, work.resolve("out_" + from + "_" + to));
        checkSheet("sheet " + from + " -> LibreOffice -> " + to,
                OfficeFormats.readSheet(converted));
    }

    /** LibreOffice flattening our .odt to text proves it really parsed the package. */
    private static void plainTextCheck() throws Exception {
        Path source = work.resolve("flat.odt");
        OfficeFormats.writeText(sampleText(), source);
        Path text = LibreOffice.convert(source, "txt:Text", work.resolve("out_txt"));
        String content = Files.readString(text);
        expect("LibreOffice reads our .odt (title)", content.contains("Quarterly Report"));
        expect("LibreOffice reads our .odt (runs)", content.contains("bold"));
        expect("LibreOffice reads our .odt (escaping)", content.contains("ampersand & angle <tag>"));
    }

    private static void checkText(String label, TextDocument document) {
        List<Paragraph> paragraphs = document.paragraphs();
        expect(label + ": paragraph count", paragraphs.size() >= 4,
                "got " + paragraphs.size());
        if (paragraphs.size() < 4) {
            return;
        }
        expect(label + ": title text", "Quarterly Report".equals(paragraphs.get(0).text()),
                "got <" + paragraphs.get(0).text() + ">");
        expect(label + ": title centred", paragraphs.get(0).align() == Align.CENTER,
                "got " + paragraphs.get(0).align());
        Format titleFormat = paragraphs.get(0).runs().get(0).format();
        expect(label + ": title bold", titleFormat.bold());
        expect(label + ": title size 20", titleFormat.sizePt() == 20,
                "got " + titleFormat.sizePt());

        Paragraph body = paragraphs.get(1);
        expect(label + ": body text",
                "Plain text, then bold, then italic, then underlined, then red."
                        .equals(body.text()),
                "got <" + body.text() + ">");
        expect(label + ": bold run", hasRun(body, "bold", f -> f.bold()));
        expect(label + ": italic run", hasRun(body, "italic", f -> f.italic()));
        expect(label + ": underlined run", hasRun(body, "underlined", f -> f.underline()));
        expect(label + ": red run", hasRun(body, "red",
                f -> "#FF0000".equalsIgnoreCase(f.color())));

        // The title carries a font, and nothing used to check that it arrived.
        expect(label + ": title font", "Times New Roman".equals(titleFormat.font()),
                "got <" + titleFormat.font() + ">");

        expect(label + ": right aligned", paragraphs.get(2).align() == Align.RIGHT,
                "got " + paragraphs.get(2).align());
        expect(label + ": justified", paragraphs.get(4).align() == Align.JUSTIFY,
                "got " + paragraphs.get(4).align());
        String spaces = paragraphs.get(3).text();
        expect(label + ": spaces preserved", spaces.contains("Spaces:   three"),
                "got <" + spaces + ">");
        expect(label + ": tab preserved", spaces.contains("\t"), "got <" + spaces + ">");
        expect(label + ": special characters", spaces.contains("ampersand & angle <tag>"),
                "got <" + spaces + ">");
    }

    private static boolean hasRun(Paragraph paragraph, String text,
            java.util.function.Predicate<Format> test) {
        for (Run run : paragraph.runs()) {
            if (run.text().contains(text) && test.test(run.format())) {
                return true;
            }
        }
        return false;
    }

    private static void checkSheet(String label, SheetDocument sheet) {
        expect(label + ": header", "Item".equals(sheet.get(0, 0)), "got " + sheet.get(0, 0));
        expect(label + ": text cell", "Workstation".equals(sheet.get(1, 0)),
                "got " + sheet.get(1, 0));
        expect(label + ": number cell", "4".equals(sheet.get(1, 1)), "got " + sheet.get(1, 1));
        expect(label + ": formula kept", "=B2*C2".equals(sheet.get(1, 3)),
                "got " + sheet.get(1, 3));
        expect(label + ": formula value", "9600".equals(sheet.cachedValue(1, 3)),
                "got " + sheet.cachedValue(1, 3));
        expect(label + ": range formula", "=SUM(D2:D4)".equals(sheet.get(4, 3)),
                "got " + sheet.get(4, 3));
        expect(label + ": range value", "17397".equals(sheet.cachedValue(4, 3)),
                "got " + sheet.cachedValue(4, 3));
        expect(label + ": comma in text", "Text with, comma".equals(sheet.get(5, 0)),
                "got " + sheet.get(5, 0));
        expect(label + ": run of spaces", "spaced   out".equals(sheet.get(5, 1)),
                "got <" + sheet.get(5, 1) + ">");
    }

    private static void expect(String label, boolean condition) {
        expect(label, condition, "");
    }

    private static void expect(String label, boolean condition, String detail) {
        if (condition) {
            System.out.println("ok   " + label);
        } else {
            System.out.println("FAIL " + label + (detail.isEmpty() ? "" : "  (" + detail + ")"));
            failures++;
        }
    }
}
