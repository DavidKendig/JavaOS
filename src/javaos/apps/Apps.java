package javaos.apps;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

import javax.swing.Icon;

import javaos.desktop.Shell;
import javaos.interop.OfficeSuite;
import javaos.ui.Icons;

/** The installed application list -- JavaOS has no package manager, so this is it. */
public final class Apps {

    private Apps() {
    }

    /** An application built from a factory pair rather than its own class. */
    public record Definition(String id, String name, String category, String description,
            IntFunction<Icon> iconFactory, BiFunction<Shell, String, AppWindow> factory,
            String[] extensions, boolean inLaunchMenu) implements App {

        @Override public Icon icon(int size) {
            return iconFactory.apply(size);
        }

        @Override public AppWindow create(Shell shell, String argument) {
            return factory.apply(shell, argument);
        }
    }

    private static final String[] NONE = new String[0];

    /**
     * An office application that prefers the real thing. Each of these looks for
     * Microsoft Office, then LibreOffice, and only opens a JavaOS window when
     * neither is installed -- see {@link OfficeLauncher}.
     *
     * <p>They come first in the list, so opening a .docx or .xlsx from the File
     * Manager goes through the same order that launching them does. Writer and
     * Calc stay in the menu underneath as the way to ask for the JavaOS editor
     * by name.
     */
    private static Definition officeApp(OfficeSuite.Program program, String description,
            IntFunction<Icon> icon, String... extensions) {
        return new Definition(program.name().toLowerCase(java.util.Locale.ROOT),
                program.label, "Office", description, icon,
                (shell, argument) -> OfficeLauncher.open(shell, program, argument),
                extensions, true);
    }

    public static List<App> installed() {
        return List.of(
                officeApp(OfficeSuite.Program.WORD,
                        "Word processor: the installed Office or LibreOffice, else JavaOS Writer",
                        Icons::officeWord, "docx", "doc", "odt", "rtf"),

                officeApp(OfficeSuite.Program.EXCEL,
                        "Spreadsheet: the installed Office or LibreOffice, else JavaOS Calc",
                        Icons::officeExcel, "xlsx", "xls", "ods"),

                officeApp(OfficeSuite.Program.POWERPOINT,
                        "Presentations, through the installed Office or LibreOffice",
                        Icons::officePowerPoint, "pptx", "ppt", "odp", "otp"),

                officeApp(OfficeSuite.Program.ACCESS,
                        "Databases, through the installed Office or LibreOffice",
                        Icons::officeAccess, "accdb", "mdb", "odb"),

                // The JavaOS editors themselves. Word and Excel above already
                // open them whenever no suite is installed, so listing them in
                // the Launch menu as well would put the same word processor in
                // the same submenu twice. They stay registered: the desktop
                // icons, the File Manager and the plain-text extensions all
                // launch them by id, and they are still the fallback the office
                // applications land on.
                new Definition("writer", "Writer", "Office",
                        "The JavaOS word processor: ODF, Word, RTF and plain text",
                        Icons::textDocument, WriterApp::new,
                        new String[] {"odt", "docx", "txt", "rtf", "log", "md", "java",
                            "properties"}, false),

                new Definition("calc", "Calc", "Office",
                        "The JavaOS spreadsheet with formulas: ODF, Excel and CSV",
                        Icons::spreadsheet, CalcApp::new,
                        new String[] {"ods", "xlsx", "csv", "tsv"}, false),

                new Definition("filemanager", "File Manager", "Accessories",
                        "Browse the host file system",
                        Icons::folderOpen, FileManagerApp::new, NONE, true),

                new Definition("terminal", "Terminal", "Accessories",
                        "A shell over the host file system",
                        Icons::terminal, TerminalApp::new, NONE, true),

                new Definition("calculator", "Calculator", "Accessories",
                        "Arithmetic, memory keys and all",
                        Icons::calculator, CalculatorApp::new, NONE, true),

                new Definition("paint", "Paint", "Accessories",
                        "Bitmap editor, saves PNG",
                        Icons::paint, PaintApp::new,
                        new String[] {"png", "jpg", "jpeg", "gif"}, true),

                new Definition("mediaplayer", "Media Player", "Accessories",
                        "Plays WAV, AU, AIFF and MIDI; hands the rest to VLC",
                        Icons::media, MediaPlayerApp::new,
                        javaos.media.Media.allExtensions(), true),

                new Definition("mines", "Mines", "Games",
                        "Find the mines without standing on one",
                        Icons::mine, MinesApp::new, NONE, true),

                new Definition("settings", "Control Panel", "System",
                        "Appearance, desktop and clock settings",
                        Icons::settings, SettingsApp::new, NONE, true),

                new Definition("monitor", "System Monitor", "System",
                        "Heap, threads and disk usage",
                        Icons::monitor, MonitorApp::new, NONE, true),

                new Definition("about", "About JavaOS", "System",
                        "Version and credits",
                        Icons::info, AboutApp::new, NONE, false));
    }
}
