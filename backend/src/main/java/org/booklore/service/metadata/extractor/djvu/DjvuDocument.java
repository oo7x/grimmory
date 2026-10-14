package org.booklore.service.metadata.extractor.djvu;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.booklore.service.metadata.extractor.djvu.DjvuIffReader.DjvuChunk;

/**
 * Structural model of a DjVu file: page count, first-page geometry, and the
 * document metadata gathered from annotation chunks ({@code ANTa}/{@code ANTz}).
 * Shared (document-wide) annotations are merged first, then the first page's own
 * annotations, so page-level metadata keys override shared ones.
 */
@Slf4j
@Getter
public final class DjvuDocument {

    private static final int MAX_ANNOTATION_BYTES = 1024 * 1024;
    private static final int INFO_CHUNK_CAP = 64;

    private int pageCount;
    private int width;
    private int height;
    private int dpi;
    /** Metadata key/value pairs from the {@code (metadata ...)} annotation form, lowercased keys. */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private DjvuDocument() {
    }

    public static DjvuDocument parse(File file) throws IOException {
        DjvuDocument doc = new DjvuDocument();
        try (DjvuIffReader reader = new DjvuIffReader(file)) {
            DjvuChunk outer = reader.readOuterForm();
            switch (outer.secondaryId()) {
                case "DJVU" -> doc.parseSinglePage(reader, outer);
                case "DJVM" -> doc.parseMultiPage(reader, outer);
                default -> {
                    // A bare component (DJVI etc.): nothing beyond validity is known
                    doc.pageCount = 0;
                }
            }
        }
        return doc;
    }

    private void parseSinglePage(DjvuIffReader reader, DjvuChunk pageForm) throws IOException {
        pageCount = 1;
        readPageChunks(reader, pageForm, metadata);
    }

    private void parseMultiPage(DjvuIffReader reader, DjvuChunk documentForm) throws IOException {
        List<DjvuChunk> children = reader.children(documentForm);

        DjvuDirectory directory = null;
        for (DjvuChunk child : children) {
            if ("DIRM".equals(child.id())) {
                directory = DjvuDirectory.parse(reader.readPayload(child, 16 * 1024 * 1024));
                break;
            }
        }
        if (directory == null) {
            log.debug("Multi-page DjVu without DIRM chunk; treating as empty document");
            pageCount = 0;
            return;
        }
        pageCount = directory.getPageCount();

        if (!directory.isBundled()) {
            // Indirect document: components live in separate files that are never
            // opened here (the library scanner ingests them individually)
            return;
        }

        // Map component ids to the child FORM chunks by matching DIRM offsets against
        // actual chunk positions (offsets are counted from the start of the file)
        Map<Long, DjvuChunk> formsByOffset = new HashMap<>();
        for (DjvuChunk child : children) {
            if (child.isForm()) {
                formsByOffset.put(child.dataOffset() - 12, child);
            }
        }
        Map<String, DjvuChunk> formsById = new HashMap<>();
        DjvuChunk sharedAnno = null;
        DjvuChunk firstPage = null;
        for (DjvuDirectory.Component component : directory.getComponents()) {
            DjvuChunk form = formsByOffset.get(component.offset());
            if (form == null) {
                continue;
            }
            formsById.put(component.id(), form);
            if (component.type() == DjvuDirectory.TYPE_SHARED_ANNO && sharedAnno == null) {
                sharedAnno = form;
            }
            if (component.type() == DjvuDirectory.TYPE_PAGE && firstPage == null) {
                firstPage = form;
            }
        }

        // Some producers store document metadata in a DJVI component without the
        // shared-annotation type flag; detect those by content
        if (sharedAnno == null) {
            for (DjvuChunk child : children) {
                if (child.isForm() && "DJVI".equals(child.secondaryId()) && containsAnnotations(reader, child)) {
                    sharedAnno = child;
                    break;
                }
            }
        }
        if (firstPage == null) {
            for (DjvuChunk child : children) {
                if (child.isForm() && "DJVU".equals(child.secondaryId())) {
                    firstPage = child;
                    break;
                }
            }
        }

        // Shared metadata first, then the first page's own (page keys win)
        if (sharedAnno != null) {
            Map<String, String> shared = new LinkedHashMap<>();
            readAnnotationChunks(reader, sharedAnno, shared);
            metadata.putAll(shared);
        }
        if (firstPage != null) {
            Map<String, String> page = new LinkedHashMap<>();
            readPageChunks(reader, firstPage, page, formsById, new HashSet<>());
            metadata.putAll(page);
        }
    }

