package javaos;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

import javaos.desktop.JavaOsDesktop;
import javaos.office.OfficeFormats;
import javaos.office.SheetDocument;
import javaos.office.TextDocument;
import javaos.ui.SplashScreen;
import javaos.ui.SunTheme;
import javaos.vfs.Vfs;

/**
 * Entry point. Boots the volume, installs the theme, shows the splash, and hands
 * control to the desktop.
 *
 * <pre>
 *   java -cp out javaos.Boot [--no-splash] [--volume &lt;path&gt;] [--theme steel|emerald|ochre|slate]
 * </pre>
 */
public final class Boot {

    private Boot() {
    }

    public static void main(String[] args) {
        boolean splashWanted = true;
        Path volume = null;
        SunTheme.Flavor forcedTheme = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-splash" -> splashWanted = false;
                case "--volume" -> volume = Paths.get(args[++i]);
                case "--theme" -> forcedTheme = SunTheme.Flavor.byName(args[++i]);
                case "--help", "-h" -> {
                    System.out.println(Version.FULL + """


                              --no-splash        skip the start-up screen
                              --volume <path>    use an alternative volume directory
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
        Vfs vfs = volume == null ? Vfs.defaultVolume() : mount(volume);
        seedOfficeSamples(vfs);

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

    private static Vfs mount(Path path) {
        Vfs vfs = new Vfs(path);
        vfs.mount();
        return vfs;
    }

    /**
     * Writes one sample of each office format the first time they are missing,
     * so the formats are visible in the file manager rather than only findable
     * through a save dialog.
     */
    private static void seedOfficeSamples(Vfs vfs) {
        String documents = Vfs.HOME + "/Documents";
        String sheets = Vfs.HOME + "/Spreadsheets";
        try {
            if (!vfs.exists(documents + "/welcome.odt")) {
                TextDocument document = new TextDocument();
                TextDocument.Paragraph title = document.addParagraph();
                title.setAlign(TextDocument.Align.CENTER);
                title.add(new TextDocument.Run("Welcome to JavaOS Writer",
                        TextDocument.Format.PLAIN.withBold(true).withSize(20)));
                TextDocument.Paragraph body = document.addParagraph();
                body.add(TextDocument.Run.of("This is a real OpenDocument file. It was "));
                body.add(new TextDocument.Run("written",
                        TextDocument.Format.PLAIN.withItalic(true)));
                body.add(TextDocument.Run.of(" by JavaOS in pure Java, with no LibreOffice "
                        + "involved, and any word processor can open it."));
                document.addParagraph().add(TextDocument.Run.of(
                        "Save as .odt or .docx from the File menu."));
                OfficeFormats.writeText(document, vfs.host(documents + "/welcome.odt"));
            }
            if (!vfs.exists(sheets + "/budget.ods")) {
                SheetDocument sheet = new SheetDocument();
                sheet.setName("Budget");
                String[] headers = {"Item", "Qty", "Unit", "Total"};
                for (int i = 0; i < headers.length; i++) {
                    sheet.set(0, i, headers[i]);
                }
                String[] items = {"Workstation", "Monitor", "Coffee"};
                int[][] numbers = {{4, 2400}, {8, 600}, {999, 3}};
                for (int i = 0; i < items.length; i++) {
                    int row = i + 1;
                    sheet.set(row, 0, items[i]);
                    sheet.set(row, 1, String.valueOf(numbers[i][0]));
                    sheet.set(row, 2, String.valueOf(numbers[i][1]));
                    sheet.set(row, 3, "=B" + (row + 1) + "*C" + (row + 1));
                    sheet.setCachedValue(row, 3,
                            String.valueOf(numbers[i][0] * numbers[i][1]));
                }
                sheet.set(4, 2, "Grand total");
                sheet.set(4, 3, "=SUM(D2:D4)");
                sheet.setCachedValue(4, 3, "17397");
                OfficeFormats.writeSheet(sheet, vfs.host(sheets + "/budget.ods"));
            }
        } catch (Exception e) {
            // Samples are a convenience; a failure here must not stop the boot.
            System.err.println("Could not write the office samples: " + e.getMessage());
        }
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
