package org.booklore.service.metadata.extractor;

import org.booklore.model.dto.BookMetadata;
import org.booklore.service.metadata.extractor.djvu.DjvuDocument;
import org.booklore.service.metadata.extractor.djvu.DjvuPageRenderer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts metadata from DjVu files ({@code .djvu}/{@code .djv}): page count and
 * the document metadata annotations written by tools like djvused and pdf2djvu.
 * DjVu files carry no separate cover image; like PDF, the cover is the rendered first
 * page, produced by {@link DjvuPageRenderer} when DjVuLibre's {@code ddjvu} is installed.
 */
@Slf4j
@Component
public class DjvuMetadataExtractor implements FileMetadataExtractor {

    private final DjvuPageRenderer pageRenderer;

    public DjvuMetadataExtractor(DjvuPageRenderer pageRenderer) {
        this.pageRenderer = pageRenderer;
    }

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\d{4}");
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\d{9}[\\dXx]");
    private static final Pattern ISBN_CLEANER_PATTERN = Pattern.compile("[^0-9Xx]");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern AUTHOR_SEPARATOR_PATTERN = Pattern.compile("[,;&]");
    private static final Pattern KEYWORD_SEPARATOR_PATTERN = Pattern.compile("[,;]");

    @Override
    public BookMetadata extractMetadata(File file) {
        try {
            DjvuDocument document = DjvuDocument.parse(file);
            Map<String, String> annotations = document.getMetadata();

            BookMetadata.BookMetadataBuilder builder = BookMetadata.builder();
            if (document.getPageCount() > 0) {
                builder.pageCount(document.getPageCount());
            }

            String title = firstPresent(annotations, "title", "booktitle");
            if (title != null) {
                builder.title(title);
            }

            List<String> authors = new ArrayList<>();
            String author = firstPresent(annotations, "author", "authors", "creator");
            if (author != null) {
                for (String part : AUTHOR_SEPARATOR_PATTERN.split(author)) {
                    String trimmed = part.trim();
                    if (StringUtils.isNotBlank(trimmed)) {
                        authors.add(trimmed);
                    }
                }
            }
            builder.authors(authors);

            String description = firstPresent(annotations, "description", "annote", "abstract");
            if (description != null) {
                builder.description(description);
            }

            String publisher = firstPresent(annotations, "publisher");
            if (publisher != null) {
                builder.publisher(publisher);
            }

            LocalDate publishedDate = parseDate(firstPresent(annotations, "year", "date", "creationdate"));
            if (publishedDate != null) {
                builder.publishedDate(publishedDate);
            }

            String isbn = firstPresent(annotations, "isbn");
            if (isbn != null) {
                applyIsbn(isbn, builder);
            }

            Set<String> categories = new HashSet<>();
            String keywords = firstPresent(annotations, "keywords");
            if (keywords != null) {
                for (String keyword : KEYWORD_SEPARATOR_PATTERN.split(keywords)) {
                    String trimmed = keyword.trim();
                    if (StringUtils.isNotBlank(trimmed)) {
                        categories.add(trimmed);
                    }
                }
            }
            String subject = firstPresent(annotations, "subject");
            if (StringUtils.isNotBlank(subject)) {
                categories.add(subject.trim());
            }
            builder.categories(categories);

            String language = firstPresent(annotations, "language", "lang");
            if (language != null) {
                builder.language(language);
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("Failed to extract metadata from DjVu: {}", file.getName(), e);
            return null;
        }
    }

    @Override
    public byte[] extractCover(File file) {
        return pageRenderer.renderFirstPageAsJpeg(file);
    }

    private String firstPresent(Map<String, String> annotations, String... keys) {
        for (String key : keys) {
            String value = annotations.get(key);
            if (StringUtils.isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void applyIsbn(String isbnText, BookMetadata.BookMetadataBuilder builder) {
        String cleaned = ISBN_CLEANER_PATTERN.matcher(isbnText).replaceAll("");
        if (cleaned.length() == 13) {
            builder.isbn13(cleaned);
        } else if (cleaned.length() == 10) {
            builder.isbn10(cleaned);
        } else {
            Matcher matcher = ISBN_PATTERN.matcher(cleaned);
            if (matcher.find()) {
                builder.isbn10(matcher.group());
            }
        }
    }

    private LocalDate parseDate(String dateString) {
        if (StringUtils.isBlank(dateString)) {
            return null;
        }
        try {
            Matcher isoMatcher = ISO_DATE_PATTERN.matcher(dateString);
            if (isoMatcher.find()) {
                return LocalDate.parse(isoMatcher.group());
            }
            Matcher yearMatcher = YEAR_PATTERN.matcher(dateString);
            if (yearMatcher.find()) {
                return LocalDate.of(Integer.parseInt(yearMatcher.group()), 1, 1);
            }
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", dateString, e);
        }
        return null;
    }
}
