package javaos.apps;

import javax.swing.Icon;

import javaos.desktop.Shell;

/** One installed application. Instances are stateless factories; windows hold the state. */
public interface App {

    String id();

    String name();

    Icon icon(int size);

    /** Builds a new window. {@code argument} is usually a document path, or null. */
    AppWindow create(Shell shell, String argument);

    /** Menu grouping, matching the sections of the Launch menu. */
    default String category() {
        return "Applications";
    }

    /** Lower-case file extensions this application opens. */
    default String[] extensions() {
        return new String[0];
    }

    /** One-line summary shown in tooltips and the Launch menu status text. */
    default String description() {
        return name();
    }

    default boolean inLaunchMenu() {
        return true;
    }
}
