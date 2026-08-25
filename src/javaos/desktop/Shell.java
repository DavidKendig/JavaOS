package javaos.desktop;

import java.util.List;

import javax.swing.JDesktopPane;
import javax.swing.JFrame;

import javaos.Settings;
import javaos.apps.App;
import javaos.apps.AppWindow;
import javaos.vfs.Vfs;

/** What an application is allowed to ask of the desktop it runs on. */
public interface Shell {

    Vfs vfs();

    Settings settings();

    /** Starts an application with no argument. */
    AppWindow launch(String appId);

    /** Starts an application, handing it a document path or other argument. */
    AppWindow launch(String appId, String argument);

    /** Opens a file with whichever application claims its extension. */
    AppWindow openFile(String virtualPath);

    /** Places an already-built window on the desktop and focuses it. */
    void show(AppWindow window);

    /** Writes a line to the desktop status area. */
    void status(String message);

    JDesktopPane pane();

    JFrame frame();

    List<App> apps();

    /** Seals the session behind the lock screen until the user comes back. */
    void lockScreen();

    /** Confirms, then tears the session down. */
    void shutDown();
}
