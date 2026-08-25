package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.DecimalFormat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import javaos.desktop.Shell;
import javaos.ui.Icons;
import javaos.ui.Ui;

/** Four functions, a memory register, and an LCD that is really just a JLabel. */
public class CalculatorApp extends AppWindow {

    private static final DecimalFormat DISPLAY = new DecimalFormat("#,##0.##########");

    private final JLabel screen = new JLabel("0", SwingConstants.RIGHT);
    private final JLabel flags = new JLabel(" ", SwingConstants.LEFT);

    private double accumulator;
    private double memory;
    private String pendingOperator;
    private boolean startNewNumber = true;

    public CalculatorApp(Shell shell, String argument) {
        super(shell, "Calculator", Icons.calculator(16));
        setSize(268, 300);
        setResizable(false);
        setMaximizable(false);

        screen.setFont(new Font("Monospaced", Font.BOLD, 22));
        screen.setForeground(new Color(0x1A, 0x2E, 0x1A));
        screen.setBackground(new Color(0xCC, 0xE4, 0xB8));
        screen.setOpaque(true);
        screen.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        flags.setFont(new Font("Dialog", Font.BOLD, 10));
        flags.setForeground(new Color(0x1A, 0x2E, 0x1A));
        flags.setBackground(screen.getBackground());
        flags.setOpaque(true);
        flags.setBorder(BorderFactory.createEmptyBorder(2, 8, 0, 8));

        JPanel lcd = new JPanel(new BorderLayout());
        lcd.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));
        lcd.setBackground(screen.getBackground());
        lcd.add(flags, BorderLayout.NORTH);
        lcd.add(screen, BorderLayout.CENTER);

        JPanel keys = new JPanel(new GridLayout(5, 5, 3, 3));
        keys.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        String[] layout = {
            "MC", "MR", "M+", "C", "CE",
            "7", "8", "9", "/", "sqrt",
            "4", "5", "6", "*", "%",
            "1", "2", "3", "-", "1/x",
            "0", ".", "+/-", "+", "=",
        };
        for (String key : layout) {
            keys.add(button(key));
        }

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        root.add(lcd, BorderLayout.NORTH);
        root.add(keys, BorderLayout.CENTER);
        setBody(root);
        status("Ready");

        root.setFocusable(true);
        root.addKeyListener(new KeyAdapter() {
            @Override public void keyTyped(KeyEvent e) {
                typed(e.getKeyChar());
            }
        });
        java.awt.EventQueue.invokeLater(root::requestFocusInWindow);
    }

    private JButton button(String key) {
        JButton b = new JButton(key);
        b.setFont(new Font("Dialog", Font.BOLD, key.length() > 2 ? 10 : 13));
        b.setMargin(new java.awt.Insets(1, 1, 1, 1));
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(40, 30));
        if (key.matches("[0-9.]")) {
            b.setForeground(new Color(0x1F, 0x3A, 0x6E));
        } else if (key.equals("=")) {
            b.setForeground(Ui.darker(Icons.GREEN, 0.1));
        } else if (key.startsWith("M")) {
            b.setForeground(new Color(0x7A, 0x4A, 0x1F));
        } else {
            b.setForeground(new Color(0x8C, 0x2A, 0x22));
        }
        b.addActionListener(e -> press(key));
        return b;
    }

    private void typed(char ch) {
        if (Character.isDigit(ch) || ch == '.') {
            press(String.valueOf(ch));
        } else if ("+-*/".indexOf(ch) >= 0) {
            press(String.valueOf(ch));
        } else if (ch == '\n' || ch == '=') {
            press("=");
        } else if (ch == 8) {
            press("CE");
        } else if (ch == 27) {
            press("C");
        }
    }

    private void press(String key) {
        switch (key) {
            case "C" -> {
                accumulator = 0;
                pendingOperator = null;
                setDisplay(0);
                startNewNumber = true;
                status("Cleared");
            }
            case "CE" -> {
                setDisplay(0);
                startNewNumber = true;
            }
            case "MC" -> {
                memory = 0;
                flags.setText(" ");
            }
            case "MR" -> {
                setDisplay(memory);
                startNewNumber = true;
            }
            case "M+" -> {
                memory += currentValue();
                flags.setText("M");
                startNewNumber = true;
            }
            case "+/-" -> setDisplay(-currentValue());
            case "sqrt" -> {
                double value = currentValue();
                if (value < 0) {
                    showError("Invalid input");
                } else {
                    setDisplay(Math.sqrt(value));
                }
                startNewNumber = true;
            }
            case "1/x" -> {
                if (currentValue() == 0) {
                    showError("Cannot divide by zero");
                } else {
                    setDisplay(1 / currentValue());
                }
                startNewNumber = true;
            }
            case "%" -> {
                setDisplay(accumulator * currentValue() / 100);
                startNewNumber = true;
            }
            case "+", "-", "*", "/" -> operator(key);
            case "=" -> equals();
            case "." -> {
                if (startNewNumber) {
                    screen.setText("0.");
                    startNewNumber = false;
                } else if (!screen.getText().contains(".")) {
                    screen.setText(screen.getText() + ".");
                }
            }
            default -> {
                if (startNewNumber || screen.getText().equals("0")) {
                    screen.setText(key);
                    startNewNumber = false;
                } else {
                    screen.setText(screen.getText() + key);
                }
            }
        }
    }

    private void operator(String op) {
        equals();
        pendingOperator = op;
        accumulator = currentValue();
        startNewNumber = true;
        status(DISPLAY.format(accumulator) + " " + op);
    }

    private void equals() {
        if (pendingOperator == null) {
            accumulator = currentValue();
            return;
        }
        double right = currentValue();
        double result = switch (pendingOperator) {
            case "+" -> accumulator + right;
            case "-" -> accumulator - right;
            case "*" -> accumulator * right;
            default -> right == 0 ? Double.NaN : accumulator / right;
        };
        if (Double.isNaN(result) || Double.isInfinite(result)) {
            showError("Cannot divide by zero");
            accumulator = 0;
        } else {
            accumulator = result;
            setDisplay(result);
            status(" ");
        }
        pendingOperator = null;
        startNewNumber = true;
    }

    private double currentValue() {
        try {
            return Double.parseDouble(screen.getText().replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setDisplay(double value) {
        screen.setText(DISPLAY.format(value));
    }

    private void showError(String message) {
        screen.setText("E");
        status(message);
    }
}
