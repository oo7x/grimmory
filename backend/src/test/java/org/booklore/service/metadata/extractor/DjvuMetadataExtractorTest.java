package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.booklore.service.metadata.extractor.djvu.DjvuPageRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DjvuMetadataExtractorTest {

    @TempDir
    Path tempDir;

    private DjvuPageRenderer pageRenderer;
    private DjvuMetadataExtractor extractor;

    @BeforeEach
    void setUp() {
        pageRenderer = mock(DjvuPageRenderer.class);
        extractor = new DjvuMetadataExtractor(pageRenderer);
    }

    @Nested
    class Cover {

        @Test
        void coverIsTheRenderedFirstPage() {
            File file = tempDir.resolve("book.djvu").toFile();
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2};
            when(pageRenderer.renderFirstPageAsJpeg(file)).thenReturn(jpeg);

            assertThat(extractor.extractCover(file)).isSameAs(jpeg);
        }

        @Test
        void noCoverWhenPageCannotBeRendered() {
            File file = tempDir.resolve("book.djvu").toFile();
            when(pageRenderer.renderFirstPageAsJpeg(file)).thenReturn(null);

            assertThat(extractor.extractCover(file)).isNull();
        }
    }

    /**
     * A real file produced offline with DjVuLibre:
     * {@code c44} of a 32x32 image, then {@code djvused -e "set-meta ..." -s} with
     * title "Single Page Book", author "Alice Author; Bob Builder", year 1968,
     * isbn 978-0-201-03801-5, publisher "Test Press", description "A test file",
     * keywords "computing, algorithms". djvused converts the single-page file into
     * a bundled DJVM with the metadata in a BZZ-compressed shared_anno component.
     */
    private static final byte[] SINGLE_PAGE_WITH_METADATA = Base64.getDecoder().decode(
            "QVQmVEZPUk0AAAFOREpWTURJUk0AAAAvgQACAAAASAAAAK7//96/mSAhyGV0n/ONhuHQ/qw3tbZkix5FJWpMtn365qtnd38A" +
            "Rk9STQAAAF5ESlZVSU5GTwAAAAoAIAAgGABkABYBSU5DTAAAAA9zaGFyZWRfYW5uby5pZmYAQkc0NAAAABEASgECACAAIIrm" +
            "4bE32X8qiQBCRzQ0AAAABAEP+Z9CRzQ0AAAAAgIKRk9STQAAAKRESlZJQU5UegAAAJj//yr6hEJMlI6+TyekO3tFQ9re7DiK" +
            "HOsiG61KutRhPk1SU6cKpFEatIx0cajFxleoLmh17nbs0o6Y5NNhGHWpGdhvYO1oTbqFc9IOC7FvXKPe80FiJn+8y/x7ZtvO" +
            "emelmRPy1P1GpTsP/1d8q7BRNbH17VeOu5fWLbRBTU1nKe3YDUWVo1w876WBmUW9Ng3ZuE7eEGBYSw==");

    /**
     * A real two-page bundled document produced offline with DjVuLibre's
     * {@code djvm -c} plus {@code djvused set-meta} with title "Bundled Book",
     * author "Carol Composer", date "2001-05-17".
     */
    private static final byte[] BUNDLED_TWO_PAGES = Base64.getDecoder().decode(
            "QVQmVEZPUk0AAAFsREpWTURJUk0AAAA5gQADAAAAUgAAALgAAAES///Sv4ohxNOP719+At2ShKakogP/d/3BI9h4Mx1gDUNu" +
            "sIZaXEFuuP3bAEZPUk0AAABeREpWVUlORk8AAAAKACAAIBgAZAAWAUlOQ0wAAAAPc2hhcmVkX2Fubm8uaWZmAEJHNDQAAAAR" +
            "AEoBAgAgACCK5uGxN9l/KokAQkc0NAAAAAQBD/mfQkc0NAAAAAICCkZPUk0AAABSREpWSUFOVHoAAABG//+q+oKcTJSQClAm" +
            "9Z/X+bMzbYoiFr6ojOX0dtuiO0YLgScz/1rSLgCLPGcMnNmdqYsFcpGEievwUp3KGICgtTCtMa6Yg0ZPUk0AAABeREpWVUlO" +
            "Rk8AAAAKACAAIBgAZAAWAUlOQ0wAAAAPc2hhcmVkX2Fubm8uaWZmAEJHNDQAAAARAEoBAgAgACCK5uGxN9l/KokAQkc0NAAA" +
            "AAQBD/mfQkc0NAAAAAICCg==");

    private File writeFixture(String name, byte[] content) throws IOException {
        File file = tempDir.resolve(name).toFile();
        Files.write(file.toPath(), content);
        return file;
    }

    private File writeSinglePageWithAnta(String name, String annotationText) throws IOException {
        return writeFixture(name, new DjvuFileBuilder()
                .beginForm("DJVU")
                .chunk("INFO", infoPayload(2550, 3300, 300))
                .chunk("ANTa", annotationText.getBytes(StandardCharsets.UTF_8))
                .build());
    }

    private static byte[] infoPayload(int width, int height, int dpi) {
        return new byte[]{
                (byte) (width >> 8), (byte) width,
                (byte) (height >> 8), (byte) height,
                24, 0,
                (byte) (dpi & 0xff), (byte) (dpi >> 8), // little-endian DPI
                22, 1
        };
    }

    @Nested
    class RealFiles {

        @Test
        void extractsMetadataFromDjvusedTaggedFile() throws IOException {
            File file = writeFixture("book.djvu", SINGLE_PAGE_WITH_METADATA);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Single Page Book");
            assertThat(metadata.getAuthors()).containsExactly("Alice Author", "Bob Builder");
            assertThat(metadata.getPublisher()).isEqualTo("Test Press");
            assertThat(metadata.getDescription()).isEqualTo("A test file");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1968, 1, 1));
            assertThat(metadata.getIsbn13()).isEqualTo("9780201038015");
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("computing", "algorithms");
            assertThat(metadata.getPageCount()).isEqualTo(1);
        }

        @Test
        void extractsMetadataFromBundledDocument() throws IOException {
            File file = writeFixture("bundle.djvu", BUNDLED_TWO_PAGES);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Bundled Book");
            assertThat(metadata.getAuthors()).containsExactly("Carol Composer");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2001, 5, 17));
            assertThat(metadata.getPageCount()).isEqualTo(2);
        }

        @Test
        void djvExtensionIsHandledTheSame() throws IOException {
            File file = writeFixture("book.djv", SINGLE_PAGE_WITH_METADATA);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Single Page Book");
        }
    }

    @Nested
    class SyntheticSinglePage {

        @Test
        void extractsPlainTextAnnotationMetadata() throws IOException {
            File file = writeSinglePageWithAnta("plain.djvu", """
                    (metadata
                     (title "Synthetic Title")
                     (author "Ann Author, Ben Bookman")
                     (publisher "Plain Press")
                     (description "Handmade fixture")
                     (isbn "0-201-03801-3")
                     (date "1999-12-31")
                     (keywords "one; two")
                     (language "de"))
                    """);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Synthetic Title");
            assertThat(metadata.getAuthors()).containsExactly("Ann Author", "Ben Bookman");
            assertThat(metadata.getPublisher()).isEqualTo("Plain Press");
            assertThat(metadata.getDescription()).isEqualTo("Handmade fixture");
            assertThat(metadata.getIsbn10()).isEqualTo("0201038013");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(1999, 12, 31));
            assertThat(metadata.getCategories()).containsExactlyInAnyOrder("one", "two");
            assertThat(metadata.getLanguage()).isEqualTo("de");
            assertThat(metadata.getPageCount()).isEqualTo(1);
        }

        @Test
        void capitalizedKeysFromPdf2djvuAreMatched() throws IOException {
            File file = writeSinglePageWithAnta("frompdf.djvu", """
                    (metadata
                     (Title "Converted Title")
                     (Author "Pat Producer")
                     (CreationDate "2010-06-01T12:00:00Z"))
                    """);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Converted Title");
            assertThat(metadata.getAuthors()).containsExactly("Pat Producer");
            assertThat(metadata.getPublishedDate()).isEqualTo(LocalDate.of(2010, 6, 1));
        }

        @Test
        void escapedQuotesInsideValuesAreUnescaped() throws IOException {
            File file = writeSinglePageWithAnta("escaped.djvu",
                    "(metadata (title \"The \\\"Quoted\\\" Word\"))");
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("The \"Quoted\" Word");
        }

        @Test
        void otherAnnotationFormsAreIgnored() throws IOException {
            File file = writeSinglePageWithAnta("mixed.djvu", """
                    (background #FFFFFF) (zoom page)
                    (maparea "http://example.com" "link" (rect 10 10 50 50))
                    (metadata (title "Among Friends"))
                    """);
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Among Friends");
        }

        @Test
        void fileWithoutAnnotationsStillYieldsPageCount() throws IOException {
            File file = writeFixture("bare.djvu", new DjvuFileBuilder()
                    .beginForm("DJVU")
                    .chunk("INFO", infoPayload(100, 200, 150))
                    .build());
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isNull();
            assertThat(metadata.getPageCount()).isEqualTo(1);
        }

        @Test
        void oddLengthChunksArePaddedCorrectly() throws IOException {
            // The ANTa payload has odd length, so the following chunk starts after a pad byte
            File file = writeFixture("padded.djvu", new DjvuFileBuilder()
                    .beginForm("DJVU")
                    .chunk("INFO", infoPayload(100, 200, 150))
                    .chunk("ANTa", "(metadata (title \"Odd\"))".getBytes(StandardCharsets.UTF_8))
                    .chunk("ANTa", "(metadata (author \"Even\"))".getBytes(StandardCharsets.UTF_8))
                    .build());
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isEqualTo("Odd");
            assertThat(metadata.getAuthors()).containsExactly("Even");
        }
    }

    @Nested
    class MalformedFiles {

        @Test
        void wrongMagicReturnsNull() throws IOException {
            File file = writeFixture("fake.djvu", "%PDF-1.4 not a djvu".getBytes(StandardCharsets.UTF_8));
            assertThat(extractor.extractMetadata(file)).isNull();
        }

        @Test
        void emptyFileReturnsNull() throws IOException {
            File file = writeFixture("empty.djvu", new byte[0]);
            assertThat(extractor.extractMetadata(file)).isNull();
        }

        @Test
        void chunkLengthBeyondEndOfFileReturnsNull() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("AT&TFORM".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(new byte[]{0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}); // absurd length
            out.writeBytes("DJVU".getBytes(StandardCharsets.US_ASCII));
            File file = writeFixture("overlong.djvu", out.toByteArray());
            assertThat(extractor.extractMetadata(file)).isNull();
        }

        @Test
        void corruptedAnnotationStillYieldsStructuralMetadata() throws IOException {
            // A valid container whose ANTz payload is garbage must not fail the parse
            File file = writeFixture("badantz.djvu", new DjvuFileBuilder()
                    .beginForm("DJVU")
                    .chunk("INFO", infoPayload(100, 200, 150))
                    .chunk("ANTz", new byte[]{0x0b, 0x3b, 0x62, 0x6c, 0x51, (byte) 0xbf})
                    .build());
            BookMetadata metadata = extractor.extractMetadata(file);

            assertThat(metadata).isNotNull();
            assertThat(metadata.getTitle()).isNull();
            assertThat(metadata.getPageCount()).isEqualTo(1);
        }

        @Test
        void truncatedRealFileReturnsNull() throws IOException {
            byte[] truncated = new byte[40];
            System.arraycopy(SINGLE_PAGE_WITH_METADATA, 0, truncated, 0, 40);
            File file = writeFixture("truncated.djvu", truncated);
            assertThat(extractor.extractMetadata(file)).isNull();
        }
    }

    /** Builds minimal DjVu byte structures: AT&T magic + FORM with child chunks. */
    private static final class DjvuFileBuilder {

        private record ChunkSpec(String id, byte[] payload) {}

        private String formType;
        private final java.util.List<ChunkSpec> chunks = new java.util.ArrayList<>();

        DjvuFileBuilder beginForm(String secondaryId) {
            this.formType = secondaryId;
            return this;
        }

        DjvuFileBuilder chunk(String id, byte[] payload) {
            chunks.add(new ChunkSpec(id, payload));
            return this;
        }

        byte[] build() {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            inner.writeBytes(formType.getBytes(StandardCharsets.US_ASCII));
            for (ChunkSpec chunk : chunks) {
                inner.writeBytes(chunk.id().getBytes(StandardCharsets.US_ASCII));
                writeUint32(inner, chunk.payload().length);
                inner.writeBytes(chunk.payload());
                if ((chunk.payload().length & 1) != 0) {
                    inner.write(0);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("AT&T".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes("FORM".getBytes(StandardCharsets.US_ASCII));
            writeUint32(out, inner.size());
            out.writeBytes(inner.toByteArray());
            return out.toByteArray();
        }

        private static void writeUint32(ByteArrayOutputStream out, int value) {
            out.write((value >> 24) & 0xff);
            out.write((value >> 16) & 0xff);
            out.write((value >> 8) & 0xff);
            out.write(value & 0xff);
        }
    }
}
