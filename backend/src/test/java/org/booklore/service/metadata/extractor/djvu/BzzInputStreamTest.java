package org.booklore.service.metadata.extractor.djvu;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the BZZ decoder against reference streams produced offline with
 * DjVuLibre's {@code bzz -e <input> <output>} tool.
 */
class BzzInputStreamTest {

    /** {@code bzz -e} of the annotation text in {@link #METADATA_TEXT}. */
    private static final byte[] METADATA_BZZ = Base64.getDecoder().decode(
            "//+d+tAT3j2Wg+iyQeAVySBOvzNvK9N0+hLM8vyOnOee2LVFe3/I5Be4l/evZw3dudGJPSzEv9eZxkmUrxff3IEllakw7x9q" +
            "YHotzJFhF+OOkTUedQ==");

    private static final String METADATA_TEXT = """
            (metadata
             (title "The Art of Computer Programming")
             (author "Donald E. Knuth")
             (year "1968"))
            """;

    /**
     * {@code bzz -e} of 10000 bytes generated as {@code (i * 7919) % 96 + 32},
     * exercising the higher move-to-front context bands over multiple block reads.
     */
    private static final byte[] DETERMINISTIC_BZZ = Base64.getDecoder().decode(
            "/9ju/33LcHo35M4kRCR6XsWbXLuNeNButs8RFWgAH8HYcAJBOpIJrUVyYu46bg/IQFz+3x4Bcj1MSXb6TSE+w+fNWMEd+9ri" +
            "JdRCIOnhjrZ1P8bL9h60U3k661EsE/42Dca5Br7HLNl7xyX2mOkRxKGCMwQi2amQl6VBR6qHUQdW4cnXIR84/EOYCxrx8AwR" +
            "+OYkqCj4iLlcw5+2G4mQBwXTa8aYRXWBlj5WNNOSLwoCw7m/zheWYA/+Cc1f41Uvzw==");

    @Test
    void decodesKnownMetadataBlob() throws IOException {
        byte[] decoded = BzzInputStream.decode(METADATA_BZZ, 1024 * 1024);
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(METADATA_TEXT);
    }

    @Test
    void decodesDeterministicMultiContextStream() throws IOException {
        byte[] expected = new byte[10000];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) ((i * 7919) % 96 + 32);
        }
        byte[] decoded = BzzInputStream.decode(DETERMINISTIC_BZZ, 1024 * 1024);
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void decodesEmptyStreamToEmptyOutput() throws IOException {
        assertThat(BzzInputStream.decode(new byte[0], 1024)).isEmpty();
    }

    @Test
    void truncatedStreamThrowsInsteadOfHanging() {
        byte[] truncated = Arrays.copyOf(METADATA_BZZ, 20);
        assertThatThrownBy(() -> BzzInputStream.decode(truncated, 1024 * 1024))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void garbageInputFailsCleanly() {
        byte[] garbage = HexFormat.of().parseHex(
                "0b3b626c51bf270fe2b971d63c98a8cc58a6585601c1ec14c2ddd684ef8499bfacf1f44fa5e6e6f0");
        assertThatThrownBy(() -> BzzInputStream.decode(garbage, 1024 * 1024))
                .isInstanceOf(IOException.class);
    }

    @Test
    void outputCapIsEnforced() {
        assertThatThrownBy(() -> BzzInputStream.decode(DETERMINISTIC_BZZ, 100))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cap");
    }

    @Test
    void readsCorrectlyThroughStreamInterface() throws IOException {
        try (BzzInputStream in = new BzzInputStream(METADATA_BZZ)) {
            byte[] out = in.readAllBytes();
            assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo(METADATA_TEXT);
            assertThat(in.read()).isEqualTo(-1);
        }
    }
}
