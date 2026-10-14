package org.booklore.service.metadata.extractor.djvu;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the {@code DIRM} chunk of a bundled or indirect multi-page DjVu
 * document ({@code FORM:DJVM}). The chunk starts with a flags/version byte and a
 * component count; for bundled documents a table of component offsets follows;
 * the remainder (component sizes, type flags, and identifiers) is BZZ-compressed.
 */
@Slf4j
final class DjvuDirectory {

    static final int TYPE_INCLUDE = 0;
    static final int TYPE_PAGE = 1;
    static final int TYPE_THUMBNAILS = 2;
    static final int TYPE_SHARED_ANNO = 3;

    private static final int HAS_NAME = 0x80;
    private static final int HAS_TITLE = 0x40;
    private static final int TYPE_MASK = 0x3f;
    private static final int MAX_DECOMPRESSED_BYTES = 10 * 1024 * 1024;

    record Component(String id, long offset, int type) {}

    private final boolean bundled;
    private final int fileCount;
    private final List<Component> components;

    private DjvuDirectory(boolean bundled, int fileCount, List<Component> components) {
        this.bundled = bundled;
        this.fileCount = fileCount;
        this.components = components;
    }

    boolean isBundled() {
        return bundled;
    }

    /**
     * The number of pages, counted from the decoded component types; if the
     * compressed part of the directory could not be decoded, falls back to the
     * total component count (an upper bound).
     */
    int getPageCount() {
        if (components.isEmpty()) {
            return fileCount;
        }
        return (int) components.stream().filter(c -> c.type() == TYPE_PAGE).count();
    }

    /** The fully decoded components; empty when the compressed part could not be decoded. */
    List<Component> getComponents() {
        return components;
    }

    static DjvuDirectory parse(byte[] payload) throws IOException {
        if (payload.length < 3) {
            throw new IOException("DIRM chunk too short: " + payload.length + " bytes");
        }
        int flags = payload[0] & 0xff;
        boolean bundled = (flags & 0x80) != 0;
        int version = flags & 0x7f;
        int fileCount = ((payload[1] & 0xff) << 8) | (payload[2] & 0xff);

        int pos = 3;
        long[] offsets = new long[fileCount];
        if (bundled) {
            for (int i = 0; i < fileCount; i++) {
                if (pos + 4 > payload.length) {
                    throw new IOException("DIRM chunk truncated in offset table");
                }
                offsets[i] = ((long) (payload[pos] & 0xff) << 24)
                        | ((payload[pos + 1] & 0xff) << 16)
                        | ((payload[pos + 2] & 0xff) << 8)
                        | (payload[pos + 3] & 0xff);
                pos += 4;
                if (version == 0) {
                    pos += 3; // version 0 stored a 24-bit size next to each offset
                }
            }
        }

        List<Component> components = new ArrayList<>(fileCount);
        try {
            byte[] compressed = new byte[payload.length - pos];
            System.arraycopy(payload, pos, compressed, 0, compressed.length);
            byte[] decoded = BzzInputStream.decode(compressed, MAX_DECOMPRESSED_BYTES);

            int p = 0;
            if (version > 0) {
                p += 3 * fileCount; // 24-bit component sizes, unused here
            }
            if (p + fileCount > decoded.length) {
                throw new IOException("DIRM directory truncated in flags table");
            }
            int[] componentFlags = new int[fileCount];
            for (int i = 0; i < fileCount; i++) {
                int f = decoded[p++] & 0xff;
                if (version == 0) {
                    // Version 0 used different flag bits: 1=page, 2=has-name, 4=has-title
                    int converted = (f & 1) != 0 ? TYPE_PAGE : TYPE_INCLUDE;
                    if ((f & 2) != 0) converted |= HAS_NAME;
                    if ((f & 4) != 0) converted |= HAS_TITLE;
                    f = converted;
                }
                componentFlags[i] = f;
            }
            for (int i = 0; i < fileCount; i++) {
                String id = readNulTerminated(decoded, p);
                p += id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                if ((componentFlags[i] & HAS_NAME) != 0) {
                    String name = readNulTerminated(decoded, p);
                    p += name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                }
                if ((componentFlags[i] & HAS_TITLE) != 0) {
                    String title = readNulTerminated(decoded, p);
                    p += title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                }
                components.add(new Component(id, bundled ? offsets[i] : -1, componentFlags[i] & TYPE_MASK));
            }
        } catch (Exception e) {
            // Degrade gracefully: the page count falls back to the component count
            log.debug("Failed to decode compressed part of DIRM chunk: {}", e.getMessage());
            components = new ArrayList<>();
        }

        return new DjvuDirectory(bundled, fileCount, components);
    }

    private static String readNulTerminated(byte[] data, int start) throws IOException {
        if (start >= data.length) {
            throw new IOException("DIRM directory truncated in identifier table");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = start; i < data.length; i++) {
            if (data[i] == 0) {
                return out.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
            out.write(data[i]);
        }
        throw new IOException("DIRM directory identifier is not NUL-terminated");
    }
}
