package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;

import javaos.desktop.Shell;
import javaos.media.Media;
import javaos.media.MidiPlayer;
import javaos.media.Player;
import javaos.media.SampledPlayer;
import javaos.ui.Icons;
import javaos.ui.Ui;
import javaos.vfs.Vfs;
import javaos.vlc.Vlc;

/**
 * The media player: a queue down the side, a lit display in the middle, and a
 * transport along the top.
 *
 * <p>It sounds what the JDK can decode -- see {@link javaos.media} -- and hands
 * everything else to VLC when the user has it. A file it cannot play is still
 * accepted into the playlist and still says what it is; it simply says so
 * instead of pretending to load.
 */
public class MediaPlayerApp extends AppWindow {

    /** How often the display and the meter refresh. 20 Hz is smooth enough. */
    private static final int TICK_MS = 50;

    private static final int SEEK_RESOLUTION = 1000;

    private final DefaultListModel<String> queue = new DefaultListModel<>();
    private final JList<String> playlist = new JList<>(queue);
    private final Display display = new Display();
    private final JSlider seek = new JSlider(0, SEEK_RESOLUTION, 0);
    private final JSlider volume = new JSlider(0, 100, 80);
    private final Timer ticker = new Timer(TICK_MS, e -> tick());

    private final Action playAction = action("Play", Icons.mediaPlay(20), this::playOrPause);
    private final Action stopAction = action("Stop", Icons.mediaStop(20), this::stopPlayback);
    private final Action previousAction =
            action("Previous", Icons.mediaPrevious(20), () -> step(-1));
    private final Action nextAction = action("Next", Icons.mediaNext(20), () -> step(1));
    private final Action vlcAction = action("Open in VLC", Icons.mediaVlc(20), this::openInVlc);

    private Player player;
    private String loaded;
    private boolean seeking;

