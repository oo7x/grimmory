package org.booklore.service.metadata.extractor.djvu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decoder for the BZZ general-purpose compression format used inside DjVu files
 * (a Burrows-Wheeler transform with a move-to-front coder entropy-coded by the
 * ZP-coder), implemented from the published DjVu v3 reference specification.
 * BZZ compresses the {@code ANTz} annotation chunk and the tail of the
 * {@code DIRM} directory chunk.
 */
final class BzzInputStream extends InputStream {

    private static final int MAX_BLOCK_BYTES = 4096 * 1024;
    private static final int FREQMAX = 4;
    private static final int CTXIDS = 3;

    private final ZpDecoder zp;
    private final int[] ctx = new int[300];

    private byte[] data;
    private int size;
    private int bptr;
    private boolean eof;

    BzzInputStream(byte[] compressed) throws IOException {
        this.zp = new ZpDecoder(compressed);
    }

    /**
     * Fully decodes a BZZ stream, refusing to produce more than {@code maxOutput}
     * bytes so a malformed or malicious stream cannot exhaust memory.
     */
    static byte[] decode(byte[] compressed, int maxOutput) throws IOException {
        try (BzzInputStream in = new BzzInputStream(compressed)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > maxOutput) {
                    throw new IOException("BZZ output exceeds cap of " + maxOutput + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (eof) {
            return -1;
        }
        int copied = 0;
        while (len > 0 && !eof) {
            if (size == 0) {
                bptr = 0;
                if (decodeBlock() == 0) {
                    size = 1;
                    eof = true;
                }
                size -= 1;
            }
            int bytes = Math.min(size, len);
            if (bytes > 0) {
                System.arraycopy(data, bptr, buffer, off, bytes);
                off += bytes;
            }
            size -= bytes;
            bptr += bytes;
            len -= bytes;
            copied += bytes;
        }
        return copied == 0 && eof ? -1 : copied;
    }

    private int decodeRaw(int bits) throws IOException {
        int n = 1;
        int m = 1 << bits;
        while (n < m) {
            n = (n << 1) | zp.decodeRaw();
        }
        return n - m;
    }

    private int decodeBinary(int ctxOffset, int bits) throws IOException {
        int n = 1;
        int m = 1 << bits;
        while (n < m) {
            n = (n << 1) | zp.decode(ctx, ctxOffset - 1 + n);
        }
        return n - m;
    }

    /** Decodes the next block into {@code data}; returns the raw block size (0 at end of stream). */
    private int decodeBlock() throws IOException {
        size = decodeRaw(24);
        if (size == 0) {
            return 0;
        }
        if (size > MAX_BLOCK_BYTES) {
            throw new IOException("BZZ block size " + size + " exceeds maximum");
        }
        if (data == null || data.length < size) {
            data = new byte[size];
        }

        // Estimation speed
        int fshift = 0;
        if (zp.decodeRaw() != 0) {
            fshift += 1;
            if (zp.decodeRaw() != 0) {
                fshift += 1;
            }
        }

        // Move-to-front list and frequency counters
        int[] mtf = new int[256];
        for (int i = 0; i < 256; i++) {
            mtf[i] = i;
        }
        int[] freq = new int[FREQMAX];
        int fadd = 4;

        // Decode the MTF symbol stream
        int mtfno = 3;
        int markerpos = -1;
        for (int i = 0; i < size; i++) {
            int ctxid = Math.min(CTXIDS - 1, mtfno);
            decoded:
            {
                if (zp.decode(ctx, ctxid) != 0) {
                    mtfno = 0;
                } else if (zp.decode(ctx, CTXIDS + ctxid) != 0) {
                    mtfno = 1;
                } else if (zp.decode(ctx, 2 * CTXIDS) != 0) {
                    mtfno = 2 + decodeBinary(2 * CTXIDS + 1, 1);
                } else if (zp.decode(ctx, 2 * CTXIDS + 2) != 0) {
                    mtfno = 4 + decodeBinary(2 * CTXIDS + 3, 2);
                } else if (zp.decode(ctx, 2 * CTXIDS + 6) != 0) {
                    mtfno = 8 + decodeBinary(2 * CTXIDS + 7, 3);
                } else if (zp.decode(ctx, 2 * CTXIDS + 14) != 0) {
                    mtfno = 16 + decodeBinary(2 * CTXIDS + 15, 4);
                } else if (zp.decode(ctx, 2 * CTXIDS + 30) != 0) {
                    mtfno = 32 + decodeBinary(2 * CTXIDS + 31, 5);
                } else if (zp.decode(ctx, 2 * CTXIDS + 62) != 0) {
                    mtfno = 64 + decodeBinary(2 * CTXIDS + 63, 6);
                } else if (zp.decode(ctx, 2 * CTXIDS + 126) != 0) {
                    mtfno = 128 + decodeBinary(2 * CTXIDS + 127, 7);
                } else {
                    // Escape symbol: the BWT marker position
                    mtfno = 256;
                    data[i] = 0;
                    markerpos = i;
                    break decoded;
                }
                data[i] = (byte) mtf[mtfno];

                // Rotate the MTF list according to adaptive frequencies
                fadd = fadd + (fadd >> fshift);
                if (fadd > 0x10000000) {
                    fadd >>= 24;
                    for (int k = 0; k < FREQMAX; k++) {
                        freq[k] >>= 24;
                    }
                }
                int fc = fadd;
                if (mtfno < FREQMAX) {
                    fc += freq[mtfno];
                }
                int k;
                for (k = mtfno; k >= FREQMAX; k--) {
                    mtf[k] = mtf[k - 1];
                }
                for (; k > 0 && fc >= freq[k - 1]; k--) {
                    mtf[k] = mtf[k - 1];
                    freq[k] = freq[k - 1];
                }
                mtf[k] = data[i] & 0xff;
                freq[k] = fc;
            }
        }

        // Inverse Burrows-Wheeler transform
        if (markerpos < 1 || markerpos >= size) {
            throw new IOException("Corrupt BZZ block: invalid BWT marker position");
        }
        int[] posn = new int[size];
        int[] count = new int[256];
        for (int i = 0; i < markerpos; i++) {
            int c = data[i] & 0xff;
            posn[i] = (c << 24) | (count[c] & 0xffffff);
            count[c] += 1;
        }
        for (int i = markerpos + 1; i < size; i++) {
            int c = data[i] & 0xff;
            posn[i] = (c << 24) | (count[c] & 0xffffff);
            count[c] += 1;
        }
        int last = 1;
        for (int i = 0; i < 256; i++) {
            int tmp = count[i];
            count[i] = last;
            last += tmp;
        }
        int i = 0;
        last = size - 1;
        while (last > 0) {
            int n = posn[i];
            int c = posn[i] >>> 24;
            data[--last] = (byte) c;
            i = count[c] + (n & 0xffffff);
            if (i < 0 || i >= size) {
                throw new IOException("Corrupt BZZ block: BWT permutation out of range");
            }
        }
        if (i != markerpos) {
            throw new IOException("Corrupt BZZ block: BWT permutation does not close");
        }
        return size;
    }
}
