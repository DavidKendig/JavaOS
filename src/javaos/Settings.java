package javaos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javaos.ui.SunTheme;

/** Desktop preferences, persisted next to the volume in ~/.javaos/settings.properties. */
public final class Settings {

    /**
     * Desktop backdrops. The first five are bundled images, shipped inside the
     * jar and painted by {@link javaos.ui.Wallpapers}; the last is generated in
     * code and needs no files at all, which is why it survives. A style whose
     * image will not load falls back to a plain backdrop rather than failing
     * the session.
     */
    public enum Wallpaper {
        CHROME("Chrome polygons", "Polygons_Chrome.png"),
        POLYGONS("Colour polygons", "Polygons_Color.png"),
        HEXAGONS("Colour hexagons", "Hexagons_Color.png"),
        HEXAGONS_DARK("Dark hexagons", "Hexagons_Dark.png"),
        PLUM("Solaris plum", "SolarisPlum.png"),
        FACETS("Low-poly facets (drawn)");

        public final String label;

        /** The bundled image this style paints, or null when it is drawn in code. */
        public final String resource;

        Wallpaper(String label) {
            this(label, null);
        }

        Wallpaper(String label, String resource) {
            this.label = label;
            this.resource = resource;
        }

        /** True when this backdrop is a bundled image rather than code. */
        public boolean isImage() {
            return resource != null;
        }
    }

    private final Path file;
    private final Properties props = new Properties();
    private final List<Runnable> listeners = new ArrayList<>();

    public Settings(Path file) {
        this.file = file;
        load();
    }

    public static Settings defaults() {
        return new Settings(Paths.get(System.getProperty("user.home"), ".javaos",
                "settings.properties"));
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            // A corrupt settings file is not worth failing the boot over.
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "JavaOS desktop settings");
            }
        } catch (IOException e) {
            // Non-fatal: the session simply will not be remembered.
        }
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public void fireChanged() {
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }

    private String get(String key, String fallback) {
        return props.getProperty(key, fallback);
    }

    private void set(String key, String value) {
        props.setProperty(key, value);
    }

    public SunTheme.Flavor theme() {
        return SunTheme.Flavor.byName(get("theme", "STEEL"));
    }

    public void setTheme(SunTheme.Flavor flavor) {
        set("theme", flavor.name());
    }

    public Wallpaper wallpaper() {
        try {
            return Wallpaper.valueOf(get("wallpaper", "CHROME"));
        } catch (IllegalArgumentException e) {
            // A settings file from a build that had more backdrops than this
            // one names a style that is gone; fall back rather than refuse.
            return Wallpaper.CHROME;
        }
    }

    public void setWallpaper(Wallpaper wallpaper) {
        set("wallpaper", wallpaper.name());
    }

    public boolean showLogo() {
        return Boolean.parseBoolean(get("desktop.logo", "true"));
    }

    public void setShowLogo(boolean show) {
        set("desktop.logo", Boolean.toString(show));
    }

    public boolean clock24h() {
        return Boolean.parseBoolean(get("clock.24h", "false"));
    }

    public void setClock24h(boolean use24h) {
        set("clock.24h", Boolean.toString(use24h));
    }

    public boolean showSeconds() {
        return Boolean.parseBoolean(get("clock.seconds", "true"));
    }

    public void setShowSeconds(boolean show) {
        set("clock.seconds", Boolean.toString(show));
    }

    public boolean outlineDrag() {
        return Boolean.parseBoolean(get("windows.outlineDrag", "true"));
    }

    public void setOutlineDrag(boolean outline) {
        set("windows.outlineDrag", Boolean.toString(outline));
    }

    public boolean showSplash() {
        return Boolean.parseBoolean(get("boot.splash", "true"));
    }

    public void setShowSplash(boolean show) {
        set("boot.splash", Boolean.toString(show));
    }

    /**
     * True when the office applications hand documents to an installed
     * Microsoft Office or LibreOffice before opening a JavaOS window. On by
     * default: a machine with the real Word on it belongs to someone who
     * probably wants Word. Turning it off keeps every document inside JavaOS,
     * which is also how the pure Java editors stay reachable on a machine that
     * has a suite installed.
     */
    public boolean preferInstalledOffice() {
        return Boolean.parseBoolean(get("office.preferInstalled", "true"));
    }

    public void setPreferInstalledOffice(boolean prefer) {
        set("office.preferInstalled", Boolean.toString(prefer));
    }

    public String userName() {
        return get("user.name", "duke");
    }

    public void setUserName(String name) {
        set("user.name", name);
    }

    // ---- the lock screen ------------------------------------------------

    /** True when the session asks for a passphrase before it will unlock. */
    public boolean hasLockPassphrase() {
        return !get("lock.hash", "").isBlank() && !get("lock.salt", "").isBlank();
    }

    /** Stores a new passphrase as a salt and a derivation; the text is not kept. */
    public void setLockPassphrase(char[] passphrase) {
        Passphrase.Stored stored = Passphrase.of(passphrase);
        set("lock.salt", stored.salt());
        set("lock.hash", stored.hash());
    }

    /** Drops the passphrase, leaving a lock screen that any key opens. */
    public void clearLockPassphrase() {
        props.remove("lock.salt");
        props.remove("lock.hash");
    }

    /** True when the guess opens the session. An unset passphrase accepts anything. */
    public boolean unlocks(char[] guess) {
        if (!hasLockPassphrase()) {
            return true;
        }
        return Passphrase.matches(guess,
                new Passphrase.Stored(get("lock.salt", ""), get("lock.hash", "")));
    }
}
