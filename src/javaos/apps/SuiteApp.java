package javaos.apps;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import javaos.desktop.Shell;
import javaos.interop.OfficeSuite;
import javaos.ui.Icons;
import javaos.vfs.Vfs;

/**
 * The window PowerPoint and Access open when neither Microsoft Office nor
 * LibreOffice is installed.
 *
 * <p>JavaOS reads and writes ODF and OOXML text and spreadsheets in pure Java,
 * which is why Word and Excel always have somewhere to land. Presentations and
 * databases are a different size of problem, and JavaOS has no editor for
 * either. Rather than open an empty frame that cannot do the job, this says
 * plainly what is missing and offers to look again -- the arrangement the Media
 * Player already has with the formats the JDK cannot decode.
 */
public class SuiteApp extends AppWindow {

    private final OfficeSuite.Program program;
    private final String argument;
    private final JLabel state = new JLabel();

    public SuiteApp(Shell shell, OfficeSuite.Program program, String argument) {
        super(shell, program.label, icon(program, 16));
        this.program = program;
        this.argument = argument;
        setSize(500, 300);
        setBody(explanation());
        setDocumentPath(argument);
        showState();
        status(OfficeSuite.describe(program));
    }

    /** The tile for one office role, at the size the caller asked for. */
    public static Icon icon(OfficeSuite.Program program, int size) {
        return switch (program) {
            case WORD -> Icons.officeWord(size);
            case EXCEL -> Icons.officeExcel(size);
            case POWERPOINT -> Icons.officePowerPoint(size);
            case ACCESS -> Icons.officeAccess(size);
        };
    }

    private JComponent explanation() {
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel heading = new JLabel(program.label + " needs an office suite");
        heading.setFont(new Font("Dialog", Font.BOLD, 15));
        heading.setAlignmentX(0f);
        text.add(heading);
        text.add(Box.createVerticalStrut(8));

        JLabel body = new JLabel("<html>" + prose() + "</html>");
        body.setFont(new Font("Dialog", Font.PLAIN, 12));
        body.setAlignmentX(0f);
        text.add(body);
        text.add(Box.createVerticalStrut(10));

        state.setFont(new Font("Dialog", Font.PLAIN, 11));
        state.setAlignmentX(0f);
        text.add(state);
        text.add(Box.createVerticalGlue());

        JButton again = new JButton("Check Again", Icons.officeHandover(16));
        again.addActionListener(e -> checkAgain());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 6));
        buttons.setAlignmentX(0f);
        buttons.add(again);
        text.add(buttons);

        JLabel tile = new JLabel(icon(program, 64));
        tile.setVerticalAlignment(JLabel.TOP);
        tile.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 14));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(tile, BorderLayout.WEST);
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    private String prose() {
        String what = program == OfficeSuite.Program.POWERPOINT
                ? "presentations" : "databases";
        String suites = program == OfficeSuite.Program.POWERPOINT
                ? "Microsoft PowerPoint or LibreOffice Impress"
                : "Microsoft Access or LibreOffice Base";
        return "JavaOS reads and writes text documents and spreadsheets on its own,"
                + " in pure Java, with nothing installed. It has no editor for " + what
                + ".<br><br>Install " + suites + " and JavaOS will hand "
                + (argument == null ? what : Vfs.name(argument))
                + " straight to it. Nothing here installs or updates either one.";
    }

    private void showState() {
        state.setText(OfficeSuite.handlerFor(program)
                .map(handler -> "Found " + handler.describe() + ".")
                .orElse("No office suite found on this machine."));
    }

    /**
     * Looks both suites up again. A suite installed since JavaOS started is
     * found here, takes the document, and this window closes behind it.
     */
    private void checkAgain() {
        OfficeSuite.refresh();
        showState();
        if (!OfficeSuite.isAvailable(program)) {
            status("Still nothing. Install a suite, then check again.");
            return;
        }
        AppWindow window = shell.launch(appId(), argument);
        if (window == null) {
            dispose();
        }
    }

    private String appId() {
        return program.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override protected void updateTitle() {
        setTitle(documentPath() == null
                ? appName()
                : Vfs.name(documentPath()) + " - " + appName());
    }
}
