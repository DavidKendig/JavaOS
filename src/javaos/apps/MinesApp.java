package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.ui.Ui;

/** Mines: the game every desktop of the era shipped, bevels and all. */
public class MinesApp extends AppWindow {

    private static final Color[] NUMBER_COLORS = {
        Color.BLACK, new Color(0x00, 0x00, 0xC0), new Color(0x00, 0x78, 0x00),
        new Color(0xC0, 0x00, 0x00), new Color(0x00, 0x00, 0x78),
        new Color(0x78, 0x00, 0x00), new Color(0x00, 0x78, 0x78),
        Color.BLACK, new Color(0x78, 0x78, 0x78),
    };

    private final Board board = new Board();
    private final Led minesLeft = new Led();
    private final Led elapsed = new Led();
    private final JButton face = new JButton(":-)");
    private final Timer clock = new Timer(1000, e -> tick());

    private int columns = 9;
    private int rows = 9;
    private int mines = 10;
    private int seconds;

    private boolean[][] mine;
    private boolean[][] revealed;
    private boolean[][] flagged;
    private boolean playing;
    private boolean firstClick;

    public MinesApp(Shell shell, String argument) {
        super(shell, "Mines", Icons.mine(16));
        setResizable(false);
        setMaximizable(false);

        face.setFont(new Font("Monospaced", Font.BOLD, 14));
        face.setPreferredSize(new Dimension(34, 30));
        face.setFocusable(false);
        face.addActionListener(e -> newGame(columns, rows, mines));

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 6, 4, 6),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLoweredBevelBorder(),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6))));
        top.add(minesLeft, BorderLayout.WEST);
        top.add(face, BorderLayout.CENTER);
        top.add(elapsed, BorderLayout.EAST);

        JPanel field = new JPanel(new BorderLayout());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 6, 6, 6),
                BorderFactory.createLoweredBevelBorder()));
        field.add(board, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(field, BorderLayout.CENTER);
        setBody(root);
        setJMenuBar(menuBar());

        newGame(9, 9, 10);
    }

    private JMenuBar menuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu game = new JMenu("Game");
        game.add(item("New", () -> newGame(columns, rows, mines)));
        game.addSeparator();
        game.add(item("Beginner  (9 x 9, 10 mines)", () -> newGame(9, 9, 10)));
        game.add(item("Intermediate  (16 x 16, 40 mines)", () -> newGame(16, 16, 40)));
        game.add(item("Expert  (30 x 16, 99 mines)", () -> newGame(30, 16, 99)));
        game.addSeparator();
        game.add(item("Exit", this::doDefaultCloseAction));
        menuBar.add(game);

        JMenu help = new JMenu("Help");
        help.add(item("How to Play", () -> JOptionPane.showInternalMessageDialog(this,
                """
                Left click uncovers a square.
                Right click plants a flag.

                A number tells you how many mines touch that square.
                Uncover every square that is not a mine to win.
                """,
                "Mines", JOptionPane.INFORMATION_MESSAGE)));
        menuBar.add(help);
        return menuBar;
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem menuItem = new JMenuItem(text);
        menuItem.addActionListener(e -> action.run());
        return menuItem;
    }

    private void newGame(int columns, int rows, int mines) {
        this.columns = columns;
        this.rows = rows;
        this.mines = mines;
        mine = new boolean[rows][columns];
        revealed = new boolean[rows][columns];
        flagged = new boolean[rows][columns];
        playing = true;
        firstClick = true;
        seconds = 0;
        clock.stop();
        elapsed.setValue(0);
        minesLeft.setValue(mines);
        face.setText(":-)");
        board.setPreferredSize(new Dimension(columns * Board.CELL, rows * Board.CELL));
        pack();
        status("Left click to uncover, right click to flag.");
        board.repaint();
    }

    private void tick() {
        seconds++;
        elapsed.setValue(Math.min(seconds, 999));
    }

    private void layMines(int safeRow, int safeColumn) {
        Random random = new Random();
        int placed = 0;
        while (placed < mines) {
            int r = random.nextInt(rows);
            int c = random.nextInt(columns);
            if (mine[r][c] || (Math.abs(r - safeRow) <= 1 && Math.abs(c - safeColumn) <= 1)) {
                continue;
            }
            mine[r][c] = true;
            placed++;
        }
    }

    private int neighbours(int row, int column) {
        int count = 0;
        for (int r = row - 1; r <= row + 1; r++) {
            for (int c = column - 1; c <= column + 1; c++) {
                if (r >= 0 && r < rows && c >= 0 && c < columns && mine[r][c]) {
                    count++;
                }
            }
        }
        return count;
    }

    private void uncover(int row, int column) {
        if (row < 0 || row >= rows || column < 0 || column >= columns
                || revealed[row][column] || flagged[row][column]) {
            return;
        }
        revealed[row][column] = true;
        if (mine[row][column]) {
            lose();
            return;
        }
        if (neighbours(row, column) == 0) {
            for (int r = row - 1; r <= row + 1; r++) {
                for (int c = column - 1; c <= column + 1; c++) {
                    uncover(r, c);
                }
            }
        }
    }

    private void lose() {
        playing = false;
        clock.stop();
        face.setText("X-(");
        status("Bad luck. Click the face for a new game.");
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (mine[r][c]) {
                    revealed[r][c] = true;
                }
            }
        }
    }

    private void checkWin() {
        int hidden = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (!revealed[r][c] && !mine[r][c]) {
                    hidden++;
                }
            }
        }
        if (hidden == 0) {
            playing = false;
            clock.stop();
            face.setText("8-)");
            status("Cleared in " + seconds + " seconds.");
        }
    }

    private int flagCount() {
        int count = 0;
        for (boolean[] row : flagged) {
            for (boolean f : row) {
                if (f) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The minefield itself, drawn cell by cell. */
    private class Board extends JComponent {
        static final int CELL = 20;

        Board() {
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!playing) {
                        return;
                    }
                    int column = e.getX() / CELL;
                    int row = e.getY() / CELL;
                    if (row < 0 || row >= rows || column < 0 || column >= columns) {
                        return;
                    }
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        if (!revealed[row][column]) {
                            flagged[row][column] = !flagged[row][column];
                            minesLeft.setValue(mines - flagCount());
                        }
                    } else {
                        if (firstClick) {
                            layMines(row, column);
                            firstClick = false;
                            clock.start();
                        }
                        uncover(row, column);
                        if (playing) {
                            checkWin();
                        }
                    }
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.smooth(g);
            g2.setFont(new Font("Dialog", Font.BOLD, 13));
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    int x = c * CELL;
                    int y = r * CELL;
                    if (revealed[r][c]) {
                        g2.setColor(Ui.control());
                        g2.fillRect(x, y, CELL, CELL);
                        g2.setColor(Ui.shadow());
                        g2.drawRect(x, y, CELL, CELL);
                        if (mine[r][c]) {
                            g2.setColor(new Color(0xB0, 0x20, 0x20));
                            g2.fillRect(x + 1, y + 1, CELL - 1, CELL - 1);
                            Icons.mine(CELL - 6).paintIcon(this, g2, x + 3, y + 3);
                        } else {
                            int n = neighbours(r, c);
                            if (n > 0) {
                                g2.setColor(NUMBER_COLORS[n]);
                                g2.drawString(String.valueOf(n), x + 6, y + CELL - 5);
                            }
                        }
                    } else {
                        g2.setColor(Ui.control());
                        g2.fillRect(x, y, CELL, CELL);
                        Ui.bevel(g2, x, y, CELL, CELL, true);
                        if (flagged[r][c]) {
                            g2.setColor(new Color(0xC0, 0x20, 0x20));
                            g2.fillPolygon(new int[] {x + 6, x + 6, x + 13},
                                    new int[] {y + 4, y + 11, y + 7}, 3);
                            g2.setColor(Color.BLACK);
                            g2.drawLine(x + 6, y + 4, x + 6, y + 15);
                            g2.drawLine(x + 3, y + 15, x + 12, y + 15);
                        }
                    }
                }
            }
            g2.dispose();
        }
    }

    /** Three-digit red LED counter, exactly as tacky as it should be. */
    private static class Led extends JLabel {
        Led() {
            super("000", SwingConstants.CENTER);
            setFont(new Font("Monospaced", Font.BOLD, 18));
            setForeground(new Color(0xFF, 0x30, 0x30));
            setBackground(Color.BLACK);
            setOpaque(true);
            setBorder(BorderFactory.createLoweredBevelBorder());
            setPreferredSize(new Dimension(52, 30));
        }

        void setValue(int value) {
            setText(String.format("%03d", Math.max(0, Math.min(999, value))));
        }
    }
}
