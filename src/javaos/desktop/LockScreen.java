package javaos.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import javaos.Settings;
import javaos.Version;
import javaos.ui.JavaLogo;
import javaos.ui.Ui;

/**
 * The locked session: an opaque sheet over the whole frame, with the desktop
 * and its windows sealed behind it.
 *
 * <p>It lives as the frame's glass pane, which is what makes the lock a state
 * rather than a window -- nothing underneath can be clicked, focused or read,
 * and the desktop's own keyboard shortcuts step aside while it is up. The
 * backdrop is the session's own wallpaper, dimmed, so it is recognisably the
 * same machine waiting for its user to come back.
 *
 * <p>A passphrase is optional. Without one the screen is a privacy sheet and
 * any key opens it; with one, {@link Settings#unlocks(char[])} decides.
 */
public class LockScreen extends JPanel {

    private final Settings settings;
    private final Runnable onUnlock;

    private final JPasswordField passphrase = new JPasswordField(16);
    private final JLabel clock = new JLabel("", SwingConstants.CENTER);
    private final JLabel date = new JLabel("", SwingConstants.CENTER);
    private final JLabel message = new JLabel(" ", SwingConstants.CENTER);
    private final Timer tick = new Timer(1000, e -> tickClock());
    private int refusals;

    public LockScreen(Settings settings, Runnable onUnlock) {
        this.settings = settings;
        this.onUnlock = onUnlock;
        setLayout(new GridBagLayout());
        setOpaque(true);

        // The glass pane only swallows input if something is listening for it.
        MouseAdapter swallow = new MouseAdapter() {
        };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(e -> { });

        add(panel(), new GridBagConstraints());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "unlock", this::attempt);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear", () -> {
            passphrase.setText("");
            message.setText(" ");
        });
        tickClock();
    }

    /** The raised plate in the middle: logo, who is logged in, and the way back in. */
    private JComponent panel() {
        JPanel plate = new JPanel(new BorderLayout(12, 10));
        plate.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(16, 18, 14, 18)));

        JComponent logo = new JComponent() {
            @Override public Dimension getPreferredSize() {
                return new Dimension(76, 84);
            }

            @Override protected void paintComponent(Graphics g) {
                JavaLogo.paintAt((Graphics2D) g, 4, 2, 80);
            }
        };

        JLabel title = new JLabel(Version.NAME);
        title.setFont(new Font("Dialog", Font.BOLD, 24));
        JLabel locked = new JLabel("This session is locked.");
        locked.setFont(new Font("Dialog", Font.PLAIN, 12));

        clock.setFont(new Font("Dialog", Font.BOLD, 30));
        date.setFont(new Font("Dialog", Font.PLAIN, 11));

        Box heading = Box.createVerticalBox();
        for (JComponent c : new JComponent[] {title, locked}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            heading.add(c);
        }

        JPanel entry = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        JLabel who = new JLabel("Locked by " + settings.userName() + ":");
        who.setFont(new Font("Dialog", Font.BOLD, 12));
        passphrase.setFont(new Font("Dialog", Font.PLAIN, 13));
        passphrase.addActionListener(e -> attempt());
        JButton unlock = new JButton("Unlock");
        unlock.addActionListener(e -> attempt());
        entry.add(who);
        entry.add(passphrase);
        entry.add(unlock);

        message.setFont(new Font("Dialog", Font.PLAIN, 11));
        message.setHorizontalAlignment(SwingConstants.LEFT);
        message.setForeground(new Color(0x8E, 0x1D, 0x18));

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(entry, BorderLayout.NORTH);
        south.add(message, BorderLayout.CENTER);
        south.add(hint(), BorderLayout.SOUTH);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.add(heading, BorderLayout.NORTH);
        JPanel clockBox = new JPanel(new BorderLayout());
        clockBox.add(clock, BorderLayout.CENTER);
        clockBox.add(date, BorderLayout.SOUTH);
        clockBox.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        middle.add(clockBox, BorderLayout.CENTER);

        plate.add(logo, BorderLayout.WEST);
        plate.add(middle, BorderLayout.CENTER);
        plate.add(south, BorderLayout.SOUTH);
        return plate;
    }

    private JLabel hint() {
        JLabel hint = new JLabel(settings.hasLockPassphrase()
                ? "Enter your passphrase to return to the session."
                : "No passphrase is set: press Enter to return to the session.");
        hint.setFont(new Font("Dialog", Font.ITALIC, 11));
        return hint;
    }

    /** Called when the desktop puts this screen up. */
    void engage() {
        refusals = 0;
        message.setText(" ");
        passphrase.setText("");
        tickClock();
        tick.start();
        // The field is not in the hierarchy until the glass pane shows, so ask
        // for focus once Swing has finished making it visible.
        javax.swing.SwingUtilities.invokeLater(passphrase::requestFocusInWindow);
    }

    /** Called when the session is released, so nothing keeps ticking behind it. */
    void release() {
        tick.stop();
        passphrase.setText("");
    }

    private void attempt() {
        char[] guess = passphrase.getPassword();
        boolean opened = settings.unlocks(guess);
        Arrays.fill(guess, '\0');
        if (opened) {
            onUnlock.run();
            return;
        }
        refusals++;
        message.setText(refusals == 1
                ? "That passphrase was not recognised."
                : "That passphrase was not recognised. (" + refusals + " attempts)");
        passphrase.setText("");
        passphrase.requestFocusInWindow();
        java.awt.Toolkit.getDefaultToolkit().beep();
    }

    private void tickClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(settings.clock24h() ? "HH:mm" : "h:mm a").format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM yyyy").format(now));
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.smooth(g);
        int w = getWidth();
        int h = getHeight();
        DesktopPane.paintWallpaper(g2, w, h, settings.wallpaper());

        // Dimmed hard enough that no window shape reads through the sheet.
        g2.setColor(new Color(0, 0, 0, 150));
        g2.fillRect(0, 0, w, h);

        java.awt.Composite old = g2.getComposite();
        g2.setComposite(java.awt.AlphaComposite.getInstance(
                java.awt.AlphaComposite.SRC_OVER, 0.14f));
        int logo = Math.min(w, h) / 2;
        JavaLogo.paintAt(g2, (w - JavaLogo.width(logo)) / 2, (h - JavaLogo.height(logo)) / 2, logo);
        g2.setComposite(old);
        g2.dispose();
    }
}
