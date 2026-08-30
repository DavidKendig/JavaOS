package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import javaos.Settings;
import javaos.desktop.Shell;
import javaos.interop.OfficeSuite;
import javaos.ui.Icons;
import javaos.ui.SunTheme;
import javaos.ui.Ui;

/** Control Panel: theme, backdrop, clock and window behaviour, applied live. */
public class SettingsApp extends AppWindow {

    private final Settings settings;
    private final ButtonGroup themeGroup = new ButtonGroup();
    private final JComboBox<Settings.Wallpaper> wallpaperBox =
            new JComboBox<>(Settings.Wallpaper.values());
    private final JCheckBox showLogo = new JCheckBox("Show the Java watermark on the backdrop");
    private final JCheckBox clock24h = new JCheckBox("24-hour clock");
    private final JCheckBox showSeconds = new JCheckBox("Show seconds");
    private final JCheckBox outlineDrag = new JCheckBox("Drag windows as an outline (faster)");
    private final JCheckBox splash = new JCheckBox("Show the splash screen at start-up");
    private final JCheckBox preferOffice = new JCheckBox(
            "Open documents in Microsoft Office or LibreOffice when installed");
    private final JPanel suiteReport = new JPanel();
    private final JTextField userName = new JTextField(14);
    private final JLabel lockState = new JLabel();
    private final Preview preview = new Preview();