    public MediaPlayerApp(Shell shell, String argument) {
        super(shell, "Media Player", Icons.media(16));
        setSize(700, 460);

        setJMenuBar(menuBar());
        addToolBar(transportBar());
        setBody(buildLayout());

        playlist.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlist.setCellRenderer(new TrackRenderer());
        playlist.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    playSelected();
                }
            }
        });

        seek.setEnabled(false);
        seek.addChangeListener(e -> {
            if (seek.getValueIsAdjusting()) {
                seeking = true;
            } else if (seeking) {
                seeking = false;
                seekToSlider();
            }
        });
        volume.setPreferredSize(new Dimension(90, 22));
        volume.setMaximumSize(new Dimension(90, 22));
        volume.setToolTipText("Volume");
        volume.addChangeListener(e -> {
            if (player != null) {
                player.setVolume(volume.getValue() / 100f);
            }
        });

        ticker.start();
        refreshActions();

        if (argument != null && vfs().exists(argument)) {
            enqueueFolderOf(argument);
            open(argument);
        } else {
            enqueueFolder(Vfs.HOME + "/Music");
            status(queue.isEmpty()
                    ? "Playlist empty. Open a file to begin."
                    : queue.size() + " item(s) in /home/duke/Music.");
        }
    }

    // ---- construction --------------------------------------------------

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Open...", this::openFile));
        file.add(item("Add Folder...", this::addFolder));
        file.addSeparator();
        file.add(item("Open in VLC", this::openInVlc));
        file.add(item("Send Playlist to VLC", this::sendQueueToVlc));
        file.addSeparator();
        file.add(item("Close", this::closeWindow));
        bar.add(file);

        JMenu playback = new JMenu("Playback");
        playback.add(new JMenuItem(playAction));
        playback.add(new JMenuItem(stopAction));
        playback.addSeparator();
        playback.add(new JMenuItem(previousAction));
        playback.add(new JMenuItem(nextAction));
        bar.add(playback);

        JMenu list = new JMenu("Playlist");
        list.add(item("Remove Selected", this::removeSelected));
        list.add(item("Clear", this::clearQueue));
        bar.add(list);

        JMenu help = new JMenu("Help");
        help.add(item("Which Formats?", this::explainFormats));
        help.add(item("About VLC Support", this::explainVlc));
        bar.add(help);
        return bar;
    }

    private JToolBar transportBar() {
        JToolBar bar = toolBar();
        bar.add(tool(action("Open...", Icons.folderOpen(20), this::openFile)));
        gap(bar);
        bar.add(tool(previousAction));
        bar.add(tool(playAction));
        bar.add(tool(stopAction));
        bar.add(tool(nextAction));
        gap(bar);
        bar.add(new JLabel(" Volume "));
        bar.add(volume);
        gap(bar);
        bar.add(tool(vlcAction));
        return bar;
    }

    private JComponent buildLayout() {
        JPanel screen = new JPanel(new BorderLayout(0, 3));
        screen.add(display, BorderLayout.CENTER);
        screen.add(seek, BorderLayout.SOUTH);
        screen.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JScrollPane scroll = new JScrollPane(playlist);
        scroll.setPreferredSize(new Dimension(210, 100));
        scroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Playlist"),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));

        JPanel root = new JPanel(new BorderLayout(4, 0));
        root.add(screen, BorderLayout.CENTER);
        root.add(scroll, BorderLayout.EAST);
        return root;
    }

    // ---- opening -------------------------------------------------------

    private void openFile() {
        String path = VfsChooser.open(this, vfs(), Vfs.HOME, "Open Media",
                Media.allExtensions());
        if (path != null) {
            enqueue(path);
            open(path);
        }
    }

    private void addFolder() {
        String path = VfsChooser.open(this, vfs(), Vfs.HOME, "Add Folder");
        if (path == null) {
            return;
        }
        String folder = vfs().isDirectory(path) ? path : Vfs.parent(path);
        int before = queue.size();
        enqueueFolder(folder);
        status("Added " + (queue.size() - before) + " item(s) from " + folder + ".");
    }

    /** Fills the queue from the folder a file lives in, then selects that file. */
    private void enqueueFolderOf(String path) {
        enqueueFolder(Vfs.parent(path));
        enqueue(path);
    }

    private void enqueueFolder(String folder) {
        if (!vfs().isDirectory(folder)) {
            return;
        }
        for (String child : vfs().list(folder)) {
            if (!vfs().isDirectory(child) && Media.isMedia(child)) {
                enqueue(child);
            }
        }
    }

    private void enqueue(String path) {
        if (!queue.contains(path)) {
            queue.addElement(path);
        }
    }

    /** Loads a track, or explains why it cannot be loaded. */
    private void open(String path) {
        release();
        loaded = path;
        enqueue(path);
        playlist.setSelectedValue(path, true);
        setDocumentPath(path);

        Media.Kind kind = Media.kindOf(path);
        if (kind == Media.Kind.HANDOVER) {
            display.show(Vfs.name(path), Media.label(path) + " -- needs VLC to decode", true);
            seek.setValue(0);
            seek.setEnabled(false);
            status(Media.label(path) + " is a format JavaOS cannot decode. "
                    + (Vlc.isAvailable() ? "Use Open in VLC." : "Install VLC to play it."));
            refreshActions();
            return;
        }
        if (kind == Media.Kind.OTHER) {
            display.show(Vfs.name(path), "not a media file", true);
            status(Vfs.name(path) + " is not a media file.");
            refreshActions();
            return;
        }

        File host = vfs().host(path).toFile();
        try {
            player = kind == Media.Kind.MIDI ? new MidiPlayer(host) : new SampledPlayer(host);
        } catch (Exception e) {
            display.show(Vfs.name(path), "could not be opened", true);
            status("Could not open " + Vfs.name(path) + ".");
            error("Could not open " + Vfs.name(path) + ":\n\n" + reason(e)
                    + (Vlc.isAvailable() ? "\n\nVLC is installed -- try Open in VLC." : ""));
            refreshActions();
            return;
        }

        player.onFinished(this::trackFinished);
        player.setVolume(volume.getValue() / 100f);
        display.show(Vfs.name(path), player.describe(), false);
        display.attach(player.levels());
        seek.setEnabled(player.duration() > 0);
        status(player.describe());
        player.play();
        refreshActions();
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : message;
    }

    // ---- transport -----------------------------------------------------

    private void playOrPause() {
        if (player == null) {
            playSelected();
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            status("Paused.");
        } else {
            player.play();
            status("Playing " + Vfs.name(loaded) + ".");
        }
        refreshActions();
    }

    private void playSelected() {
        String path = playlist.getSelectedValue();
        if (path == null && !queue.isEmpty()) {
            path = queue.get(0);
        }
        if (path != null) {
            open(path);
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.stop();
            seek.setValue(0);
            display.reset();
            status("Stopped.");
        }
        refreshActions();
    }

    /** Moves {@code delta} places through the queue and plays what it lands on. */
    private void step(int delta) {
        if (queue.isEmpty()) {
            return;
        }
        int index = loaded == null ? -1 : queue.indexOf(loaded);
        int next = index < 0 ? 0 : index + delta;
        if (next < 0 || next >= queue.size()) {
            status(delta < 0 ? "Start of playlist." : "End of playlist.");
            return;
        }
        open(queue.get(next));
    }

    private void trackFinished() {
        int index = loaded == null ? -1 : queue.indexOf(loaded);
        if (index >= 0 && index + 1 < queue.size()) {
            open(queue.get(index + 1));
        } else {
            stopPlayback();
            status("End of playlist.");
        }
    }

    private void seekToSlider() {
        if (player == null || player.duration() <= 0) {
            return;
        }
        long target = (long) (seek.getValue() / (double) SEEK_RESOLUTION * player.duration());
        player.seek(target);
    }

    /** Drives the clock, the slider and the meter. */
    private void tick() {
        display.repaint();
        if (player == null) {
            return;
        }
        long position = player.position();
        long duration = player.duration();
        display.time(position, duration);
        if (!seeking && duration > 0) {
            seek.setValue((int) (position / (double) duration * SEEK_RESOLUTION));
        }
    }

    // ---- the playlist --------------------------------------------------

    private void removeSelected() {
        String path = playlist.getSelectedValue();
        if (path == null) {
            return;
        }
        if (path.equals(loaded)) {
            release();
            display.reset();
        }
        queue.removeElement(path);
    }

    private void clearQueue() {
        release();
        queue.clear();
        display.reset();
        seek.setValue(0);
        status("Playlist cleared.");
        refreshActions();
    }

    // ---- VLC -----------------------------------------------------------

    /** Hands the loaded track, or the selected one, to VLC. */
    private void openInVlc() {
        String path = playlist.getSelectedValue();
        if (path == null) {
            path = loaded;
        }
        if (path == null) {
            status("Nothing selected.");
            return;
        }
        if (!requireVlc()) {
            return;
        }
        try {
            Vlc.open(vfs().host(path));
            status("Handed " + Vfs.name(path) + " to VLC.");
        } catch (java.io.IOException e) {
            error("Could not start VLC:\n" + e.getMessage());
        }
    }

    /** Hands the whole queue over as one VLC playlist. */
    private void sendQueueToVlc() {
        if (queue.isEmpty()) {
            status("Playlist empty.");
            return;
        }
        if (!requireVlc()) {
            return;
        }
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            files.add(vfs().host(queue.get(i)));
        }
        try {
            Vlc.openAll(files);
            status("Handed " + files.size() + " item(s) to VLC.");
        } catch (java.io.IOException e) {
            error("Could not start VLC:\n" + e.getMessage());
        }
    }

    private boolean requireVlc() {
        if (Vlc.isAvailable()) {
            return true;
        }
        error("""
                VLC was not found on this machine.

                JavaOS never bundles or installs VLC. This menu item only hands
                a file over when you have installed VLC yourself.

                If it is installed somewhere unusual, start JavaOS with
                -Djavaos.vlc=<path to vlc>.""");
        return false;
    }

    // ---- help ----------------------------------------------------------

    private void explainFormats() {
        JOptionPane.showInternalMessageDialog(this, """
                JavaOS plays, on its own, in pure Java:

                  WAV, AU, AIFF    uncompressed PCM, through javax.sound.sampled
                  MID, RMI, KAR    sequences, through the JDK synthesiser

                Everything else on the playlist -- MP3, MP4, MKV, FLAC and the
                rest -- is compressed with codecs the JDK does not carry, and is
                handed to VLC instead.""",
                appName(), JOptionPane.INFORMATION_MESSAGE);
    }

    private void explainVlc() {
        String found = Vlc.find()
                .map(install -> "Found: " + install.executable()
                        + "\nVersion: " + install.version())
                .orElse("VLC was not found on this machine.");
        JOptionPane.showInternalMessageDialog(this, """
                VLC is not part of JavaOS, and JavaOS never installs or updates
                it. When you have installed it yourself, JavaOS finds it and
                offers to hand media over -- the same arrangement Writer and
                Calc have with LibreOffice.

                %s""".formatted(found), appName(), JOptionPane.INFORMATION_MESSAGE);
    }

    // ---- housekeeping --------------------------------------------------

    private void refreshActions() {
        boolean loadedPlayable = player != null;
        playAction.putValue(Action.NAME, loadedPlayable && player.isPlaying() ? "Pause" : "Play");
        playAction.putValue(Action.SMALL_ICON,
                loadedPlayable && player.isPlaying() ? Icons.mediaPause(20) : Icons.mediaPlay(20));
        playAction.putValue(Action.SHORT_DESCRIPTION,
                loadedPlayable && player.isPlaying() ? "Pause" : "Play");
        playAction.setEnabled(loadedPlayable || !queue.isEmpty());
        stopAction.setEnabled(loadedPlayable);
        previousAction.setEnabled(!queue.isEmpty());
        nextAction.setEnabled(!queue.isEmpty());
        vlcAction.setEnabled(!queue.isEmpty());
    }

    /** Closes the engine and lets go of the audio device. */
    private void release() {
        if (player != null) {
            player.close();
            player = null;
        }
        display.attach(null);
    }

    private void closeWindow() {
        doDefaultCloseAction();
        dispose();
    }

    @Override public void dispose() {
        ticker.stop();
        release();
        super.dispose();
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

    private JMenuItem item(String label, Runnable body) {
        JMenuItem menuItem = new JMenuItem(label);
        menuItem.addActionListener(e -> body.run());
        return menuItem;
    }

    /** Track names, with the icon saying whether JavaOS can sound this one. */
    private static class TrackRenderer extends DefaultListCellRenderer {
        @Override public java.awt.Component getListCellRendererComponent(JList<?> list,
                Object value, int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            String path = String.valueOf(value);
            setText(Vfs.name(path));
            setIcon(Media.isPlayable(path) ? Icons.media(16) : Icons.mediaVlc(16));
            setToolTipText(Media.isPlayable(path)
                    ? Media.label(path) + " -- played by JavaOS"
                    : Media.label(path) + " -- needs VLC");
            return this;
        }
    }

    /**
     * The lit panel: what is loaded, what it is, how far in, and a meter fed by
     * whichever engine is running.
     */
    private static class Display extends JComponent {

        private static final Color SCREEN = new Color(0x10, 0x18, 0x14);
        private static final Color LCD = new Color(0x6C, 0xE8, 0x86);
        private static final Color LCD_DIM = new Color(0x39, 0x7A, 0x4A);
        private static final Color WARN = new Color(0xE8, 0xC4, 0x6A);

        private String title = "No media loaded";
        private String subtitle = "Open a file to begin";
        private boolean warning;
        private long position;
        private long duration = -1;
        private Player.Levels levels;
        private float[] bars = new float[0];

        Display() {
            setPreferredSize(new Dimension(400, 200));
            setBorder(Ui.sunkenPanel());
        }

        void show(String title, String subtitle, boolean warning) {
            this.title = title;
            this.subtitle = subtitle;
            this.warning = warning;
            this.position = 0;
            this.duration = -1;
            repaint();
        }

        void attach(Player.Levels levels) {
            this.levels = levels;
            this.bars = new float[levels == null ? 0 : levels.channels()];
        }

        void time(long position, long duration) {
            this.position = position;
            this.duration = duration;
        }

        void reset() {
            position = 0;
            java.util.Arrays.fill(bars, 0f);
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            int w = getWidth();
            int h = getHeight();
            Ui.vGradient(g2, 0, 0, w, h, Ui.lighter(SCREEN, 0.08), SCREEN);

            g2.setFont(new Font(Font.DIALOG, Font.BOLD, 14));
            g2.setColor(warning ? WARN : LCD);
            g2.drawString(fit(g2, title, w - 24), 12, 26);

            g2.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
            g2.setColor(LCD_DIM);
            g2.drawString(fit(g2, subtitle, w - 24), 12, 44);

            drawClock(g2, w);
            drawMeter(g2, w, h);
            g2.dispose();
        }

        private void drawClock(Graphics2D g2, int w) {
            String elapsed = clock(position);
            String total = duration > 0 ? clock(duration) : "--:--";
            g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
            g2.setColor(LCD);
            String text = elapsed;
            int width = g2.getFontMetrics().stringWidth(text);
            g2.drawString(text, w - width - 14, 88);

            g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g2.setColor(LCD_DIM);
            String of = "of " + total;
            g2.drawString(of, w - g2.getFontMetrics().stringWidth(of) - 14, 106);
        }

        /** One bar per channel, decaying so the meter falls rather than flickers. */
        private void drawMeter(Graphics2D g2, int w, int h) {
            if (levels == null || bars.length == 0) {
                return;
            }
            float[] fresh = levels.read(0.06f);
            for (int i = 0; i < bars.length && i < fresh.length; i++) {
                bars[i] = Math.max(fresh[i], bars[i] - 0.05f);
            }

            int top = 124;
            int bottom = h - 14;
            int height = bottom - top;
            if (height < 20) {
                return;
            }
            int gap = bars.length > 8 ? 2 : 6;
            int barWidth = Math.max(3, (w - 24 - gap * (bars.length - 1)) / bars.length);
            int x = 12;
            for (float level : bars) {
                int lit = (int) (level * height);
                g2.setColor(Ui.mix(SCREEN, LCD, 0.12));
                g2.fillRect(x, top, barWidth, height);
                // Segments, so it reads as a meter and not a progress bar.
                for (int y = bottom - 4; y > bottom - lit; y -= 5) {
                    int fromTop = bottom - y;
                    g2.setColor(fromTop > height * 0.85 ? new Color(0xE0, 0x5A, 0x3C)
                            : fromTop > height * 0.6 ? WARN : LCD);
                    g2.fillRect(x + 1, y, barWidth - 2, 3);
                }
                x += barWidth + gap;
            }
        }

        private static String clock(long micros) {
            long seconds = Math.max(0, micros) / 1_000_000L;
            return "%d:%02d".formatted(seconds / 60, seconds % 60);
        }

        private static String fit(Graphics2D g2, String text, int width) {
            if (g2.getFontMetrics().stringWidth(text) <= width) {
                return text;
            }
            String trimmed = text;
            while (trimmed.length() > 1
                    && g2.getFontMetrics().stringWidth(trimmed + "...") > width) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "...";
        }
    }
}
