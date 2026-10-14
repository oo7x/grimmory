package org.booklore.service.metadata.extractor.djvu;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for the IFF85-style chunk container used by DjVu files. A DjVu file
 * starts with the magic bytes {@code AT&T} followed by a composite {@code FORM}
 * chunk whose secondary identifier is {@code DJVU} (single page), {@code DJVM}
 * (bundled multi-page) or {@code DJVI} (shared component). Every chunk starts on
 * an even file offset; a chunk with an odd length is followed by one pad byte.
 */
final class DjvuIffReader implements Closeable {

    static final int MAGIC_LENGTH = 4;
    private static final byte[] MAGIC = {0x41, 0x54, 0x26, 0x54}; // "AT&T"

    /**
     * One chunk in the file. For composite ({@code FORM}) chunks, {@code secondaryId}
     * holds the form type and {@code dataOffset}/{@code length} describe the payload
     * after the secondary identifier.
     */
    record DjvuChunk(String id, String secondaryId, long dataOffset, long length) {
        boolean isForm() {
            return "FORM".equals(id);
        }
    }

    private final RandomAccessFile file;
    private final long fileLength;

    DjvuIffReader(File f) throws IOException {
        this.file = new RandomAccessFile(f, "r");
        this.fileLength = file.length();
    }

    /** Reads the magic bytes and the outer FORM chunk; throws if this is not a DjVu file. */
    DjvuChunk readOuterForm() throws IOException {
        byte[] magic = new byte[MAGIC_LENGTH];
        file.seek(0);
        file.readFully(magic);
        for (int i = 0; i < MAGIC_LENGTH; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Not a DjVu file: missing AT&T magic header");
            }
        }
        DjvuChunk outer = readChunkAt(MAGIC_LENGTH);
        if (!outer.isForm()) {
            throw new IOException("Not a DjVu file: outer chunk is not FORM");
        }
        return outer;
    }

    /** Reads the chunk header starting at {@code offset}. */
    DjvuChunk readChunkAt(long offset) throws IOException {
        if (offset < 0 || offset + 8 > fileLength) {
            throw new EOFException("Chunk header at offset " + offset + " lies beyond end of file");
        }
        file.seek(offset);
        String id = readId();
        long length = readUint32();
        if (id.equals("FORM")) {
            if (length < 4 || offset + 8 + length > fileLength) {
                throw new IOException("FORM chunk at offset " + offset + " has invalid length " + length);
            }
            String secondaryId = readId();
            return new DjvuChunk(id, secondaryId, offset + 12, length - 4);
        }
        if (offset + 8 + length > fileLength) {
            throw new IOException("Chunk " + id + " at offset " + offset + " has invalid length " + length);
        }
        return new DjvuChunk(id, null, offset + 8, length);
    }

    /** Lists the child chunks of a composite chunk. */
    List<DjvuChunk> children(DjvuChunk composite) throws IOException {
        List<DjvuChunk> result = new ArrayList<>();
        long pos = composite.dataOffset();
        long end = composite.dataOffset() + composite.length();
        while (pos + 8 <= end) {
            DjvuChunk child = readChunkAt(pos);
            result.add(child);
            pos = child.dataOffset() + child.length();
            if ((pos & 1) != 0) {
                pos++;
            }
        }
        return result;
    }

    /** Reads a chunk's payload, refusing payloads larger than {@code maxBytes}. */
    byte[] readPayload(DjvuChunk chunk, int maxBytes) throws IOException {
        if (chunk.length() > maxBytes) {
            throw new IOException("Chunk " + chunk.id() + " payload of " + chunk.length()
                    + " bytes exceeds cap of " + maxBytes);
        }
        byte[] payload = new byte[(int) chunk.length()];
        file.seek(chunk.dataOffset());
        file.readFully(payload);
        return payload;
    }

    private String readId() throws IOException {
        byte[] id = new byte[4];
        file.readFully(id);
        for (byte b : id) {
            if (b < 0x20 || b > 0x7e) {
                throw new IOException("Invalid chunk identifier byte: 0x" + Integer.toHexString(b & 0xff));
            }
        }
        return new String(id, StandardCharsets.US_ASCII);
    }

    private long readUint32() throws IOException {
        return ((long) (file.readByte() & 0xff) << 24)
                | ((file.readByte() & 0xff) << 16)
                | ((file.readByte() & 0xff) << 8)
                | (file.readByte() & 0xff);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
