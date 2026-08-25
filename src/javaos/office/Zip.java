package javaos.office;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The container half of both office formats: ODF and OOXML files are ZIP
 * archives of XML parts. Documents are small enough to hold in memory, which
 * keeps reading and writing to a map of part name to bytes.
 */
public final class Zip {

    /** Refuse absurd archives rather than unpacking a zip bomb into the heap. */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;

    private Zip() {
    }

    /** Reads every part into memory, keyed by its path inside the archive. */
    public static Map<String, byte[]> read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public static Map<String, byte[]> read(InputStream source) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (parts.size() >= MAX_ENTRIES) {
                    throw new IOException("archive has too many parts");
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    total += read;
                    if (total > MAX_TOTAL_BYTES) {
                        throw new IOException("archive expands to more than 256 MB");
                    }
                    buffer.write(chunk, 0, read);
                }
                // A malformed archive can repeat a name; the first one wins.
                parts.putIfAbsent(normalize(entry.getName()), buffer.toByteArray());
            }
        }
        return parts;
    }

    private static String normalize(String name) {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    /**
     * Writes the parts out in map order.
     *
     * @param uncompressedFirst a part that must be stored without compression as
     *                          the very first entry, or null. ODF requires this
     *                          of its {@code mimetype} part, and readers that
     *                          sniff the file type will reject the package
     *                          without it.
     */
    public static void write(Path file, Map<String, byte[]> parts, String uncompressedFirst)
            throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, parts, uncompressedFirst);
        }
    }

    public static void write(OutputStream target, Map<String, byte[]> parts,
            String uncompressedFirst) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
            if (uncompressedFirst != null && parts.containsKey(uncompressedFirst)) {
                byte[] content = parts.get(uncompressedFirst);
                ZipEntry entry = new ZipEntry(uncompressedFirst);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(content.length);
                entry.setCompressedSize(content.length);
                CRC32 crc = new CRC32();
                crc.update(content);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                if (part.getKey().equals(uncompressedFirst)) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(part.getKey());
                entry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(entry);
                zip.write(part.getValue());
                zip.closeEntry();
            }
        }
    }

    /** True when the file starts with the ZIP local file header, "PK\3\4". */
    public static boolean isZip(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && magic[2] == 3 && magic[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    public static String text(Map<String, byte[]> parts, String name) {
        byte[] content = parts.get(name);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
