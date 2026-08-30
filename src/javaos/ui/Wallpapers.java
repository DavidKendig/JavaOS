package javaos.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * The bundled wallpaper images: loaded from the jar, scaled to cover whatever
 * the desktop happens to be, and cached so a repaint costs a blit.
 *
 * <p>These are the only bitmaps in JavaOS. Every icon, every window chrome and
 * the one remaining generated backdrop are drawn in code, but a wallpaper like
 * these is not something to reconstruct with {@code GeneralPath}, and
 * pretending otherwise would cost the user the picture they actually picked.
 *
 * <p>Nothing here is required. A missing or unreadable image is reported as a
 * failure to paint rather than thrown, and the desktop falls back to a plain
 * backdrop -- a wallpaper is not worth failing a session over.
 */
public final class Wallpapers {

    /** Where the images sit on the classpath, mirroring their place under src. */
    private static final String DIRECTORY = "/javaos/ui/wallpaper/";

    /**
     * One image at a time, at full size and again fitted to the desktop. Holding
     * all of them would be about 45MB of pixels for a preference the user sets
     * once; reloading the few hundred KB of PNG on a change is the better trade.
     */
    private static String sourceName;
    private static BufferedImage source;
    private static boolean sourceFailed;

    private static String fittedKey;
    private static BufferedImage fitted;

    private Wallpapers() {
    }

    /**
     * Paints one bundled wallpaper across the whole of {@code w} by {@code h}.
     *
     * @return false when the image is not there or will not read, leaving the
     *         caller to paint something else
     */
    public static synchronized boolean paint(Graphics2D g2, int w, int h, String resource) {
        if (resource == null || w <= 0 || h <= 0) {
            return false;
        }
        BufferedImage image = fit(resource, w, h);
        if (image == null) {
            return false;
        }
        g2.drawImage(image, 0, 0, null);
        return true;
    }

    /**
     * The wallpaper at exactly the size asked for, cropped to fill rather than
     * squashed to fit: the aspect ratio of a 16:10 wallpaper is not the aspect
     * ratio of the window, and stretching it would show.
     */
    private static BufferedImage fit(String resource, int w, int h) {
        String key = resource + "@" + w + "x" + h;
        if (key.equals(fittedKey) && fitted != null) {
            return fitted;
        }
        BufferedImage original = load(resource);
        if (original == null) {
            return null;
        }

        double scale = Math.max(w / (double) original.getWidth(),
                h / (double) original.getHeight());
        int width = (int) Math.ceil(original.getWidth() * scale);
        int height = (int) Math.ceil(original.getHeight() * scale);

        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Centred, so the crop takes evenly off both edges.
        g.drawImage(original, (w - width) / 2, (h - height) / 2, width, height, null);
        g.dispose();

        fittedKey = key;
        fitted = target;
        return target;
    }

    /** The image at its own size, read from the jar the first time it is asked for. */
    private static BufferedImage load(String resource) {
        if (resource.equals(sourceName)) {
            return sourceFailed ? null : source;
        }
        sourceName = resource;
        source = null;
        sourceFailed = true;
        try (InputStream in = Wallpapers.class.getResourceAsStream(DIRECTORY + resource)) {
            if (in == null) {
                return null;
            }
            BufferedImage read = ImageIO.read(in);
            if (read == null) {
                return null;
            }
            source = read;
            sourceFailed = false;
            return source;
        } catch (IOException | RuntimeException e) {
            // An unreadable wallpaper is a cosmetic problem, not a fatal one.
            return null;
        }
    }

}
