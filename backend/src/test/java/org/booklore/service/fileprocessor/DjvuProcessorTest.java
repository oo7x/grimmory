package org.booklore.service.fileprocessor;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.LibraryOrganizationMode;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.book.BookCreatorService;
import org.booklore.service.metadata.MetadataMatchService;
import org.booklore.service.metadata.extractor.DjvuMetadataExtractor;
import org.booklore.service.metadata.extractor.djvu.DjvuPageRenderer;
import org.booklore.service.metadata.sidecar.SidecarMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DjvuProcessorTest {

    private static final long BOOK_ID = 42L;

    @Mock private BookRepository bookRepository;
    @Mock private BookAdditionalFileRepository bookAdditionalFileRepository;
    @Mock private BookCreatorService bookCreatorService;
    @Mock private BookMapper bookMapper;
    @Mock private FileService fileService;
    @Mock private MetadataMatchService metadataMatchService;
    @Mock private SidecarMetadataWriter sidecarMetadataWriter;
    @Mock private DjvuMetadataExtractor djvuMetadataExtractor;
    @Mock private DjvuPageRenderer djvuPageRenderer;

    @TempDir
    Path tempDir;

    private DjvuProcessor processor;
    private BookEntity book;
    private File expectedFile;

    @BeforeEach
    void setUp() {
        processor = new DjvuProcessor(
                bookRepository, bookAdditionalFileRepository, bookCreatorService, bookMapper,
                fileService, metadataMatchService, sidecarMetadataWriter,
                djvuMetadataExtractor, djvuPageRenderer
        );

        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());

        book = BookEntity.builder()
                .id(BOOK_ID)
                .libraryPath(libraryPath)
                .metadata(new BookMetadataEntity())
                .bookFiles(new HashSet<>())
                .build();
        BookFileEntity bookFile = BookFileEntity.builder()
                .id(1L)
                .book(book)
                .fileName("book.djvu")
                .fileSubPath("books")
                .isBookFormat(true)
                .bookType(BookFileType.DJVU)
                .build();
        book.getBookFiles().add(bookFile);

        expectedFile = tempDir.resolve("books").resolve("book.djvu").toFile();
    }

    @Test
    void supportsDjvu() {
        assertThat(processor.getSupportedTypes()).isEqualTo(List.of(BookFileType.DJVU));
    }

    @Test
    void generateCover_savesRenderedFirstPage() throws IOException {
        BufferedImage page = new BufferedImage(10, 15, BufferedImage.TYPE_INT_RGB);
        when(djvuPageRenderer.renderFirstPage(expectedFile)).thenReturn(page);
        when(fileService.saveCoverImages(page, BOOK_ID)).thenReturn(true);

        assertThat(processor.generateCover(book)).isTrue();
        verify(fileService).saveCoverImages(page, BOOK_ID);
    }

    @Test
    void generateCover_returnsFalseWhenPageCannotBeRendered() throws IOException {
        when(djvuPageRenderer.renderFirstPage(expectedFile)).thenReturn(null);

        assertThat(processor.generateCover(book)).isFalse();
        verify(fileService, never()).saveCoverImages(any(), anyLong());
    }

    @Test
    void generateCover_returnsFalseWhenSavingFails() throws IOException {
        BufferedImage page = new BufferedImage(10, 15, BufferedImage.TYPE_INT_RGB);
        when(djvuPageRenderer.renderFirstPage(expectedFile)).thenReturn(page);
        when(fileService.saveCoverImages(page, BOOK_ID)).thenThrow(new IOException("disk full"));

        assertThat(processor.generateCover(book)).isFalse();
    }

    @Test
    void processNewFile_setsCoverFromRenderedPage() throws IOException {
        LibraryFile libraryFile = libraryFile(LibraryOrganizationMode.AUTO_DETECT);
        when(bookCreatorService.createShellBook(libraryFile, BookFileType.DJVU)).thenReturn(book);
        BufferedImage page = new BufferedImage(10, 15, BufferedImage.TYPE_INT_RGB);
        when(djvuPageRenderer.renderFirstPage(expectedFile)).thenReturn(page);
        when(fileService.saveCoverImages(page, BOOK_ID)).thenReturn(true);

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getBookCoverHash()).isNotNull();
        assertThat(result.getMetadata().getCoverUpdatedOn()).isNotNull();
    }

    @Test
    void processNewFile_withoutRendererOrFolderImage_hasNoCover() throws IOException {
        LibraryFile libraryFile = libraryFile(LibraryOrganizationMode.AUTO_DETECT);
        when(bookCreatorService.createShellBook(libraryFile, BookFileType.DJVU)).thenReturn(book);
        when(djvuPageRenderer.renderFirstPage(expectedFile)).thenReturn(null);

        BookEntity result = processor.processNewFile(libraryFile);

        assertThat(result.getBookCoverHash()).isNull();
        verify(fileService, never()).saveCoverImages(any(), eq(BOOK_ID));
    }

    private LibraryFile libraryFile(LibraryOrganizationMode mode) {
        LibraryPathEntity libraryPath = new LibraryPathEntity();
        libraryPath.setPath(tempDir.toString());
        LibraryEntity library = new LibraryEntity();
        library.setOrganizationMode(mode);
        return LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(libraryPath)
                .fileSubPath("books")
                .fileName("book.djvu")
                .build();
    }
}
