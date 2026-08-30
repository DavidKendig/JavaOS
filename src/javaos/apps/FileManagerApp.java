package javaos.apps;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;

/** Browses the host file system: folder tree left, icon or detail view right. */
public class FileManagerApp extends AppWindow {

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    private final DefaultListModel<String> iconModel = new DefaultListModel<>();
    private final JList<String> iconView = new JList<>(iconModel);
    private final DetailsModel detailsModel = new DetailsModel();
    private final JTable detailsView = new JTable(detailsModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel viewHost = new JPanel(cards);
    private final JTextField locationField = new JTextField();
    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private final JLabel selectionCell = Ui.statusCell(" ", 150);

    private final Deque<String> back = new ArrayDeque<>();
    private final Deque<String> forward = new ArrayDeque<>();
    private String currentDirectory;
    private boolean details;

    public FileManagerApp(Shell shell, String argument) {
        super(shell, "File Manager", Icons.folderOpen(16));
        setSize(720, 480);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("/");
        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel);
        tree.setCellRenderer(new FolderRenderer());
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> {
            TreePath path = e.getNewLeadSelectionPath();
            if (path != null) {
                String dir = pathOf(path);
                if (!dir.equals(currentDirectory)) {
                    navigate(dir);
                }
            }
        });
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent e) {
                fillChildren((DefaultMutableTreeNode) e.getPath().getLastPathComponent(),
                        pathOf(e.getPath()));
            }

            @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) {
                // Nothing to release; the tree is rebuilt lazily on expand.
            }
        });
        fillChildren(root, "/");

        iconView.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        iconView.setVisibleRowCount(-1);
        iconView.setCellRenderer(new IconRenderer());
        iconView.setFixedCellWidth(96);
        iconView.setFixedCellHeight(72);
        iconView.addMouseListener(new OpenOnDoubleClick(() -> iconView.getSelectedValue()));
        iconView.addListSelectionListener(e -> describeSelection(iconView.getSelectedValue()));

        detailsView.setRowHeight(20);
        detailsView.setShowGrid(false);
        detailsView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        detailsView.setFont(new Font("Dialog", Font.PLAIN, 12));
        detailsView.getTableHeader().setFont(new Font("Dialog", Font.BOLD, 11));
        detailsView.setDefaultRenderer(Object.class, new DetailsRenderer());
        detailsView.addMouseListener(new OpenOnDoubleClick(this::selectedInTable));
        detailsView.getSelectionModel().addListSelectionListener(
                e -> describeSelection(selectedInTable()));
        detailsView.getColumnModel().getColumn(0).setPreferredWidth(220);

        viewHost.add(new JScrollPane(iconView), "icons");
        viewHost.add(new JScrollPane(detailsView), "details");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(tree), viewHost);
        split.setDividerLocation(190);
        setBody(split);

        addToolBar(buildToolBar());
        addToolBar(buildLocationBar());
        setJMenuBar(buildMenuBar());
        addStatusCell(selectionCell);
        installContextMenu();

        navigate(argument == null ? Vfs.HOME : argument);
    }

    // ---- navigation ----------------------------------------------------

    private void navigate(String dir) {
        if (!vfs().isDirectory(dir)) {
            dir = Vfs.parent(dir);
        }
        if (currentDirectory != null && !currentDirectory.equals(dir)) {
            back.push(currentDirectory);
            forward.clear();
        }
        currentDirectory = Vfs.normalize(dir);
        reload();
    }

    private void reload() {
        List<String> entries = vfs().list(currentDirectory);
        iconModel.clear();
        for (String entry : entries) {
            iconModel.addElement(entry);
        }
        detailsModel.setEntries(entries);
        locationField.setText(currentDirectory);
        setTitle(Vfs.name(currentDirectory) + " - File Manager");

        long bytes = 0;
        int folders = 0;
        for (String entry : entries) {
            if (vfs().isDirectory(entry)) {
                folders++;
            } else {
                bytes += vfs().size(entry);
            }
        }
        status(entries.size() + " object(s), " + folders + " folder(s), "
                + Vfs.humanSize(bytes));
        selectionCell.setText(" ");
    }

    private void open(String path) {
        if (path == null) {
            return;
        }
        if (vfs().isDirectory(path)) {
            navigate(path);
        } else {
            shell.openFile(path);
        }
    }

    private String selectedInTable() {
        int row = detailsView.getSelectedRow();
        return row < 0 ? null : detailsModel.pathAt(row);
    }

    private String selected() {
        return details ? selectedInTable() : iconView.getSelectedValue();
    }

    private void describeSelection(String path) {
        if (path == null) {
            selectionCell.setText(" ");
            return;
        }
        selectionCell.setText(vfs().isDirectory(path)
                ? "Folder: " + Vfs.name(path)
                : Vfs.name(path) + "  (" + Vfs.humanSize(vfs().size(path)) + ")");
    }

    // ---- chrome --------------------------------------------------------

    private JToolBar buildToolBar() {
        JToolBar bar = toolBar();
        bar.add(tool(action("Back", Icons.back(20), () -> {
            if (!back.isEmpty()) {
                forward.push(currentDirectory);
                currentDirectory = back.pop();
                reload();
            }
        })));
        bar.add(tool(action("Forward", Icons.forward(20), () -> {
            if (!forward.isEmpty()) {
                back.push(currentDirectory);
                currentDirectory = forward.pop();
                reload();
            }
        })));
        bar.add(tool(action("Up One Level", Icons.up(20),
                () -> navigate(Vfs.parent(currentDirectory)))));
        gap(bar);
        bar.add(tool(action("Home", Icons.folderOpen(20), () -> navigate(Vfs.HOME))));
        bar.add(tool(action("Refresh", Icons.refresh(20), this::reload)));
        gap(bar);
        bar.add(tool(action("New Folder", Icons.folder(20), this::newFolder)));
        bar.add(tool(action("Delete", Icons.trash(20), this::deleteSelected)));
        gap(bar);
        bar.add(tool(action("Terminal Here", Icons.terminal(20),
                () -> shell.launch("terminal", currentDirectory))));
        return bar;
    }

    private JComponent buildLocationBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 5, 4, 5));
        JLabel label = new JLabel("Location:");
        label.setFont(new Font("Dialog", Font.BOLD, 11));
        locationField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        locationField.addActionListener(e -> {
            String typed = Vfs.resolve(currentDirectory, locationField.getText().trim());
            if (vfs().exists(typed)) {
                open(typed);
            } else {
                error("No such folder: " + typed);
                locationField.setText(currentDirectory);
            }
        });
        bar.add(label, BorderLayout.WEST);
        bar.add(locationField, BorderLayout.CENTER);
        return bar;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Open", () -> open(selected())));
        file.add(menuItem("New Folder", this::newFolder));
        file.add(menuItem("New Text Document", this::newDocument));
        file.addSeparator();
        file.add(menuItem("Rename...", this::renameSelected));
        file.add(menuItem("Duplicate", this::duplicateSelected));
        file.add(menuItem("Delete", this::deleteSelected));
        file.addSeparator();
        file.add(menuItem("Properties...", this::showProperties));
        file.addSeparator();
        file.add(menuItem("Close", () -> doDefaultCloseAction()));
        menuBar.add(file);

        JMenu view = new JMenu("View");
        JCheckBoxMenuItem detailsItem = new JCheckBoxMenuItem("Details");
        detailsItem.addActionListener(e -> {
            details = detailsItem.isSelected();
            cards.show(viewHost, details ? "details" : "icons");
        });
        view.add(detailsItem);
        view.add(menuItem("Refresh", this::reload));
        menuBar.add(view);

        JMenu go = new JMenu("Go");
        go.add(menuItem("My Computer", () -> navigate(Vfs.ROOT)));
        go.add(menuItem("Home", () -> navigate(Vfs.HOME)));
        go.add(menuItem("Documents",
                () -> navigate(vfs().firstDirectory(Vfs.HOME + "/Documents"))));
        go.add(menuItem("Pictures",
                () -> navigate(vfs().firstDirectory(Vfs.HOME + "/Pictures"))));

        menuBar.add(go);

        return menuBar;
    }

    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Open", () -> open(selected())));
        menu.add(menuItem("Open With Writer",
                () -> shell.launch("writer", selected())));
        menu.add(menuItem("Open With LibreOffice", this::openWithLibreOffice));
        menu.add(menuItem("Open With VLC", this::openWithVlc));
        menu.addSeparator();
        menu.add(menuItem("Rename...", this::renameSelected));
        menu.add(menuItem("Duplicate", this::duplicateSelected));
        menu.add(menuItem("Delete", this::deleteSelected));
        menu.addSeparator();
        menu.add(menuItem("Properties...", this::showProperties));

        MouseAdapter trigger = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                if (e.getComponent() == iconView) {
                    iconView.setSelectedIndex(iconView.locationToIndex(e.getPoint()));
                } else {
                    int row = detailsView.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        detailsView.setRowSelectionInterval(row, row);
                    }
                }
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        iconView.addMouseListener(trigger);
        detailsView.addMouseListener(trigger);
    }

    // ---- operations ----------------------------------------------------

    /** Hands the selected file straight to LibreOffice, if it is installed. */
    private void openWithLibreOffice() {
        String path = selected();
        if (path == null || vfs().isDirectory(path)) {
            return;
        }
        if (!javaos.soffice.LibreOffice.isAvailable()) {
            error("LibreOffice was not found on this machine.");
            return;
        }
        try {
            javaos.soffice.LibreOffice.open(vfs().host(path));
            status("Handed " + Vfs.name(path) + " to LibreOffice.");
        } catch (java.io.IOException e) {
            error("Could not start LibreOffice:\n" + e.getMessage());
        }
    }

    /** Hands the selected file straight to VLC, if it is installed. */
    private void openWithVlc() {
        String path = selected();
        if (path == null || vfs().isDirectory(path)) {
            return;
        }
        if (!javaos.vlc.Vlc.isAvailable()) {
            error("VLC was not found on this machine.");
            return;
        }
        try {
            javaos.vlc.Vlc.open(vfs().host(path));
            status("Handed " + Vfs.name(path) + " to VLC.");
        } catch (java.io.IOException e) {
            error("Could not start VLC:\n" + e.getMessage());
        }
    }

    private void newFolder() {
        String name = JOptionPane.showInternalInputDialog(this, "Folder name:",
                "New Folder", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            vfs().mkdirs(Vfs.join(currentDirectory, name.trim()));
            reload();
        }
    }

    private void newDocument() {
        String name = JOptionPane.showInternalInputDialog(this, "Document name:",
                "New Text Document", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            String path = Vfs.join(currentDirectory,
                    name.contains(".") ? name.trim() : name.trim() + ".txt");
            vfs().write(path, "");
            reload();
            shell.openFile(path);
        }
    }

    private void renameSelected() {
        String path = selected();
        if (path == null) {
            return;
        }
        String name = (String) JOptionPane.showInternalInputDialog(this, "New name:",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, Vfs.name(path));
        if (name != null && !name.isBlank()) {
            vfs().move(path, Vfs.join(Vfs.parent(path), name.trim()));
            reload();
        }
    }

    private void duplicateSelected() {
        String path = selected();
        if (path == null) {
            return;
        }
        String target = vfs().uniqueName(Vfs.parent(path), "copy of " + Vfs.name(path));
        vfs().copy(path, target);
        reload();
    }

    private void deleteSelected() {
        String path = selected();
        if (path == null) {
            return;
        }
        if (Vfs.isRoot(path) || Vfs.isDrive(path)) {
            error("A drive is not something this can delete.");
            return;
        }
        // These are the user's own files now, not a sandbox: say which of the
        // two things is about to happen, because only one of them is undoable.
        String fate = Vfs.hasWastebasket()
                ? "\n\nIt goes to the system wastebasket, so you can put it back."
                : "\n\nThere is no wastebasket on this system, so this is permanent.";
        int confirm = JOptionPane.showInternalConfirmDialog(this,
                "Delete " + Vfs.name(path) + "?"
                        + (vfs().isDirectory(path) ? "\nThe folder and its contents go too." : "")
                        + fate,
                "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            vfs().delete(path);
            reload();
        }
    }

    private void showProperties() {
        String path = selected();
        if (path == null) {
            path = currentDirectory;
        }
        String kind = vfs().isDirectory(path) ? "Folder" : Vfs.extension(path).toUpperCase()
                + " file";
        JOptionPane.showInternalMessageDialog(this,
                "Name:      " + Vfs.name(path) + "\n"
                        + "Location:  " + Vfs.parent(path) + "\n"
                        + "Type:      " + kind + "\n"
                        + "Size:      " + Vfs.humanSize(vfs().size(path)) + "\n"
                        + "Modified:  " + STAMP.format(new java.util.Date(vfs().modified(path))),
                "Properties", JOptionPane.INFORMATION_MESSAGE,
                iconFor(path, 32));
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private Action action(String name, Icon icon, Runnable body) {
        Action a = new AbstractAction(name, icon) {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                body.run();
            }
        };
        a.putValue(Action.SHORT_DESCRIPTION, name);
        return a;
    }

    // ---- tree ----------------------------------------------------------

    private String pathOf(TreePath treePath) {
        StringBuilder sb = new StringBuilder();
        Object[] nodes = treePath.getPath();
        for (int i = 1; i < nodes.length; i++) {
            sb.append('/').append(nodes[i].toString());
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private void fillChildren(DefaultMutableTreeNode node, String dir) {
        node.removeAllChildren();
        for (String entry : vfs().list(dir)) {
            if (vfs().isDirectory(entry)) {
                DefaultMutableTreeNode child = new DefaultMutableTreeNode(Vfs.name(entry));
                // A placeholder keeps the handle visible until the node is expanded.
                if (!vfs().list(entry).isEmpty()) {
                    child.add(new DefaultMutableTreeNode("..."));
                }
                node.add(child);
            }
        }
        treeModel.nodeStructureChanged(node);
    }

    private Icon iconFor(String path, int size) {
        if (Vfs.isRoot(path)) {
            return Icons.computer(size);
        }
        // A whole drive gets the disk, not a manila folder.
        if (Vfs.isDrive(path)) {
            return Icons.disk(size);
        }
        if (vfs().isDirectory(path)) {
            return Icons.folder(size);
        }
        return switch (Vfs.extension(path)) {
            case "txt", "log", "md", "properties" -> Icons.document(size);
            case "rtf", "odt", "docx", "doc" -> Icons.textDocument(size);
            case "csv", "tsv", "ods", "xlsx", "xls" -> Icons.spreadsheet(size);
            case "png", "jpg", "jpeg", "gif" -> Icons.image(size);
            default -> Icons.document(size);
        };
    }

    // ---- renderers -----------------------------------------------------

    private class IconRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            String path = String.valueOf(value);
            setText("<html><center>" + Vfs.name(path) + "</center></html>");
            setIcon(iconFor(path, 32));
            setHorizontalAlignment(CENTER);
            setHorizontalTextPosition(CENTER);
            setVerticalTextPosition(BOTTOM);
            setFont(new Font("Dialog", Font.PLAIN, 11));
            setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
            return this;
        }
    }

    private class DetailsRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            setIcon(column == 0 ? iconFor(detailsModel.pathAt(row), 16) : null);
            setHorizontalAlignment(column == 1 ? RIGHT : LEFT);
            return this;
        }
    }

    private class FolderRenderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree t, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean focused) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focused);
            setIcon(expanded ? Icons.folderOpen(16) : Icons.folder(16));
            setFont(new Font("Dialog", Font.PLAIN, 12));
            return this;
        }
    }

    private class DetailsModel extends AbstractTableModel {
        private final String[] columns = {"Name", "Size", "Type", "Modified"};
        private List<String> entries = new ArrayList<>();

        void setEntries(List<String> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        String pathAt(int row) {
            return row >= 0 && row < entries.size() ? entries.get(row) : null;
        }

        @Override public int getRowCount() {
            return entries.size();
        }

        @Override public int getColumnCount() {
            return columns.length;
        }

        @Override public String getColumnName(int column) {
            return columns[column];
        }

        @Override public Object getValueAt(int row, int column) {
            String path = entries.get(row);
            boolean dir = vfs().isDirectory(path);
            return switch (column) {
                case 0 -> Vfs.name(path);
                case 1 -> dir ? "" : Vfs.humanSize(vfs().size(path));
                case 2 -> dir ? "Folder" : (Vfs.extension(path).isEmpty()
                        ? "File" : Vfs.extension(path).toUpperCase() + " file");
                default -> STAMP.format(new java.util.Date(vfs().modified(path)));
            };
        }
    }

    /** Shared double-click handler for both views. */
    private class OpenOnDoubleClick extends MouseAdapter {
        private final java.util.function.Supplier<String> target;

        OpenOnDoubleClick(java.util.function.Supplier<String> target) {
            this.target = target;
        }

        @Override public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                open(target.get());
            }
        }
    }
}
