package javaos.sys;

import java.util.Locale;

/**
 * A platform's way of answering the expensive questions: what each core is
 * doing, and what hardware is installed. Implementations report their findings
 * back into {@link Machine} from their own threads.
 */
interface Probe {

    /** Begins sampling. Must return promptly; do the work on a daemon thread. */
    void start();

    /** Stops sampling and releases any helper process. */
    void stop();

    static Probe forThisPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsProbe();
        }
        if (os.contains("linux")) {
            return new LinuxProbe();
        }
        return new Probe() {
            @Override public void start() {
                Machine.reportStatus("No detailed probe for " + System.getProperty("os.name")
                        + "; showing what the JVM reports.");
            }

            @Override public void stop() {
            }
        };
    }
}
