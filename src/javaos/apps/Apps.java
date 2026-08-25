package javaos.apps;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

import javax.swing.Icon;

import javaos.desktop.Shell;
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

    public static List<App> installed() {
        return List.of(
                new Definition("writer", "Writer", "Office",
                        "Word processor: ODF, Word, RTF and plain text",
                        Icons::textDocument, WriterApp::new,
                        new String[] {"odt", "docx", "txt", "rtf", "log", "md", "java",
                            "properties"}, true),

                new Definition("calc", "Calc", "Office",
                        "Spreadsheet with formulas: ODF, Excel and CSV",
                        Icons::spreadsheet, CalcApp::new,
                        new String[] {"ods", "xlsx", "csv", "tsv"}, true),

                new Definition("filemanager", "File Manager", "Accessories",
                        "Browse the volume",
                        Icons::folderOpen, FileManagerApp::new, NONE, true),

                new Definition("terminal", "Terminal", "Accessories",
                        "A shell over the virtual volume",
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
                        "Heap, threads and volume usage",
                        Icons::monitor, MonitorApp::new, NONE, true),

                new Definition("about", "About JavaOS", "System",
                        "Version and credits",
                        Icons::info, AboutApp::new, NONE, false));
    }
}
