package javaos.office;

import java.util.ArrayList;
import java.util.List;

/**
 * A word-processor document, reduced to what JavaOS Writer can actually show:
 * paragraphs of formatted runs. Both ODF and OOXML load into this, and both are
 * written back out of it, so the format handlers never talk to Swing and Writer
 * never talks to XML.
 */
public final class TextDocument {

    public enum Align { LEFT, CENTER, RIGHT, JUSTIFY }

    /** Character formatting. Null font and colour, and size 0, mean "inherit". */
    public record Format(boolean bold, boolean italic, boolean underline,
            String font, int sizePt, String color) {

        public static final Format PLAIN = new Format(false, false, false, null, 0, null);

        public Format withBold(boolean value) {
            return new Format(value, italic, underline, font, sizePt, color);
        }

        public Format withItalic(boolean value) {
            return new Format(bold, value, underline, font, sizePt, color);
        }

        public Format withUnderline(boolean value) {
            return new Format(bold, italic, value, font, sizePt, color);
        }

        public Format withFont(String value) {
            return new Format(bold, italic, underline, value, sizePt, color);
        }

        public Format withSize(int value) {
            return new Format(bold, italic, underline, font, value, color);
        }

        public Format withColor(String value) {
            return new Format(bold, italic, underline, font, sizePt, value);
        }

        public boolean isPlain() {
            return !bold && !italic && !underline && font == null && sizePt == 0
                    && color == null;
        }
    }

    /** A stretch of text sharing one format. */
    public record Run(String text, Format format) {

        public static Run of(String text) {
            return new Run(text, Format.PLAIN);
        }
    }

    /** One paragraph: an alignment and the runs that make up its line of text. */
    public static final class Paragraph {
        private final List<Run> runs = new ArrayList<>();
        private Align align = Align.LEFT;

        public List<Run> runs() {
            return runs;
        }

        public Align align() {
            return align;
        }

        public void setAlign(Align align) {
            this.align = align == null ? Align.LEFT : align;
        }

        public void add(Run run) {
            if (run.text().isEmpty()) {
                return;
            }
            // Merge with the previous run when the formatting is identical, so a
            // reader that splits text across many spans does not produce hundreds
            // of one-character runs.
            if (!runs.isEmpty()) {
                Run last = runs.get(runs.size() - 1);
                if (last.format().equals(run.format())) {
                    runs.set(runs.size() - 1, new Run(last.text() + run.text(), last.format()));
                    return;
                }
            }
            runs.add(run);
        }

        public String text() {
            StringBuilder text = new StringBuilder();
            for (Run run : runs) {
                text.append(run.text());
            }
            return text.toString();
        }

        public boolean isEmpty() {
            return runs.isEmpty();
        }
    }

    private final List<Paragraph> paragraphs = new ArrayList<>();

    public List<Paragraph> paragraphs() {
        return paragraphs;
    }

    public Paragraph addParagraph() {
        Paragraph paragraph = new Paragraph();
        paragraphs.add(paragraph);
        return paragraph;
    }

    public void add(Paragraph paragraph) {
        paragraphs.add(paragraph);
    }

    public boolean isEmpty() {
        return paragraphs.isEmpty()
                || (paragraphs.size() == 1 && paragraphs.get(0).isEmpty());
    }
}
