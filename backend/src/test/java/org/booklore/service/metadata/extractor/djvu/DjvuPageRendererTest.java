package org.booklore.service.metadata.extractor.djvu;

import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DjvuPageRendererTest {

    /** 32x32 colour page (IW44) with metadata, produced offline with DjVuLibre's c44 + djvused. */
    private static final byte[] COLOR_PAGE = Base64.getDecoder().decode(
            "QVQmVEZPUk0AAAFOREpWTURJUk0AAAAvgQACAAAASAAAAK7//96/mSAhyGV0n/ONhuHQ/qw3tbZkix5FJWpMtn365qtnd38ARk9S" +
            "TQAAAF5ESlZVSU5GTwAAAAoAIAAgGABkABYBSU5DTAAAAA9zaGFyZWRfYW5uby5pZmYAQkc0NAAAABEASgECACAAIIrm4bE32X8q" +
            "iQBCRzQ0AAAABAEP+Z9CRzQ0AAAAAgIKRk9STQAAAKRESlZJQU5UegAAAJj//yr6hEJMlI6+TyekO3tFQ9re7DiKHOsiG61KutRh" +
            "Pk1SU6cKpFEatIx0cajFxleoLmh17nbs0o6Y5NNhGHWpGdhvYO1oTbqFc9IOC7FvXKPe80FiJn+8y/x7ZtvOemelmRPy1P1GpTsP" +
            "/1d8q7BRNbH17VeOu5fWLbRBTU1nKe3YDUWVo1w876WBmUW9Ng3ZuE7eEGBYSw==");

    /** 40x60 bitonal checkerboard page (JB2), produced offline with DjVuLibre's cjb2. */
    private static final byte[] BITONAL_PAGE = Base64.getDecoder().decode(
            "QVQmVEZPUk0AAABBREpWVUlORk8AAAAKACgAPBgALAEWAVNqYnoAAAAjg2u6SI1omykz3YUJIXX/1VB/RCJ/wqT+fmXasl8XKl+M" +
            "tl8=");

    @TempDir
    Path tempDir;

    private FileService fileService;
    private DjvuPageRenderer renderer;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        renderer = new DjvuPageRenderer(fileService);
    }

    private File writeFixture(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file.toFile();
    }

    private static Path findDdjvuOnPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        return Arrays.stream(path.split(File.pathSeparator))
                .map(dir -> Path.of(dir, DjvuPageRenderer.DDJVU_BINARY))
                .filter(Files::isExecutable)
                .findFirst()
                .orElse(null);
    }

    @Test
    void returnsNullWhenDdjvuIsNotInstalled() throws IOException {
        when(fileService.findSystemFile("ddjvu")).thenReturn(null);
        File file = writeFixture("book.djvu", COLOR_PAGE);

        assertThat(renderer.renderFirstPage(file)).isNull();
        assertThat(renderer.renderFirstPageAsJpeg(file)).isNull();
    }

    /** Runs only where DjVuLibre is installed (it is in the Docker image). */
    @Nested
    class WithDdjvu {

        @BeforeEach
        void requireDdjvu() {
            Path ddjvu = findDdjvuOnPath();
            assumeTrue(ddjvu != null, "ddjvu (DjVuLibre) is not installed");
            when(fileService.findSystemFile("ddjvu")).thenReturn(ddjvu);
        }

        @Test
        void rendersColorPageScaledIntoCoverBox() throws IOException {
            BufferedImage image = renderer.renderFirstPage(writeFixture("color.djvu", COLOR_PAGE));

            assertThat(image).isNotNull();
            // Square page scaled to fit the 1000x1500 box, keeping its aspect ratio
            assertThat(image.getWidth()).isEqualTo(1000);
            assertThat(image.getHeight()).isEqualTo(1000);
        }

        @Test
        void rendersBitonalPage() throws IOException {
            BufferedImage image = renderer.renderFirstPage(writeFixture("bitonal.djvu", BITONAL_PAGE));

            assertThat(image).isNotNull();
            // 40x60 page scaled up to the 1000x1500 box
            assertThat(image.getWidth()).isEqualTo(1000);
            assertThat(image.getHeight()).isEqualTo(1500);
        }

        @Test
        void djvExtensionIsRenderedToo() throws IOException {
            assertThat(renderer.renderFirstPage(writeFixture("color.djv", COLOR_PAGE))).isNotNull();
        }

        @Test
        void encodesRenderedPageAsJpeg() throws IOException {
            byte[] jpeg = renderer.renderFirstPageAsJpeg(writeFixture("bitonal.djvu", BITONAL_PAGE));

            assertThat(jpeg).isNotNull();
            // JPEG SOI marker
            assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
            assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertThat(decoded.getWidth()).isEqualTo(1000);
            assertThat(decoded.getHeight()).isEqualTo(1500);
        }

        @Test
        void corruptFileReturnsNull() throws IOException {
            File file = writeFixture("corrupt.djvu", "AT&TFORM garbage that is not a DjVu file".getBytes());

            assertThat(renderer.renderFirstPage(file)).isNull();
            assertThat(renderer.renderFirstPageAsJpeg(file)).isNull();
        }

        @Test
        void missingFileReturnsNull() {
            assertThat(renderer.renderFirstPage(tempDir.resolve("missing.djvu").toFile())).isNull();
        }
    }
}
