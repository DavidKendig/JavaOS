package javaos.media;

import java.util.List;
import java.util.Locale;

/**
 * Which media formats JavaOS plays for itself, and which ones it can only hand
 * to VLC.
 *
 * <p>The split is not a policy decision, it is what the JDK ships with. The
 * sampled formats below are containers of uncompressed or lightly companded
 * PCM, which {@code javax.sound.sampled} decodes without help; the sequenced
 * formats are played by {@code javax.sound.midi}'s own synthesiser. Everything
 * in {@link #HANDOVER_ONLY} needs a real codec -- H.264, AAC, Vorbis and their
 * relatives -- and JavaOS has no business pretending otherwise.
 */
public final class Media {

    /** What a file is, as far as playback is concerned. */
    public enum Kind {
        /** PCM audio the JDK decodes: WAV, AU, AIFF. */
        SAMPLED,
        /** A MIDI sequence for the JDK synthesiser. */
        MIDI,
        /** Media JavaOS recognises but cannot decode; VLC's job. */
        HANDOVER,
        /** Not media at all. */
        OTHER
    }

    /** Sampled audio the JDK decodes on its own. */
    public static final List<String> SAMPLED =
            List.of("wav", "wave", "au", "snd", "aif", "aiff", "aifc");

    /** Sequenced music the JDK's software synthesiser plays. */
    public static final List<String> MIDI = List.of("mid", "midi", "rmi", "kar");

    /**
     * Media JavaOS hands straight to VLC because decoding it would mean
     * shipping codecs. Video in every container, and the compressed audio
     * formats, and the playlist files that point at them.
     */
    public static final List<String> HANDOVER_ONLY = List.of(
            // video
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "flv", "wmv", "mpg", "mpeg",
            "mpe", "m2v", "ts", "m2ts", "mts", "vob", "ogv", "3gp", "3g2", "asf",
            "divx", "rm", "rmvb", "f4v",
            // compressed audio
            "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "ape", "mka",
            "ac3", "dts", "amr", "wv", "mpc", "spx", "tta",
            // playlists and discs
            "m3u", "m3u8", "pls", "xspf", "cue", "iso");

    private Media() {
    }

    public static Kind kindOf(String path) {
        String ext = extensionOf(path);
        if (SAMPLED.contains(ext)) {
            return Kind.SAMPLED;
        }
        if (MIDI.contains(ext)) {
            return Kind.MIDI;
        }
        if (HANDOVER_ONLY.contains(ext)) {
            return Kind.HANDOVER;
        }
        return Kind.OTHER;
    }

    /** True for anything the media player will accept into a playlist. */
    public static boolean isMedia(String path) {
        return kindOf(path) != Kind.OTHER;
    }

    /** True for what the pure-Java engines can actually sound. */
    public static boolean isPlayable(String path) {
        Kind kind = kindOf(path);
        return kind == Kind.SAMPLED || kind == Kind.MIDI;
    }

    /** Every extension the player claims, for the file dialogs and {@code Apps}. */
    public static String[] allExtensions() {
        return java.util.stream.Stream.of(SAMPLED, MIDI, HANDOVER_ONLY)
                .flatMap(List::stream).toArray(String[]::new);
    }

    /** A short label for the status bar, e.g. {@code "WAV"} or {@code "MIDI"}. */
    public static String label(String path) {
        String ext = extensionOf(path);
        return switch (ext) {
            case "mid", "midi", "rmi", "kar" -> "MIDI";
            case "" -> "unknown";
            default -> ext.toUpperCase(Locale.ROOT);
        };
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot < 0 || dot < slash) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