    private void readPageChunks(DjvuIffReader reader, DjvuChunk pageForm, Map<String, String> target)
            throws IOException {
        readPageChunks(reader, pageForm, target, Map.of(), new HashSet<>());
    }

    private void readPageChunks(DjvuIffReader reader, DjvuChunk pageForm, Map<String, String> target,
                                Map<String, DjvuChunk> formsById, Set<String> visited) throws IOException {
        for (DjvuChunk chunk : reader.children(pageForm)) {
            switch (chunk.id()) {
                case "INFO" -> readInfo(reader, chunk);
                case "ANTa", "ANTz" -> mergeAnnotation(reader, chunk, target);
                case "INCL" -> {
                    String includedId = new String(
                            reader.readPayload(chunk, 4096), StandardCharsets.UTF_8).trim();
                    DjvuChunk included = formsById.get(includedId);
                    if (included != null && visited.add(includedId)) {
                        // Included components contribute their annotations before the
                        // page's own, so later page-level chunks override them
                        readAnnotationChunks(reader, included, target);
                    }
                }
                default -> {
                    // Image data chunks (Sjbz, BG44, FGbz, ...) are not needed
                }
            }
        }
    }

    private void readAnnotationChunks(DjvuIffReader reader, DjvuChunk form, Map<String, String> target)
            throws IOException {
        for (DjvuChunk chunk : reader.children(form)) {
            if ("ANTa".equals(chunk.id()) || "ANTz".equals(chunk.id())) {
                mergeAnnotation(reader, chunk, target);
            }
        }
    }

    private boolean containsAnnotations(DjvuIffReader reader, DjvuChunk form) {
        try {
            for (DjvuChunk chunk : reader.children(form)) {
                if ("ANTa".equals(chunk.id()) || "ANTz".equals(chunk.id())) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.debug("Failed to inspect DJVI component: {}", e.getMessage());
        }
        return false;
    }

    private void mergeAnnotation(DjvuIffReader reader, DjvuChunk chunk, Map<String, String> target) {
        try {
            byte[] payload = reader.readPayload(chunk, MAX_ANNOTATION_BYTES);
            if ("ANTz".equals(chunk.id())) {
                payload = BzzInputStream.decode(payload, MAX_ANNOTATION_BYTES);
            }
            String text = new String(payload, StandardCharsets.UTF_8);
            target.putAll(DjvuAnnotationParser.parseMetadata(text));
        } catch (Exception e) {
            log.debug("Failed to decode {} annotation chunk: {}", chunk.id(), e.getMessage());
        }
    }

    private void readInfo(DjvuIffReader reader, DjvuChunk chunk) {
        try {
            if (width > 0 || height > 0) {
                return; // keep the first page's geometry
            }
            byte[] info = reader.readPayload(chunk, INFO_CHUNK_CAP);
            if (info.length >= 4) {
                width = ((info[0] & 0xff) << 8) | (info[1] & 0xff);
                height = ((info[2] & 0xff) << 8) | (info[3] & 0xff);
            }
            if (info.length >= 8) {
                // The DPI field is little-endian, unlike every other integer in the format
                dpi = (info[6] & 0xff) | ((info[7] & 0xff) << 8);
            }
        } catch (IOException e) {
            log.debug("Failed to read INFO chunk: {}", e.getMessage());
        }
    }
}
