package javaos.interop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import javaos.msoffice.MsOffice;
import javaos.soffice.LibreOffice;

/**
 * The one place JavaOS decides which office application actually opens a
 * document. The order is fixed and deliberately boring:
 *
 * <ol>
 *   <li>Microsoft Office, if the matching program is installed;
 *   <li>otherwise LibreOffice, if it is installed;
 *   <li>otherwise the JavaOS editor, which needs nothing installed at all.
 * </ol>
 *
 * <p>The reasoning is the same one behind {@link javaos.vlc.Vlc}: a machine
 * with real Word on it belongs to someone who wants Word, and handing them a
 * Swing text pane instead would be a worse answer, not a purer one. The pure
 * Java engine in {@link javaos.office} stays the floor rather than the ceiling
 * -- it is what runs when neither suite is here, and it is what the Control
 * Panel switches back to when the user would rather stay inside JavaOS.
 *
 * <p>Nothing here ever installs, bundles or updates either suite; it only
 * looks, and hands over host paths so the external program edits volume files
 * in place.
 */
public final class OfficeSuite {

    /** Who ends up opening the document. */
    public enum Vendor {
        MICROSOFT("Microsoft Office"),
        LIBRE("LibreOffice"),
        JAVAOS("JavaOS");

        public final String label;

        Vendor(String label) {
            this.label = label;
        }
    }

    /**
     * The four office roles JavaOS presents, each mapped to its counterpart in
     * both suites. Writer and Calc are the JavaOS fallbacks; the presentation
     * and database roles have no pure Java editor, so without a suite they open
     * a window that says so rather than an editor that cannot edit.
     */
    public enum Program {
        WORD("Word", MsOffice.Program.WORD, LibreOffice.Module.WRITER, "writer"),
        EXCEL("Excel", MsOffice.Program.EXCEL, LibreOffice.Module.CALC, "calc"),
        POWERPOINT("PowerPoint", MsOffice.Program.POWERPOINT, LibreOffice.Module.IMPRESS, null),
        ACCESS("Access", MsOffice.Program.ACCESS, LibreOffice.Module.BASE, null);

        public final String label;
        public final MsOffice.Program microsoft;
        public final LibreOffice.Module libre;

        /** The JavaOS application id that opens this kind of file, or null. */
        public final String nativeAppId;

        Program(String label, MsOffice.Program microsoft, LibreOffice.Module libre,
                String nativeAppId) {
            this.label = label;
            this.microsoft = microsoft;
            this.libre = libre;
            this.nativeAppId = nativeAppId;
        }

        /** True when JavaOS can open this kind of document on its own. */
        public boolean hasNativeApp() {
            return nativeAppId != null;
        }
    }

    /** An installed suite that can take one of the four roles. */
    public record Handler(Vendor vendor, Program program, String name, String version,
            Path executable) {

        /** "Microsoft Word 16.0", or "LibreOffice Writer 7.6". */
        public String describe() {
            return name + ("unknown".equals(version) ? "" : " " + version);
        }
    }

    private OfficeSuite() {
    }

    // ---- the lookup ----------------------------------------------------

    /**
     * The suite that should open this kind of document: Microsoft Office first,
     * LibreOffice second, empty when neither is installed and the JavaOS editor
     * is the answer.
     */
    public static Optional<Handler> handlerFor(Program program) {
        Optional<MsOffice.Install> microsoft = MsOffice.find(program.microsoft);
        if (microsoft.isPresent()) {
            MsOffice.Install install = microsoft.get();
            return Optional.of(new Handler(Vendor.MICROSOFT, program,
                    "Microsoft " + program.microsoft.label, install.version(),
                    install.executable()));
        }
        Optional<LibreOffice.Install> libre = LibreOffice.find();
        if (libre.isPresent()) {
            LibreOffice.Install install = libre.get();
            return Optional.of(new Handler(Vendor.LIBRE, program,
                    "LibreOffice " + program.libre.label, install.version(),
                    install.executable()));
        }
        return Optional.empty();
    }

    /** True when an installed suite would take this role. */
    public static boolean isAvailable(Program program) {
        return handlerFor(program).isPresent();
    }

    /** Looks both suites up again, so a fresh install is noticed without a restart. */
    public static void refresh() {
        MsOffice.refreshAll();
        LibreOffice.refresh();
    }

    // ---- launching -----------------------------------------------------

    /**
     * Starts the installed suite on {@code file}, or with an empty document when
     * {@code file} is null.
     *
     * @return the suite that took it, or empty when neither is installed and the
     *         caller should fall back to the JavaOS editor
     * @throws IOException when a suite was found but would not start
     */
    public static Optional<Handler> open(Program program, Path file) throws IOException {
        Optional<Handler> handler = handlerFor(program);
        if (handler.isEmpty()) {
            return handler;
        }
        if (handler.get().vendor() == Vendor.MICROSOFT) {
            if (file == null) {
                MsOffice.start(program.microsoft);
            } else {
                MsOffice.open(program.microsoft, file);
            }
        } else if (file == null) {
            LibreOffice.start(program.libre);
        } else {
            LibreOffice.open(file);
        }
        return handler;
    }

    // ---- file types ----------------------------------------------------

    /** The office role a file extension belongs to, or null when it is not one. */
    public static Program programFor(String extension) {
        MsOffice.Program microsoft = MsOffice.programFor(extension);
        if (microsoft == null) {
            return null;
        }
        return switch (microsoft) {
            case WORD -> Program.WORD;
            case EXCEL -> Program.EXCEL;
            case POWERPOINT -> Program.POWERPOINT;
            case ACCESS -> Program.ACCESS;
        };
    }

    /** A one-line report for the Control Panel and the About screens. */
    public static String describe(Program program) {
        return handlerFor(program)
                .map(handler -> handler.describe() + " will open " + program.label
                        + " documents.")
                .orElseGet(() -> program.hasNativeApp()
                        ? "No office suite found. JavaOS opens these itself."
                        : "No office suite found, and JavaOS has no " + program.label
                                + " editor of its own.");
    }
}
