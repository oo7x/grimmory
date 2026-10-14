package org.booklore.service.metadata.extractor.djvu;

import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Renders a DjVu page to an image by running DjVuLibre's {@code ddjvu} command-line
 * tool. Decoding DjVu image layers (JB2, IW44) is far beyond the structural parser in
 * this package, so page rendering is delegated to the external binary, the same way
 * audiobook chapters use {@code ffprobe}.
 * <p>
 * The page is written to stdout as PNM (PBM/PGM/PPM, depending on the page type) and
 * decoded with ImageIO through the TwelveMonkeys PNM plugin. TIFF cannot be used
 * because {@code ddjvu} needs a seekable output for it.
 * <p>
 * Every failure (missing binary, corrupt file, timeout, oversized output) yields
 * {@code null}, so callers can fall back to other cover sources.
 */
@Slf4j
@Component
public class DjvuPageRenderer {

    static final String DDJVU_BINARY = "ddjvu";

    /** Bounding box for the rendered page; matches the maximum stored cover size. */
    private static final int MAX_WIDTH = 1000;
    private static final int MAX_HEIGHT = 1500;
    /** A 1000x1500 colour PPM is about 4.5 MB; anything far larger is not a sane page. */
    private static final int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;
    private static final long TIMEOUT_SECONDS = 30;

    private final FileService fileService;

    public DjvuPageRenderer(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Renders the first page of a DjVu document.
     *
     * @param file the {@code .djvu}/{@code .djv} file
     * @return the rendered page, or {@code null} if it could not be rendered
     */
    public BufferedImage renderFirstPage(File file) {
        Path ddjvu = fileService.findSystemFile(DDJVU_BINARY);
        if (ddjvu == null) {
            log.debug("ddjvu binary not available, skipping DjVu page rendering for {}", file.getName());
            return null;
        }

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ddjvu.toAbsolutePath().toString(),
                    "-format=pnm",
                    "-page=1",
                    "-size=" + MAX_WIDTH + "x" + MAX_HEIGHT,
                    file.getAbsolutePath(),
                    "-"
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            process.getOutputStream().close();

            // Read stdout on a virtual thread (blocking I/O) so a stuck process can be timed out
            Process running = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(
                    () -> readCapped(running),
                    task -> Thread.ofVirtual().name("ddjvu-output").start(task));

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ddjvu timed out after {}s rendering {}", TIMEOUT_SECONDS, file.getName());
                return null;
            }
            if (process.exitValue() != 0) {
                log.debug("ddjvu exited with code {} for {}", process.exitValue(), file.getName());
                return null;
            }

            byte[] pnm = output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (pnm == null || pnm.length == 0) {
                log.debug("ddjvu produced no image for {}", file.getName());
                return null;
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pnm));
            if (image == null) {
                log.debug("No ImageIO reader could decode ddjvu output for {}", file.getName());
            }
            return image;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | ExecutionException | TimeoutException e) {
            log.debug("Failed to render DjVu page for {}: {}", file.getName(), e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Renders the first page of a DjVu document and encodes it as JPEG.
     *
     * @return JPEG bytes, or {@code null} if the page could not be rendered
     */
    public byte[] renderFirstPageAsJpeg(File file) {
        BufferedImage image = renderFirstPage(file);
        if (image == null) {
            return null;
        }
        try {
            BufferedImage rgb = toRgb(image);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(rgb, "jpeg", out)) {
                log.debug("No JPEG writer available to encode DjVu cover for {}", file.getName());
                return null;
            }
            if (rgb != image) {
                rgb.flush();
            }
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("Failed to encode DjVu cover for {}: {}", file.getName(), e.getMessage());
            return null;
        } finally {
            image.flush();
        }
    }

    /** JPEG cannot hold 1-bit or indexed images, so bitonal pages are converted first. */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(image, 0, 0, Color.WHITE, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static byte[] readCapped(Process process) {
        try (InputStream in = process.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_OUTPUT_BYTES) {
                    log.debug("ddjvu output exceeded {} bytes; discarding", MAX_OUTPUT_BYTES);
                    // Stop the process; otherwise it blocks on a full pipe until the timeout
                    process.destroyForcibly();
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
