package com.navneet.executespec.project.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Extracts plain text from common document formats via Apache Tika. */
@Component
@Slf4j
public class DocumentExtractor {

    /** Extract normalised UTF-8 text. Returns empty on failure. */
    public String extract(Path file, String mimeType) {
        try (InputStream in = Files.newInputStream(file)) {
            // -1 = unlimited body length
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            if (mimeType != null && !mimeType.isBlank()) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }
            new AutoDetectParser().parse(in, handler, metadata);
            String raw = handler.toString();
            return normalise(raw);
        } catch (Exception e) {
            log.warn("Tika extraction failed for {}: {}", file, e.getMessage());
            return "";
        }
    }

    private static String normalise(String raw) {
        if (raw == null) return "";
        // Drop NBSP, collapse Windows line endings, collapse runs of blank lines.
        return raw.replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