    public SettingsApp(Shell shell, String argument) {
        super(shell, "Control Panel", Icons.settings(16));
        this.settings = shell.settings();
        setSize(520, 400);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Appearance", Icons.paint(16), appearanceTab());
        tabs.addTab("Desktop", Icons.computer(16), desktopTab());
        tabs.addTab("Office", Icons.officeWord(16), officeTab());
        tabs.addTab("Clock", Icons.info(16), clockTab());
        tabs.addTab("Account", Icons.dukeIcon(16), accountTab());

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 6));
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> apply());
        JButton close = new JButton("Close");
        close.addActionListener(e -> doDefaultCloseAction());
        buttons.add(apply);
        buttons.add(close);

        JPanel root = new JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setBody(root);

        load();
        if ("desktop".equals(argument)) {
            tabs.setSelectedIndex(1);
        } else if ("office".equals(argument)) {
            tabs.setSelectedIndex(2);
        } else if ("clock".equals(argument)) {
            tabs.setSelectedIndex(3);
        }
        status("Changes take effect when you press Apply.");
    }

    private JComponent appearanceTab() {
        JPanel themes = new JPanel();
        themes.setLayout(new BoxLayout(themes, BoxLayout.Y_AXIS));
        themes.setBorder(BorderFactory.createTitledBorder("Colour scheme"));
        for (SunTheme.Flavor flavor : SunTheme.Flavor.values()) {
            JRadioButton radio = new JRadioButton(flavor.label);
            radio.setActionCommand(flavor.name());
            radio.addActionListener(e -> preview.setFlavor(flavor));
            themeGroup.add(radio);
            themes.add(radio);
        }
        themes.add(javax.swing.Box.createVerticalGlue());

        // The preview keeps its own size so the mock window stays in proportion.
        JPanel previewBox = new JPanel(new java.awt.GridBagLayout());
        previewBox.setBorder(BorderFactory.createTitledBorder("Preview"));
        previewBox.add(preview);

        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(themes);
        panel.add(previewBox);
        return panel;
    }

    private JComponent desktopTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        row.setAlignmentX(0f);
        row.add(new JLabel("Backdrop:"));
        wallpaperBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(((Settings.Wallpaper) value).label);
                return this;
            }
        });
        row.add(wallpaperBox);
        panel.add(row);
        panel.add(javax.swing.Box.createVerticalStrut(8));
        showLogo.setAlignmentX(0f);
        panel.add(showLogo);
        outlineDrag.setAlignmentX(0f);
        panel.add(outlineDrag);
        splash.setAlignmentX(0f);
        panel.add(splash);
        panel.add(javax.swing.Box.createVerticalGlue());
        return panel;
    }

    /**
     * Which office application opens a document. JavaOS looks for Microsoft
     * Office first, then LibreOffice, then falls back to its own editors; this
     * tab shows what it found and lets the user pin everything to JavaOS
     * instead.
     */
    private JComponent officeTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        preferOffice.setAlignmentX(0f);
        panel.add(preferOffice);
        JLabel note = new JLabel("<html>Off keeps every document inside JavaOS, which reads"
                + " and writes ODF and OOXML<br>text and spreadsheets on its own."
                + " Presentations and databases need a suite either way.</html>");
        note.setFont(new Font("Dialog", Font.PLAIN, 11));
        note.setAlignmentX(0f);
        panel.add(note);
        panel.add(javax.swing.Box.createVerticalStrut(10));

        suiteReport.setLayout(new BoxLayout(suiteReport, BoxLayout.Y_AXIS));
        suiteReport.setBorder(BorderFactory.createTitledBorder("Installed on this machine"));
        suiteReport.setAlignmentX(0f);
        panel.add(suiteReport);

        JButton again = new JButton("Check Again", Icons.officeHandover(16));
        again.setAlignmentX(0f);
        again.addActionListener(e -> {
            OfficeSuite.refresh();
            showSuites();
            status("Looked for Microsoft Office and LibreOffice again.");
        });
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
        buttons.setAlignmentX(0f);
        buttons.add(again);
        panel.add(buttons);
        panel.add(javax.swing.Box.createVerticalGlue());
        showSuites();
        return panel;
    }

    /** One line per office role, saying who would take it. */
    private void showSuites() {
        suiteReport.removeAll();
        for (OfficeSuite.Program program : OfficeSuite.Program.values()) {
            String found = OfficeSuite.handlerFor(program)
                    .map(OfficeSuite.Handler::describe)
                    .orElse(program.hasNativeApp()
                            ? "not installed -- JavaOS opens these itself"
                            : "not installed -- JavaOS has no editor for these");
            JLabel line = new JLabel(program.label + ": " + found,
                    SuiteApp.icon(program, 16), JLabel.LEADING);
            line.setFont(new Font("Dialog", Font.PLAIN, 11));
            line.setAlignmentX(0f);
            suiteReport.add(line);
        }
        suiteReport.revalidate();
        suiteReport.repaint();
    }

    private JComponent clockTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        clock24h.setAlignmentX(0f);
        showSeconds.setAlignmentX(0f);
        panel.add(clock24h);
        panel.add(showSeconds);
        panel.add(javax.swing.Box.createVerticalStrut(10));
        JLabel note = new JLabel("The taskbar clock updates once a second either way.");
        note.setFont(new Font("Dialog", Font.PLAIN, 11));
        note.setAlignmentX(0f);
        panel.add(note);
        panel.add(javax.swing.Box.createVerticalGlue());
        return panel;
    }

    private JComponent accountTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel fields = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 6));
        fields.add(new JLabel("User name:"));
        fields.add(userName);

        JLabel portrait = new JLabel(Icons.dukeIcon(64));
        portrait.setBorder(BorderFactory.createLoweredBevelBorder());

        JPanel text = new JPanel(new BorderLayout());
        text.add(fields, BorderLayout.NORTH);
        JLabel note = new JLabel("<html>The name appears in the shell prompt,"
                + " in Writer signatures,<br>and on the About screen.</html>");
        note.setFont(new Font("Dialog", Font.PLAIN, 11));
        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(note, BorderLayout.NORTH);
        middle.add(screenLock(), BorderLayout.CENTER);
        text.add(middle, BorderLayout.CENTER);

        panel.add(portrait, BorderLayout.WEST);
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    /** Screen lock: the passphrase it asks for, and a way to raise it now. */
    private JComponent screenLock() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Screen lock"));

        lockState.setFont(new Font("Dialog", Font.PLAIN, 11));
        lockState.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        panel.add(lockState);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        buttons.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        JButton set = new JButton("Set Passphrase...");
        set.addActionListener(e -> setPassphrase());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            settings.clearLockPassphrase();
            settings.save();
            showLockState();
            status("The screen lock no longer asks for a passphrase.");
        });
        JButton now = new JButton("Lock Now");
        now.addActionListener(e -> shell.lockScreen());
        buttons.add(set);
        buttons.add(clear);
        buttons.add(now);
        panel.add(buttons);
        showLockState();
        return panel;
    }

    /**
     * Asks for the new passphrase twice. Nothing keeps the characters: they go
     * straight to the settings, which store a salt and a derivation of them.
     */
    private void setPassphrase() {
        javax.swing.JPasswordField first = new javax.swing.JPasswordField(16);
        javax.swing.JPasswordField second = new javax.swing.JPasswordField(16);
        JPanel form = new JPanel(new GridLayout(2, 2, 6, 6));
        form.add(new JLabel("New passphrase:"));
        form.add(first);
        form.add(new JLabel("Repeat it:"));
        form.add(second);

        int choice = javax.swing.JOptionPane.showConfirmDialog(this, form,
                "Screen lock passphrase", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        char[] one = first.getPassword();
        char[] two = second.getPassword();
        try {
            if (choice != javax.swing.JOptionPane.OK_OPTION) {
                return;
            }
            if (one.length == 0) {
                status("An empty passphrase leaves the lock screen open to any key.");
                return;
            }
            if (!java.util.Arrays.equals(one, two)) {
                javax.swing.JOptionPane.showMessageDialog(this,
                        "Those two do not match.", "Screen lock passphrase",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }
            settings.setLockPassphrase(one);
            settings.save();
            showLockState();
            status("The screen lock will ask for the new passphrase.");
        } finally {
            java.util.Arrays.fill(one, ' ');
            java.util.Arrays.fill(two, ' ');
        }
    }

    private void showLockState() {
        lockState.setText(settings.hasLockPassphrase()
                ? "A passphrase is set. The lock screen asks for it before unlocking."
                : "No passphrase set. The lock screen hides the session; any key opens it.");
    }

    private void load() {
        java.util.Enumeration<javax.swing.AbstractButton> buttons = themeGroup.getElements();
        while (buttons.hasMoreElements()) {
            javax.swing.AbstractButton button = buttons.nextElement();
            if (button.getActionCommand().equals(settings.theme().name())) {
                button.setSelected(true);
            }
        }
        preview.setFlavor(settings.theme());
        wallpaperBox.setSelectedItem(settings.wallpaper());
        showLogo.setSelected(settings.showLogo());
        clock24h.setSelected(settings.clock24h());
        showSeconds.setSelected(settings.showSeconds());
        outlineDrag.setSelected(settings.outlineDrag());
        splash.setSelected(settings.showSplash());
        preferOffice.setSelected(settings.preferInstalledOffice());
        userName.setText(settings.userName());
    }

    private void apply() {
        if (themeGroup.getSelection() != null) {
            settings.setTheme(SunTheme.Flavor.byName(
                    themeGroup.getSelection().getActionCommand()));
        }
        settings.setWallpaper((Settings.Wallpaper) wallpaperBox.getSelectedItem());
        settings.setShowLogo(showLogo.isSelected());
        settings.setClock24h(clock24h.isSelected());
        settings.setShowSeconds(showSeconds.isSelected());
        settings.setOutlineDrag(outlineDrag.isSelected());
        settings.setShowSplash(splash.isSelected());
        settings.setPreferInstalledOffice(preferOffice.isSelected());
        String name = userName.getText().trim();
        settings.setUserName(name.isEmpty() ? "duke" : name);
        settings.save();
        settings.fireChanged();
        status("Settings applied.");
    }

    /** A miniature window painted in the selected flavour, before it is committed. */
    private static class Preview extends JComponent {
        private SunTheme.Flavor flavor = SunTheme.Flavor.STEEL;

        Preview() {
            setPreferredSize(new Dimension(180, 120));
        }

        void setFlavor(SunTheme.Flavor flavor) {
            this.flavor = flavor;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            Color primary = flavor.primary();
            Color primaryDark = flavor.primaryDark();
            Color control = flavor.control();
            int w = getWidth();
            int h = getHeight();

            g2.setColor(Ui.darker(primaryDark, 0.4));
            g2.fillRect(0, 0, w, h);

            int x = 14;
            int y = 18;
            int ww = w - 28;
            int hh = h - 40;
            g2.setColor(control);
            g2.fillRect(x, y, ww, hh);
            Ui.bevel(g2, x, y, ww, hh, true);
            Ui.hGradient(g2, x + 2, y + 2, ww - 4, 16, primaryDark, primary);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Dialog", Font.BOLD, 10));
            g2.drawString("Document", x + 6, y + 14);
            g2.setColor(Color.WHITE);
            g2.fillRect(x + 6, y + 24, ww - 12, hh - 32);
            g2.setColor(Ui.mix(primary, Color.WHITE, 0.4));
            for (int i = 0; i < 4; i++) {
                g2.fillRect(x + 12, y + 32 + i * 8, ww - 30 - i * 6, 3);
            }
            g2.dispose();
        }
    }
}
