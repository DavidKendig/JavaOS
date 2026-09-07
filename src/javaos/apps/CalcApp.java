package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import javaos.desktop.Shell;
import javaos.office.OfficeFormats;
import javaos.office.SheetDocument;
import javaos.soffice.LibreOffice;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/** The spreadsheet: 26 columns, 200 rows, formulas, and a CSV on disk. */
public class CalcApp extends AppWindow {

    private static final int ROWS = 200;
    private static final int COLS = 26;

    private final SheetModel model = new SheetModel(ROWS, COLS);
    private final JTable table = new JTable(model);
    private final JTextField nameBox = new JTextField(7);
    private final JTextField formulaBar = new JTextField();
    private final JLabel summary = Ui.statusCell("Sum: 0", 200);

    private boolean syncing;

    public CalcApp(Shell shell, String argument) {
        super(shell, "Calc", Icons.spreadsheet(16));
        setSize(760, 520);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setRowHeight(20);
        table.setGridColor(Ui.shadow());
        table.setShowGrid(true);
        table.setFont(new Font("Dialog", Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.setDefaultEditor(Object.class, new RawCellEditor());
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        for (int c = 0; c < COLS; c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(84);
        }

        JTableHeader header = table.getTableHeader();
        header.setFont(new Font("Dialog", Font.BOLD, 11));
        header.setReorderingAllowed(false);

        table.getSelectionModel().addListSelectionListener(e -> selectionChanged());
        table.getColumnModel().getSelectionModel()
                .addListSelectionListener(e -> selectionChanged());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setRowHeaderView(rowHeader());
        scroll.setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerBox());
        setBody(scroll);

        addToolBar(functionBar());
        addToolBar(formulaBar());
        setJMenuBar(menuBar());
        addStatusCell(summary);

        if (argument != null && vfs().exists(argument)) {
            load(argument);
        } else {
            setDocumentPath(null);
            updateTitle();
            status("New spreadsheet. Formulas start with '=' -- try =SUM(A1:A5).");
        }
        table.changeSelection(0, 0, false, false);
    }

    // ---- chrome --------------------------------------------------------

    private JToolBar functionBar() {
        JToolBar bar = toolBar();
        bar.add(tool(action("New", Icons.newDoc(20), this::newSheet)));
        bar.add(tool(action("Open...", Icons.folderOpen(20), this::openSheet)));
        bar.add(tool(action("Save", Icons.disk(20), this::saveDocument)));
        gap(bar);
        bar.add(tool(action("Sum Column Above", Icons.letterGlyph(20, "Σ", Icons.STEEL_DARK),
                this::autoSum)));
        bar.add(tool(action("Clear Cell", Icons.trash(20), this::clearSelection)));
        gap(bar);
        bar.add(tool(action("Recalculate", Icons.refresh(20), () -> {
            model.fireTableDataChanged();
            status("Recalculated.");
        })));
        return bar;
    }

    private JPanel formulaBar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 4, 4));
        nameBox.setEditable(false);
        nameBox.setHorizontalAlignment(SwingConstants.CENTER);
        nameBox.setFont(new Font("Dialog", Font.BOLD, 11));
        JLabel equals = new JLabel(" = ");
        equals.setFont(new Font("Serif", Font.BOLD, 14));

        formulaBar.setFont(new Font("Monospaced", Font.PLAIN, 12));
        formulaBar.addActionListener(e -> commitFormula());

        JPanel west = new JPanel(new BorderLayout());
        west.add(nameBox, BorderLayout.CENTER);
        west.add(equals, BorderLayout.EAST);
        bar.add(west, BorderLayout.WEST);
        bar.add(formulaBar, BorderLayout.CENTER);
        return bar;
    }

    private JMenuBar menuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("New", this::newSheet));
        file.add(item("Open...", this::openSheet));
        file.add(item("Save", () -> saveDocument()));
        file.add(item("Save As...", () -> saveAs()));
        file.addSeparator();
        file.add(item("Open in LibreOffice", this::openInLibreOffice));
        file.addSeparator();
        file.add(item("Close", this::doDefaultCloseAction));
        menuBar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(item("Clear Cells", this::clearSelection));
        edit.add(item("Clear Sheet", () -> {
            if (JOptionPane.showInternalConfirmDialog(this, "Clear every cell?", "Calc",
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                model.clearAll();
                setDirty(true);
            }
        }));
        menuBar.add(edit);

        JMenu insert = new JMenu("Insert");
        insert.add(item("Sum Above", this::autoSum));
        insert.add(item("Average Above", () -> insertAggregate("AVG")));
        insert.add(item("Maximum Above", () -> insertAggregate("MAX")));
        menuBar.add(insert);

        JMenu help = new JMenu("Help");
        help.add(item("Formula Reference", () -> JOptionPane.showInternalMessageDialog(this,
                """
                Formulas begin with '='.

                  =A1+B2*2        arithmetic, with ( ) and ^
                  =SUM(A1:A10)    ranges use a colon
                  =AVG(B1:B4)     AVG, MIN, MAX, COUNT, PRODUCT
                  =ROUND(A1, 2)   ROUND, ABS, SQRT, PI()

                Cells that refer to themselves show #CIRC!.
                """,
                "Formula Reference", JOptionPane.INFORMATION_MESSAGE)));
        menuBar.add(help);
        return menuBar;
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem menuItem = new JMenuItem(text);
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

    /** The numbered strip down the left, styled like the column headers. */
    private JList<String> rowHeader() {
        JList<String> header = new JList<>(new AbstractListModel<String>() {
            @Override public int getSize() {
                return ROWS;
            }

            @Override public String getElementAt(int index) {
                return String.valueOf(index + 1);
            }
        });
        header.setFixedCellWidth(38);
        header.setFixedCellHeight(table.getRowHeight());
        header.setCellRenderer(new RowHeaderRenderer());
        header.setEnabled(false);
        return header;
    }

    private Component cornerBox() {
        JPanel corner = new JPanel();
        corner.setBorder(BorderFactory.createRaisedBevelBorder());
        corner.setBackground(Ui.control());
        return corner;
    }

    // ---- selection and editing -----------------------------------------

    private void selectionChanged() {
        int row = table.getSelectedRow();
        int col = table.getSelectedColumn();
        if (row < 0 || col < 0) {
            return;
        }
        syncing = true;
        nameBox.setText(SheetModel.cellName(row, col));
        formulaBar.setText(model.rawAt(row, col));
        syncing = false;

        double sum = 0;
        int counted = 0;
        for (int r : table.getSelectedRows()) {
            for (int c : table.getSelectedColumns()) {
                if (!model.rawAt(r, c).isEmpty()) {
                    sum += model.numberAt(r, c);
                    counted++;
                }
            }
        }
        summary.setText(counted <= 1
                ? "Value: " + (model.rawAt(row, col).isEmpty()
                        ? "empty" : model.display(row, col))
                : "Sum: " + SheetModel.format(sum) + "   Average: "
                        + SheetModel.format(sum / counted) + "   Count: " + counted);
    }

    private void commitFormula() {
        if (syncing) {
            return;
        }
        int row = table.getSelectedRow();
        int col = table.getSelectedColumn();
        if (row < 0 || col < 0) {
            return;
        }
        model.setValueAt(formulaBar.getText(), row, col);
        setDirty(true);
        table.requestFocusInWindow();
        if (row + 1 < ROWS) {
            table.changeSelection(row + 1, col, false, false);
        }
    }

    private void clearSelection() {
        for (int r : table.getSelectedRows()) {
            for (int c : table.getSelectedColumns()) {
                model.clear(r, c);
            }
        }
        setDirty(true);
        selectionChanged();
    }

    private void autoSum() {
        insertAggregate("SUM");
    }

    private void insertAggregate(String function) {
        int row = table.getSelectedRow();
        int col = table.getSelectedColumn();
        if (row <= 0 || col < 0) {
            status("Select a cell below the numbers you want to total.");
            return;
        }
        int first = row - 1;
        while (first > 0 && !model.rawAt(first - 1, col).isEmpty()) {
            first--;
        }
        String formula = "=" + function + "(" + SheetModel.cellName(first, col) + ":"
                + SheetModel.cellName(row - 1, col) + ")";
        model.setValueAt(formula, row, col);
        setDirty(true);
        selectionChanged();
        status("Inserted " + formula);
    }

    // ---- documents -----------------------------------------------------

    private void newSheet() {
        if (isDirty() && !confirmClose()) {
            return;
        }
        model.clearAll();
        setDocumentPath(null);
        setDirty(false);
        status("New spreadsheet.");
    }

    private void openSheet() {
        String path = VfsChooser.open(this, vfs(),
                vfs().firstDirectory(Vfs.HOME + "/Documents"),
                "Open Spreadsheet", "ods", "xlsx", "csv", "tsv");
        if (path != null) {
            load(path);
        }
    }

    private void load(String path) {
        try {
            if (OfficeFormats.isSheetFormat(Vfs.extension(path))) {
                fromSheet(OfficeFormats.readSheet(vfs().host(path)));
            } else {
                model.fromCsv(vfs().read(path));
            }
            setDocumentPath(path);
            setDirty(false);
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
        String path = VfsChooser.save(this, vfs(),
                vfs().firstDirectory(Vfs.HOME + "/Documents"),
                documentPath() == null ? "sheet1.ods" : Vfs.name(documentPath()),
                "Save Spreadsheet", "ods", "xlsx", "csv");
        return path != null && writeTo(path);
    }

    private boolean writeTo(String path) {
        try {
            if (OfficeFormats.isSheetFormat(Vfs.extension(path))) {
                OfficeFormats.writeSheet(toSheet(), vfs().host(path));
            } else {
                vfs().write(path, model.toCsv());
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

    /** Fills the grid from an .ods or .xlsx, formulas and all. */
    private void fromSheet(SheetDocument sheet) {
        model.bulkUpdate(() -> {
            model.clearAll();
            int rows = Math.min(sheet.rowCount(), ROWS);
            int columns = Math.min(sheet.columnCount(), COLS);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    String raw = sheet.get(row, column);
                    if (!raw.isEmpty()) {
                        model.setValueAt(raw, row, column);
                    }
                }
            }
        });
        status("Opened " + sheet.name() + "  (" + sheet.rowCount() + " rows, "
                + sheet.columnCount() + " columns)");
    }

    /**
     * Copies the grid out for saving. Formula cells carry their computed value
     * too, so the file shows the right numbers the moment it is opened, before
     * anything has had a chance to recalculate.
     */
    private SheetDocument toSheet() {
        SheetDocument sheet = new SheetDocument();
        String name = documentPath() == null ? "Sheet1" : Vfs.name(documentPath());
        int dot = name.lastIndexOf('.');
        sheet.setName(dot > 0 ? name.substring(0, dot) : name);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLS; column++) {
                String raw = model.rawAt(row, column);
                if (raw.isEmpty()) {
                    continue;
                }
                sheet.set(row, column, raw);
                if (raw.startsWith("=")) {
                    // Strip the display grouping separators; files want bare numbers.
                    sheet.setCachedValue(row, column,
                            model.display(row, column).replace(",", ""));
                }
            }
        }
        sheet.trim();
        return sheet;
    }

    /** Hands the open spreadsheet to LibreOffice, if the user has it installed. */
    private void openInLibreOffice() {
        if (documentPath() == null) {
            error("Save the spreadsheet first, then it can be handed over.");
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

    // ---- renderers -----------------------------------------------------

    /** Numbers right, text left, errors in red -- the spreadsheet convention. */
    private class CellRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object value,
                boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focused, row, column);
            String text = String.valueOf(value == null ? "" : value);
            String raw = model.rawAt(row, column);
            boolean numeric = raw.startsWith("=") || isNumber(raw);
            setHorizontalAlignment(numeric ? RIGHT : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
            if (text.startsWith("#") && text.endsWith("!")) {
                setForeground(selected ? Color.WHITE : new Color(0xC0, 0x39, 0x2B));
            } else if (!selected) {
                setForeground(Color.BLACK);
            }
            return this;
        }

        private boolean isNumber(String raw) {
            try {
                Double.parseDouble(raw.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }

    private class RowHeaderRenderer extends JLabel implements ListCellRenderer<String> {
        RowHeaderRenderer() {
            setHorizontalAlignment(CENTER);
            setOpaque(true);
            setFont(new Font("Dialog", Font.BOLD, 11));
            setBorder(BorderFactory.createRaisedBevelBorder());
            setBackground(Ui.control());
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list,
                String value, int index, boolean selected, boolean focused) {
            setText(value);
            boolean inSelection = table.getSelectedRow() <= index
                    && index <= table.getSelectionModel().getMaxSelectionIndex()
                    && table.isRowSelected(index);
            setBackground(inSelection ? Ui.accentLight() : Ui.control());
            return this;
        }
    }

    /** Editing shows the formula, not its result. */
    private class RawCellEditor extends DefaultCellEditor {
        RawCellEditor() {
            super(new JTextField());
            ((JTextField) getComponent()).setFont(new Font("Dialog", Font.PLAIN, 12));
            ((JTextField) getComponent()).setBorder(
                    BorderFactory.createLineBorder(Ui.accent()));
            setClickCountToStart(2);
        }

        @Override public Component getTableCellEditorComponent(JTable t, Object value,
                boolean selected, int row, int column) {
            JTextField field = (JTextField) super.getTableCellEditorComponent(
                    t, value, selected, row, column);
            field.setText(model.rawAt(row, column));
            field.selectAll();
            return field;
        }

        @Override public boolean stopCellEditing() {
            boolean stopped = super.stopCellEditing();
            if (stopped) {
                setDirty(true);
                java.awt.EventQueue.invokeLater(CalcApp.this::selectionChanged);
            }
            return stopped;
        }
    }

    @Override public Dimension getMinimumSize() {
        return new Dimension(420, 260);
    }
}
