package javaos;

import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

import javaos.desktop.JavaOsDesktop;
import javaos.ui.SplashScreen;
import javaos.ui.SunTheme;
import javaos.vfs.Vfs;

/**
 * Entry point. Installs the theme, shows the splash, and hands control to the
 * desktop. There is nothing to mount: JavaOS works on the host file system.
 *
 * <pre>
 *   java -cp out javaos.Boot [--no-splash] [--theme steel|emerald|ochre|slate]
 * </pre>
 */
public final class Boot {

    private Boot() {
    }

    public static void main(String[] args) {
        boolean splashWanted = true;
        SunTheme.Flavor forcedTheme = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-splash" -> splashWanted = false;
                case "--theme" -> forcedTheme = SunTheme.Flavor.byName(args[++i]);
                case "--help", "-h" -> {
                    System.out.println(Version.FULL + """


                              --no-splash        skip the start-up screen
                              --theme <name>     steel, emerald, ochre or slate
                            """);
                    return;
                }
                default -> System.err.println("Ignoring unknown option: " + args[i]);
            }
        }

        Settings settings = Settings.defaults();
        if (forcedTheme != null) {
            settings.setTheme(forcedTheme);
        }
        Vfs vfs = new Vfs();

        SunTheme.install(settings.theme());
        ToolTipManager.sharedInstance().setInitialDelay(400);

        boolean showSplash = splashWanted && settings.showSplash();
        SplashScreen splash = showSplash ? showSplash() : null;
        if (splash != null) {
            splash.runBootSequence();
        }

        SwingUtilities.invokeLater(() -> {
            JavaOsDesktop desktop = new JavaOsDesktop(vfs, settings);
            desktop.start();
            if (splash != null) {
                splash.dispose();
            }
        });
    }

    private static SplashScreen showSplash() {
        SplashScreen[] holder = new SplashScreen[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new SplashScreen();
                holder[0].setVisible(true);
            });
        } catch (Exception e) {
            return null; // The desktop is more important than the splash.
        }
        return holder[0];
    }
}
