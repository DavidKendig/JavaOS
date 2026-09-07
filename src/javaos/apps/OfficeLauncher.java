package javaos.apps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.JOptionPane;

import javaos.desktop.Shell;
import javaos.interop.OfficeSuite;
import javaos.vfs.Vfs;

/**
 * What happens when the user starts Word, Excel, PowerPoint or Access: JavaOS
 * looks for the real thing before opening one of its own windows.
 *
 * <p>The order lives in {@link OfficeSuite} -- Microsoft Office, then
 * LibreOffice, then JavaOS. This class is only the desktop end of it: it turns
 * a JavaOS path into the host path the external suite needs, reports what it
 * did in the status bar, and builds the JavaOS window when nothing else took
 * the job. A hand-over opens no JavaOS window at all, so the factory returns
 * null and the desktop simply has one fewer frame on it.
 *
 * <p>The Control Panel switch turns the hand-over off, for the user who would
 * rather stay inside JavaOS on a machine that happens to have Office on it.
 */
final class OfficeLauncher {

    private OfficeLauncher() {
    }

    /**
     * Opens {@code argument} -- a volume path, or null for a new document -- in
     * whichever application should have it.
     *
     * @return the JavaOS window that opened, or null when an installed suite
     *         took the document instead
     */
    static AppWindow open(Shell shell, OfficeSuite.Program program, String argument) {
        if (shell.settings().preferInstalledOffice()) {
            Path host = hostPath(shell, argument);
            // A path that is not on the volume yet is not something to hand
            // over; the JavaOS window below will say what is wrong with it.
            if (argument == null || host != null) {
                try {
                    Optional<OfficeSuite.Handler> handler = OfficeSuite.open(program, host);
                    if (handler.isPresent()) {
                        shell.status(handler.get().describe() + " opened "
                                + (argument == null ? "a new document"
                                        : Vfs.name(argument)) + ".");
                        return null;
                    }
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(shell.frame(),
                            "Could not start the installed office suite.\n\n" + e.getMessage()
                                    + "\n\nOpening it in JavaOS instead.",
                            program.label, JOptionPane.WARNING_MESSAGE);
                }
            }
        }
        return fallback(shell, program, argument);
    }

    /** The real path behind a volume path, or null when there is no such file. */
    private static Path hostPath(Shell shell, String argument) {
        if (argument == null || !shell.vfs().exists(argument)
                || shell.vfs().isDirectory(argument)) {
            return null;
        }
        return shell.vfs().host(argument);
    }

    /**
     * The JavaOS answer. Word and Excel have real editors behind them; the
     * presentation and database roles do not, and open a window that says so
     * rather than an editor that cannot edit.
     */
    private static AppWindow fallback(Shell shell, OfficeSuite.Program program,
            String argument) {
        return switch (program) {
            case WORD -> new WriterApp(shell, argument);
            case EXCEL -> new CalcApp(shell, argument);
            case POWERPOINT, ACCESS -> new SuiteApp(shell, program, argument);
        };
    }
}
