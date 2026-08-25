package javaos;

/**
 * The product version, in one place.
 *
 * <p>Everything that shows a version to the user reads it from here: the splash,
 * the backdrop watermark, the Launch menu stripe, the About box, {@code uname},
 * and the generator stamp written into office documents.
 *
 * <p>Not to be confused with the format versions in {@link javaos.office}, which
 * name the ODF and OOXML specifications rather than this program.
 */
public final class Version {

    public static final String NAME = "JavaOS";

    /** The release number. This is the only line to edit when cutting a build. */
    public static final String NUMBER = "0.26.001";

    /** "JavaOS 0.26.001", for titles and banners. */
    public static final String FULL = NAME + " " + NUMBER;

    /** "Release 0.26.001", for the About box and the splash. */
    public static final String RELEASE = "Release " + NUMBER;

    /** "JavaOS/0.26.001", the generator string embedded in saved documents. */
    public static final String GENERATOR = NAME + "/" + NUMBER;

    private Version() {
    }
}
