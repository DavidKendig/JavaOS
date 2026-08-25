package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import javaos.ui.Icons;
import javaos.vfs.Vfs;

/**
 * The system file dialog. JFileChooser would browse the host machine, and the
 * whole point of the volume is that applications cannot.
 */
public final class VfsChooser extends JPanel {

    private final Vfs vfs;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JTextField nameField = new JTextField(22);
    private final JLabel location = new JLabel();
    private final List<String> extensions;

    private String directory;

    private VfsChooser(Vfs vfs, String startDirectory, String defaultName,
            List<String> extensions) {
        this.vfs = vfs;
        this.extensions = extensions;
        setLayout(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(430, 320));

        JButton up = new JButton("Up", Icons.up(16));
        up.setMargin(new java.awt.Insets(1, 4, 1, 6));
        up.addActionListener(e -> setDirectory(Vfs.parent(directory)));
        JButton home = new JButton("Home", Icons.folderOpen(16));
        home.setMargin(new java.awt.Insets(1, 4, 1, 6));
        home.addActionListener(e -> setDirectory(Vfs.HOME));

        JPanel north = new JPanel(new BorderLayout(6, 0));
        location.setFont(new Font("Dialog", Font.BOLD, 11));
        location.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        JPanel navButtons = new JPanel(new GridLayout(1, 2, 3, 0));
        navButtons.add(up);
        navButtons.add(home);
        north.add(new JLabel("Look in:"), BorderLayout.WEST);
        north.add(location, BorderLayout.CENTER);
        north.add(navButtons, BorderLayout.EAST);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer());
        list.setVisibleRowCount(12);
        list.addListSelectionListener(e -> {
            String selected = list.getSelectedValue();
            if (selected != null && !vfs.isDirectory(selected)) {
                nameField.setText(Vfs.name(selected));
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String selected = list.getSelectedValue();
                if (e.getClickCount() == 2 && selected != null && vfs.isDirectory(selected)) {
                    setDirectory(selected);
                }
            }
        });

        JPanel south = new JPanel(new BorderLayout(6, 0));
        south.add(new JLabel("File name:"), BorderLayout.WEST);
        south.add(nameField, BorderLayout.CENTER);

        add(north, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        nameField.setText(defaultName == null ? "" : defaultName);
        setDirectory(startDirectory);
    }

    private void setDirectory(String dir) {
        directory = vfs.isDirectory(dir) ? Vfs.normalize(dir) : Vfs.parent(dir);
        location.setText(directory);
        model.clear();
        for (String path : vfs.list(directory)) {
            if (vfs.isDirectory(path) || accepts(path)) {
                model.addElement(path);
            }
        }
    }

    private boolean accepts(String path) {
        return extensions.isEmpty() || extensions.contains(Vfs.extension(path));
    }

    private String chosenPath() {
        String typed = nameField.getText().trim();
        if (typed.isEmpty()) {
            String selected = list.getSelectedValue();
            return selected != null && !vfs.isDirectory(selected) ? selected : null;
        }
        return Vfs.resolve(directory, typed);
    }

    // ---- entry points --------------------------------------------------

    public static String open(Component parent, Vfs vfs, String startDirectory,
            String title, String... extensions) {
        VfsChooser chooser = new VfsChooser(vfs, startDirectory, "", List.of(extensions));
        int result = JOptionPane.showInternalConfirmDialog(parent, chooser, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        String path = chooser.chosenPath();
        if (path == null) {
            return null;
        }
        if (!vfs.exists(path)) {
            JOptionPane.showInternalMessageDialog(parent,
                    Vfs.name(path) + " does not exist.", title, JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return path;
    }

    public static String save(Component parent, Vfs vfs, String startDirectory,
            String defaultName, String title, String... extensions) {
        VfsChooser chooser = new VfsChooser(vfs, startDirectory, defaultName,
                List.of(extensions));
        int result = JOptionPane.showInternalConfirmDialog(parent, chooser, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        String path = chooser.chosenPath();
        if (path == null) {
            return null;
        }
        if (extensions.length > 0 && Vfs.extension(path).isEmpty()) {
            path = path + "." + extensions[0];
        }
        if (vfs.exists(path)) {
            int overwrite = JOptionPane.showInternalConfirmDialog(parent,
                    Vfs.name(path) + " already exists.\nReplace it?",
                    title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        return path;
    }

    /** Shows just names, with a folder or document icon in front. */
    private class EntryRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> jList, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(jList, value, index, selected, focused);
            String path = String.valueOf(value);
            setText(Vfs.name(path));
            setIcon(vfs.isDirectory(path) ? Icons.folder(16) : Icons.document(16));
            setFont(new Font("Dialog", Font.PLAIN, 12));
            return this;
        }
    }
}
