package org.booklore.service.metadata.extractor.djvu;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal s-expression reader for DjVu annotation text (the content of an
 * {@code ANTa} chunk or a BZZ-decoded {@code ANTz} chunk). Extracts only the
 * {@code (metadata (key "value") ...)} form written by djvused and similar
 * tools; every other annotation form (background, zoom, hyperlinks, ...) is
 * ignored. Keys are lowercased so that djvused-style lowercase keys and
 * pdf2djvu-style capitalized keys ({@code Title}, {@code Author}) unify.
 */
final class DjvuAnnotationParser {

    private DjvuAnnotationParser() {
    }

    /** Parses annotation text and returns the metadata key/value pairs, or an empty map. */
    static Map<String, String> parseMetadata(String annotationText) {
        Map<String, String> result = new LinkedHashMap<>();
        if (annotationText == null || annotationText.isEmpty()) {
            return result;
        }
        try {
            Parser parser = new Parser(annotationText);
            parser.parseTopLevel(result);
        } catch (Exception e) {
            // Malformed annotations are ignored; structural metadata is still useful
            return result;
        }
        return result;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        void parseTopLevel(Map<String, String> result) {
            while (skipToNextList()) {
                String symbol = readSymbol();
                if ("metadata".equalsIgnoreCase(symbol)) {
                    parseMetadataList(result);
                } else {
                    skipRestOfList(1);
                }
            }
        }

        /** Advances to just past the next '(' at the current nesting level; false at end of input. */
        private boolean skipToNextList() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '(') {
                    pos++;
                    return true;
                }
                if (c == '"') {
                    readQuotedString();
                    continue;
                }
                pos++;
            }
            return false;
        }

        private void parseMetadataList(Map<String, String> result) {
            while (true) {
                skipWhitespace();
                if (pos >= text.length()) {
                    return;
                }
                char c = text.charAt(pos);
                if (c == ')') {
                    pos++;
                    return;
                }
                if (c == '(') {
                    pos++;
                    String key = readSymbol();
                    skipWhitespace();
                    String value = null;
                    if (pos < text.length() && text.charAt(pos) == '"') {
                        value = readQuotedString();
                    } else if (pos < text.length() && text.charAt(pos) != ')') {
                        value = readSymbol();
                    }
                    skipRestOfList(1);
                    if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                        result.put(key.toLowerCase(Locale.ROOT), value.trim());
                    }
                } else {
                    pos++;
                }
            }
        }

        /** Consumes input until {@code depth} unmatched ')' have been closed. */
        private void skipRestOfList(int depth) {
            while (pos < text.length() && depth > 0) {
                char c = text.charAt(pos);
                if (c == '"') {
                    readQuotedString();
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                pos++;
            }
        }

        private String readSymbol() {
            skipWhitespace();
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == '"') {
                    break;
                }
                pos++;
            }
            return pos > start ? text.substring(start, pos) : null;
        }

        /** Reads a quoted string starting at the opening '"', handling djvused escapes. */
        private String readQuotedString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\' && pos < text.length()) {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '"', '\\' -> sb.append(e);
                        default -> {
                            if (e >= '0' && e <= '7') {
                                int value = e - '0';
                                for (int i = 0; i < 2 && pos < text.length(); i++) {
                                    char d = text.charAt(pos);
                                    if (d < '0' || d > '7') {
                                        break;
                                    }
                                    value = value * 8 + (d - '0');
                                    pos++;
                                }
                                sb.append((char) value);
                            } else {
                                sb.append(e);
                            }
                        }
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }
    }
}
