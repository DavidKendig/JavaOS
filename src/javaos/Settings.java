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

    /** Desktop backdrop styles, all painted in code -- no wallpaper files to lose. */
    public enum Wallpaper {
        HORIZON("Horizon gradient"),
        RAYS("Sunburst"),
        GRID("Blueprint grid"),
        WEAVE("Woven texture"),
        FLAT("Flat colour");

        public final String label;

        Wallpaper(String label) {
            this.label = label;
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
            return Wallpaper.valueOf(get("wallpaper", "HORIZON"));
        } catch (IllegalArgumentException e) {
            return Wallpaper.HORIZON;
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
