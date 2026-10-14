package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.DjvuMetadataExtractor;
import org.booklore.service.metadata.extractor.djvu.DjvuPageRenderer;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.BookCoverUtils;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.booklore.util.FileService.truncate;

@Slf4j
@Service
public class DjvuProcessor extends AbstractFileProcessor implements BookFileProcessor {

    private final DjvuMetadataExtractor djvuMetadataExtractor;
    private final DjvuPageRenderer djvuPageRenderer;

    public DjvuProcessor(BookRepository bookRepository,
                         BookAdditionalFileRepository bookAdditionalFileRepository,
                         BookCreatorService bookCreatorService,
                         BookMapper bookMapper,
                         FileService fileService,
                         MetadataMatchService metadataMatchService,
                         SidecarMetadataWriter sidecarMetadataWriter,
                         DjvuMetadataExtractor djvuMetadataExtractor,
                         DjvuPageRenderer djvuPageRenderer) {
        super(bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper, fileService, metadataMatchService, sidecarMetadataWriter);
        this.djvuMetadataExtractor = djvuMetadataExtractor;
        this.djvuPageRenderer = djvuPageRenderer;
    }

    @Override
    public BookEntity processNewFile(LibraryFile libraryFile) {
        BookEntity bookEntity = bookCreatorService.createShellBook(libraryFile, BookFileType.DJVU);
        setBookMetadata(bookEntity);
        // Like PDF, the cover is the rendered first page; the folder image is the fallback
        boolean coverGenerated = generateCover(bookEntity);
        if (!coverGenerated) {
            var folder = getBookFolderForCoverFallback(libraryFile);
            if (folder != null) {
                coverGenerated = generateCoverFromFolderImage(bookEntity, folder);
            }
        }
        if (coverGenerated) {
            FileService.setBookCoverPath(bookEntity.getMetadata());
            bookEntity.setBookCoverHash(BookCoverUtils.generateCoverHash());
        }
        return bookEntity;
    }

    @Override
    public boolean generateCover(BookEntity bookEntity) {
        return generateCover(bookEntity, bookEntity.getPrimaryBookFile());
    }

    @Override
    public boolean generateCover(BookEntity bookEntity, BookFileEntity bookFile) {
        Path djvuPath = FileUtils.getBookFullPath(bookEntity, bookFile);
        if (djvuPath == null) {
            return false;
        }
        BufferedImage coverImage = djvuPageRenderer.renderFirstPage(djvuPath.toFile());
        if (coverImage == null) {
            return false;
        }
        try {
            return fileService.saveCoverImages(coverImage, bookEntity.getId());
        } catch (Exception e) {
            log.warn("Failed to save cover for '{}': {}", bookFile.getFileName(), e.getMessage());
            return false;
        } finally {
            coverImage.flush();
        }
    }

    @Override
    public List<BookFileType> getSupportedTypes() {
        return List.of(BookFileType.DJVU);
    }

    private void setBookMetadata(BookEntity bookEntity) {
        File bookFile = new File(bookEntity.getFullFilePath().toUri());
        BookMetadata djvuMetadata = djvuMetadataExtractor.extractMetadata(bookFile);
        if (djvuMetadata == null) return;

        BookMetadataEntity metadata = bookEntity.getMetadata();

        metadata.setTitle(truncate(djvuMetadata.getTitle(), 1000));
        metadata.setSubtitle(truncate(djvuMetadata.getSubtitle(), 1000));
        metadata.setDescription(truncate(djvuMetadata.getDescription(), 2000));
        metadata.setPublisher(truncate(djvuMetadata.getPublisher(), 1000));
        metadata.setPublishedDate(djvuMetadata.getPublishedDate());
        metadata.setSeriesName(truncate(djvuMetadata.getSeriesName(), 1000));
        metadata.setSeriesNumber(djvuMetadata.getSeriesNumber());
        metadata.setSeriesTotal(djvuMetadata.getSeriesTotal());
        metadata.setIsbn13(truncate(djvuMetadata.getIsbn13(), 13));
        metadata.setIsbn10(truncate(djvuMetadata.getIsbn10(), 10));
        metadata.setPageCount(djvuMetadata.getPageCount());

        String lang = djvuMetadata.getLanguage();
        if (lang != null && !"UND".equalsIgnoreCase(lang)) {
            metadata.setLanguage(truncate(lang, 10));
        }

        bookCreatorService.addAuthorsToBook(djvuMetadata.getAuthors(), bookEntity);

        if (djvuMetadata.getCategories() != null) {
            Set<String> validSubjects = djvuMetadata.getCategories().stream()
                    .filter(s -> s != null && !s.isBlank() && s.length() <= 100 && !s.contains("\n") && !s.contains("\r") && !s.contains("  "))
                    .collect(Collectors.toSet());
            bookCreatorService.addCategoriesToBook(validSubjects, bookEntity);
        }
    }
}
