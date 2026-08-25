package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.rtf.RTFEditorKit;
import javax.swing.undo.UndoManager;

import javaos.desktop.Shell;
import javaos.office.OfficeFormats;
import javaos.office.TextDocument;
import javaos.soffice.LibreOffice;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/** The word processor: a page on a grey desk, two toolbars, and real RTF on disk. */
public class WriterApp extends AppWindow {

    private static final String[] FONTS = {"Serif", "SansSerif", "Monospaced", "Dialog"};
    private static final Integer[] SIZES = {8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48};

    /** The printable width of the page, in pixels. Text wraps to this. */
    private static final int PAGE_WIDTH = 620;

    /**
     * The page. Fixing the width is what makes the text wrap: a JTextPane left to
     * its own devices reports a preferred width as wide as its longest line, and
     * then never breaks a line at all.
     */
    private final JTextPane pane = new JTextPane() {
        @Override public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            return new Dimension(PAGE_WIDTH, preferred.height);
        }

        @Override public Dimension getMinimumSize() {
            return new Dimension(PAGE_WIDTH, super.getMinimumSize().height);
        }

        @Override public Dimension getMaximumSize() {
            return new Dimension(PAGE_WIDTH, Integer.MAX_VALUE);
        }
    };
    private final UndoManager undo = new UndoManager();
    private final JComboBox<String> fontBox = new JComboBox<>(FONTS);
    private final JComboBox<Integer> sizeBox = new JComboBox<>(SIZES);
    private final JToggleButton bold =
            new JToggleButton(Icons.styleGlyph(18, "B", Color.BLACK, Font.BOLD, false));
    private final JToggleButton italic =
            new JToggleButton(Icons.styleGlyph(18, "I", Color.BLACK, Font.ITALIC, false));
    private final JToggleButton underline =
            new JToggleButton(Icons.styleGlyph(18, "U", Color.BLACK, Font.PLAIN, true));
    private final javax.swing.JLabel wordCount = Ui.statusCell("0 words", 110);

    private boolean updatingControls;

    public WriterApp(Shell shell, String argument) {
        super(shell, "Writer", Icons.textDocument(16));
        setSize(720, 560);

        pane.setFont(new Font("Serif", Font.PLAIN, 14));
        pane.setBorder(BorderFactory.createEmptyBorder(28, 34, 28, 34));
        pane.getDocument().addUndoableEditListener(undo);
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                touched();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                touched();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                setDirty(true);
            }
        });
        pane.addCaretListener(e -> syncControls());

        // The page floats on a grey desk, drop shadow and all. The desk tracks the
        // viewport width so the window never scrolls sideways; the page itself
        // stays a fixed width, which is what makes the text wrap.
        Desk desk = new Desk();
        JPanel shadow = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(new Color(0, 0, 0, 70));
                g2.fillRect(5, 5, getWidth() - 5, getHeight() - 5);
            }
        };
        shadow.setOpaque(false);
        shadow.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 5));
        pane.setBackground(Color.WHITE);
        shadow.add(pane, BorderLayout.CENTER);
        java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.fill = java.awt.GridBagConstraints.NONE;
        gbc.anchor = java.awt.GridBagConstraints.NORTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.insets = new java.awt.Insets(12, 24, 12, 24);
        desk.add(shadow, gbc);

        JScrollPane scroll = new JScrollPane(desk);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        setBody(scroll);

        addToolBar(functionBar());
        addToolBar(formatBar());
        setJMenuBar(menuBar());
        addStatusCell(wordCount);
        addStatusCell(Ui.statusCell("Insert", 60));

        if (argument != null && vfs().exists(argument)) {
            load(argument);
        } else {
            setDocumentPath(null);
            status("New document.");
            updateTitle();
        }
        java.awt.EventQueue.invokeLater(pane::requestFocusInWindow);
    }

    /**
     * The grey area the page sits on. It follows the viewport's width so the
     * window never grows a horizontal scrollbar, while its height is free to
     * exceed the viewport as the document gets longer.
     */
    private static class Desk extends JPanel implements javax.swing.Scrollable {

        Desk() {
            super(new java.awt.GridBagLayout());
        }

        @Override protected void paintComponent(Graphics g) {
            g.setColor(Ui.darker(Ui.control(), 0.25));
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible,
                int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible,
                int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? visible.height : visible.width;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private void touched() {
        setDirty(true);
        updateWordCount();
    }

    private void updateWordCount() {
        String text = pane.getText();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        wordCount.setText(words + " words, " + text.length() + " chars");
    }

    // ---- chrome --------------------------------------------------------

    private JToolBar functionBar() {
        JToolBar bar = toolBar();
        bar.add(tool(action("New", Icons.newDoc(20), this::newDocument)));
        bar.add(tool(action("Open...", Icons.folderOpen(20), this::openDocument)));
        bar.add(tool(action("Save", Icons.disk(20), this::saveDocument)));
        gap(bar);
        bar.add(tool(editorAction("Cut", Icons.cut(20),
                new javax.swing.text.DefaultEditorKit.CutAction())));
        bar.add(tool(editorAction("Copy", Icons.copy(20),
                new javax.swing.text.DefaultEditorKit.CopyAction())));
        bar.add(tool(editorAction("Paste", Icons.paste(20),
                new javax.swing.text.DefaultEditorKit.PasteAction())));
        gap(bar);
        bar.add(tool(action("Undo", Icons.arrow(20, 0), () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        })));
        bar.add(tool(action("Redo", Icons.arrow(20, 2), () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        })));
        return bar;
    }

    private JToolBar formatBar() {
        JToolBar bar = toolBar();
        fontBox.setPreferredSize(new Dimension(130, 22));
        fontBox.setMaximumSize(new Dimension(130, 22));
        fontBox.addActionListener(e -> {
            if (!updatingControls) {
                applyAttribute(StyleConstants.FontFamily, fontBox.getSelectedItem());
            }
        });
        sizeBox.setPreferredSize(new Dimension(58, 22));
        sizeBox.setMaximumSize(new Dimension(58, 22));
        sizeBox.setSelectedItem(14);
        sizeBox.addActionListener(e -> {
            if (!updatingControls) {
                applyAttribute(StyleConstants.FontSize, sizeBox.getSelectedItem());
            }
        });
        bar.add(fontBox);
        bar.add(javax.swing.Box.createHorizontalStrut(4));
        bar.add(sizeBox);
        gap(bar);

        styleToggle(bar, bold, StyleConstants.Bold);
        styleToggle(bar, italic, StyleConstants.Italic);
        styleToggle(bar, underline, StyleConstants.Underline);
        gap(bar);

        bar.add(tool(alignAction("Left", 0, StyleConstants.ALIGN_LEFT)));
        bar.add(tool(alignAction("Centre", 1, StyleConstants.ALIGN_CENTER)));
        bar.add(tool(alignAction("Right", 2, StyleConstants.ALIGN_RIGHT)));
        bar.add(tool(alignAction("Justify", 3, StyleConstants.ALIGN_JUSTIFIED)));
        gap(bar);
        bar.add(tool(action("Font Colour...", Icons.paint(20), this::chooseColor)));
        return bar;
    }

    private void styleToggle(JToolBar bar, JToggleButton button, Object attribute) {
        button.setPreferredSize(new Dimension(26, 26));
        button.setMaximumSize(new Dimension(26, 26));
        button.setFocusPainted(false);
        button.addActionListener(e -> {
            if (!updatingControls) {
                applyAttribute(attribute, button.isSelected());
            }
        });
        bar.add(button);
    }

    private Action alignAction(String name, int glyph, int alignment) {
        Icon icon = Icons.of(20, g -> {
            g.setColor(Icons.STEEL_DARK);
            for (int i = 0; i < 4; i++) {
                int width = i % 2 == 0 ? 24 : 16;
                int x = switch (glyph) {
                    case 1 -> 16 - width / 2;
                    case 2 -> 28 - width;
                    case 3 -> 4;
                    default -> 4;
                };
                g.fillRect(x, 6 + i * 5, glyph == 3 ? 24 : width, 3);
            }
        });
        return new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent e) {
                SimpleAttributeSet set = new SimpleAttributeSet();
                StyleConstants.setAlignment(set, alignment);
                StyledDocument doc = pane.getStyledDocument();
                int start = Math.min(pane.getSelectionStart(), pane.getSelectionEnd());
                int length = Math.abs(pane.getSelectionEnd() - pane.getSelectionStart());
                doc.setParagraphAttributes(start, Math.max(length, 1), set, false);
                setDirty(true);
                status("Paragraph aligned " + name.toLowerCase() + ".");
            }
        };
    }

    private JMenuBar menuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("New", "control N", this::newDocument));
        file.add(item("Open...", "control O", this::openDocument));
        file.add(item("Save", "control S", () -> saveDocument()));
        file.add(item("Save As...", "control shift S", () -> saveAs()));
        file.addSeparator();
        file.add(item("Open in LibreOffice", null, this::openInLibreOffice));
        file.addSeparator();
        file.add(item("Close", null, this::doDefaultCloseAction));
        menuBar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(item("Undo", "control Z", () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        }));
        edit.add(item("Redo", "control Y", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        }));
        edit.addSeparator();
        edit.add(item("Select All", "control A", pane::selectAll));
        menuBar.add(edit);

        JMenu insert = new JMenu("Insert");
        insert.add(item("Date and Time", null, () -> insertText(
                new SimpleDateFormat("d MMMM yyyy, HH:mm").format(new Date()))));
        insert.add(item("Page Break", null, () -> insertText(
                "\n\n- - - - - - - - - - - - - - - - - - - - - - -\n\n")));
        insert.add(item("Signature", null, () -> insertText(
                "\n\nRegards,\n" + shell.settings().userName() + "\n")));
        menuBar.add(insert);

        JMenu format = new JMenu("Format");
        format.add(item("Bold", "control B", () -> {
            bold.setSelected(!bold.isSelected());
            applyAttribute(StyleConstants.Bold, bold.isSelected());
        }));
        format.add(item("Italic", "control I", () -> {
            italic.setSelected(!italic.isSelected());
            applyAttribute(StyleConstants.Italic, italic.isSelected());
        }));
        format.add(item("Font Colour...", null, this::chooseColor));
        menuBar.add(format);

        return menuBar;
    }

    private JMenuItem item(String text, String accelerator, Runnable action) {
        JMenuItem menuItem = new JMenuItem(text);
        if (accelerator != null) {
            menuItem.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        }
        menuItem.addActionListener(e -> action.run());
        return menuItem;
    }

    private Action action(String name, Icon icon, Runnable body) {
        Action a = new AbstractAction(name, icon) {
            @Override public void actionPerformed(ActionEvent e) {
                body.run();
            }
        };
        a.putValue(Action.SHORT_DESCRIPTION, name);
        return a;
    }

    private Action editorAction(String name, Icon icon, Action delegate) {
        delegate.putValue(Action.NAME, name);
        delegate.putValue(Action.SMALL_ICON, icon);
        delegate.putValue(Action.SHORT_DESCRIPTION, name);
        return delegate;
    }

    // ---- styling -------------------------------------------------------

    private void applyAttribute(Object attribute, Object value) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        set.addAttribute(attribute, value);
        int start = pane.getSelectionStart();
        int end = pane.getSelectionEnd();
        if (start == end) {
            // No selection: the attribute applies to whatever is typed next.
            pane.setCharacterAttributes(set, false);
        } else {
            pane.getStyledDocument().setCharacterAttributes(start, end - start, set, false);
        }
        setDirty(true);
        pane.requestFocusInWindow();
    }

    private void chooseColor() {
        Color chosen = JColorChooser.showDialog(this, "Font Colour", Color.BLACK);
        if (chosen != null) {
            applyAttribute(StyleConstants.Foreground, chosen);
        }
    }

    private void syncControls() {
        updatingControls = true;
        AttributeSet set = pane.getCharacterAttributes();
        bold.setSelected(StyleConstants.isBold(set));
        italic.setSelected(StyleConstants.isItalic(set));
        underline.setSelected(StyleConstants.isUnderline(set));
        fontBox.setSelectedItem(StyleConstants.getFontFamily(set));
        sizeBox.setSelectedItem(StyleConstants.getFontSize(set));
        updatingControls = false;
    }

    private void insertText(String text) {
        try {
            pane.getDocument().insertString(pane.getCaretPosition(), text, null);
        } catch (BadLocationException e) {
            error("Could not insert text: " + e.getMessage());
        }
    }

    // ---- documents -----------------------------------------------------

    private void newDocument() {
        if (isDirty() && !confirmClose()) {
            return;
        }
        pane.setText("");
        setDocumentPath(null);
        setDirty(false);
        status("New document.");
    }

    private void openDocument() {
        String path = VfsChooser.open(this, vfs(), Vfs.HOME + "/Documents",
                "Open Document", "odt", "docx", "txt", "rtf", "log", "md", "java",
                "properties");
        if (path != null) {
            load(path);
        }
    }

    private void load(String path) {
        try {
            if (OfficeFormats.isTextFormat(Vfs.extension(path))) {
                pane.setEditorKit(new StyledEditorKit());
                pane.setDocument(pane.getEditorKit().createDefaultDocument());
                toSwing(OfficeFormats.readText(vfs().host(path)));
                pane.setCaretPosition(0);
            } else if (Vfs.extension(path).equals("rtf")) {
                RTFEditorKit kit = new RTFEditorKit();
                pane.setEditorKit(kit);
                javax.swing.text.Document doc = kit.createDefaultDocument();
                kit.read(new ByteArrayInputStream(vfs().readBytes(path)), doc, 0);
                pane.setDocument(doc);
            } else {
                pane.setEditorKit(new StyledEditorKit());
                pane.setText(vfs().read(path));
                pane.setCaretPosition(0);
            }
            pane.getDocument().addUndoableEditListener(undo);
            setDocumentPath(path);
            setDirty(false);
            updateWordCount();
            status("Opened " + path);
        } catch (Exception e) {
            error("Could not open " + Vfs.name(path) + ":\n" + e.getMessage());
        }
    }

    @Override protected boolean saveDocument() {
        if (documentPath() == null) {
            return saveAs();
        }
        return writeTo(documentPath());
    }

    private boolean saveAs() {
        String path = VfsChooser.save(this, vfs(), Vfs.HOME + "/Documents",
                documentPath() == null ? "untitled.txt" : Vfs.name(documentPath()),
                "Save Document", "odt", "docx", "txt", "rtf");
        return path != null && writeTo(path);
    }

    private boolean writeTo(String path) {
        try {
            if (OfficeFormats.isTextFormat(Vfs.extension(path))) {
                OfficeFormats.writeText(fromSwing(), vfs().host(path));
            } else if (Vfs.extension(path).equals("rtf")) {
                RTFEditorKit kit = new RTFEditorKit();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                kit.write(out, pane.getDocument(), 0, pane.getDocument().getLength());
                vfs().writeBytes(path, out.toByteArray());
            } else {
                vfs().write(path, pane.getText());
                if (!pane.getText().isEmpty() && hasFormatting()) {
                    status("Saved as plain text -- formatting was not preserved.");
                }
            }
            setDocumentPath(path);
            setDirty(false);
            status("Saved " + path);
            return true;
        } catch (Exception e) {
            error("Could not save " + Vfs.name(path) + ":\n" + e.getMessage());
            return false;
        }
    }

    // ---- office formats ------------------------------------------------

    /** Pours an .odt or .docx into the editor, paragraph by paragraph. */
    private void toSwing(TextDocument document) throws BadLocationException {
        StyledDocument doc = pane.getStyledDocument();
        for (TextDocument.Paragraph paragraph : document.paragraphs()) {
            int start = doc.getLength();
            for (TextDocument.Run run : paragraph.runs()) {
                doc.insertString(doc.getLength(), run.text(), attributesOf(run.format()));
            }
            SimpleAttributeSet paragraphStyle = new SimpleAttributeSet();
            StyleConstants.setAlignment(paragraphStyle, switch (paragraph.align()) {
                case CENTER -> StyleConstants.ALIGN_CENTER;
                case RIGHT -> StyleConstants.ALIGN_RIGHT;
                case JUSTIFY -> StyleConstants.ALIGN_JUSTIFIED;
                default -> StyleConstants.ALIGN_LEFT;
            });
            doc.setParagraphAttributes(start, Math.max(doc.getLength() - start, 1),
                    paragraphStyle, false);
            doc.insertString(doc.getLength(), "\n", null);
        }
        // Every paragraph added a separator; the document does not need the last one.
        if (doc.getLength() > 0) {
            doc.remove(doc.getLength() - 1, 1);
        }
    }

    private SimpleAttributeSet attributesOf(TextDocument.Format format) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setBold(attributes, format.bold());
        StyleConstants.setItalic(attributes, format.italic());
        StyleConstants.setUnderline(attributes, format.underline());
        if (format.font() != null) {
            StyleConstants.setFontFamily(attributes, format.font());
        }
        if (format.sizePt() > 0) {
            StyleConstants.setFontSize(attributes, format.sizePt());
        }
        if (format.color() != null) {
            try {
                StyleConstants.setForeground(attributes, Color.decode(format.color()));
            } catch (NumberFormatException e) {
                // An unparseable colour just stays the default.
            }
        }
        return attributes;
    }

    /** Reads the editor back out as a document the office writers understand. */
    private TextDocument fromSwing() {
        StyledDocument doc = pane.getStyledDocument();
        TextDocument document = new TextDocument();
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element paragraphElement = root.getElement(i);
            TextDocument.Paragraph paragraph = document.addParagraph();
            paragraph.setAlign(switch (StyleConstants.getAlignment(
                    paragraphElement.getAttributes())) {
                case StyleConstants.ALIGN_CENTER -> TextDocument.Align.CENTER;
                case StyleConstants.ALIGN_RIGHT -> TextDocument.Align.RIGHT;
                case StyleConstants.ALIGN_JUSTIFIED -> TextDocument.Align.JUSTIFY;
                default -> TextDocument.Align.LEFT;
            });
            for (int r = 0; r < paragraphElement.getElementCount(); r++) {
                Element runElement = paragraphElement.getElement(r);
                int start = runElement.getStartOffset();
                int end = Math.min(runElement.getEndOffset(), doc.getLength());
                if (end <= start) {
                    continue;
                }
                try {
                    // The paragraph's trailing newline belongs to the structure,
                    // not to its text.
                    String text = doc.getText(start, end - start);
                    if (text.endsWith("\n")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    paragraph.add(new TextDocument.Run(text,
                            formatOf(runElement.getAttributes())));
                } catch (BadLocationException e) {
                    // Skip a run that moved underneath us; the rest still saves.
                }
            }
        }
        return document;
    }

    private TextDocument.Format formatOf(AttributeSet attributes) {
        TextDocument.Format format = TextDocument.Format.PLAIN
                .withBold(StyleConstants.isBold(attributes))
                .withItalic(StyleConstants.isItalic(attributes))
                .withUnderline(StyleConstants.isUnderline(attributes))
                .withFont(StyleConstants.getFontFamily(attributes))
                .withSize(StyleConstants.getFontSize(attributes));
        Color color = StyleConstants.getForeground(attributes);
        if (color != null && !color.equals(Color.BLACK)) {
            format = format.withColor(String.format("#%02X%02X%02X",
                    color.getRed(), color.getGreen(), color.getBlue()));
        }
        return format;
    }

    /** Hands the open document to LibreOffice, if the user has it installed. */
    private void openInLibreOffice() {
        if (documentPath() == null) {
            error("Save the document first, then it can be handed over.");
            return;
        }
        if (!LibreOffice.isAvailable()) {
            error("LibreOffice was not found on this machine.\n\n"
                    + "JavaOS reads and writes these formats on its own; this menu\n"
                    + "item only hands the file to LibreOffice when it is installed.");
            return;
        }
        try {
            if (isDirty()) {
                saveDocument();
            }
            LibreOffice.open(vfs().host(documentPath()));
            status("Handed " + Vfs.name(documentPath()) + " to LibreOffice.");
        } catch (Exception e) {
            error("Could not start LibreOffice:\n" + e.getMessage());
        }
    }

    private boolean hasFormatting() {
        return !(pane.getEditorKit() instanceof RTFEditorKit)
                && pane.getStyledDocument().getLength() > 0
                && pane.getStyledDocument().getCharacterElement(0)
                        .getAttributes().getAttributeCount() > 0;
    }

    @Override protected boolean confirmClose() {
        if (!isDirty()) {
            return true;
        }
        int answer = JOptionPane.showInternalConfirmDialog(this,
                "Save changes to "
                        + (documentPath() == null ? "the new document" : Vfs.name(documentPath()))
                        + "?",
                "Writer", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        return answer != JOptionPane.YES_OPTION || saveDocument();
    }
}
