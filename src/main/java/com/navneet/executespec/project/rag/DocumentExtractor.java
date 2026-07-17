package com.navneet.executespec.project.rag;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Extracts plain text from common document formats via Apache Tika. */
@Component
@Slf4j
public class DocumentExtractor {

  public record ExtractionResult(
      String text, Integer pageCount, int characterCount, int tokenCount) {}

  /** Extract normalised UTF-8 text. Returns empty on failure. */
  public String extract(Path file, String mimeType) {
    try {
      return extractBounded(file, mimeType, 2_000_000).text();
    } catch (DocumentAdmissionException e) {
      return "";
    }
  }

  /**
   * Extract text under a hard SAX write limit. Unlike the compatibility method above, failures are
   * typed so the project-document admission path never persists parser details or raw paths.
   */
  public ExtractionResult extractBounded(Path file, String mimeType, int maxCharacters) {
    BodyContentHandler handler = new BodyContentHandler(maxCharacters + 1);
    try (InputStream in = Files.newInputStream(file)) {
      Metadata metadata = new Metadata();
      if (mimeType != null && !mimeType.isBlank()) {
        metadata.set(Metadata.CONTENT_TYPE, mimeType);
        metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, mimeType);
      }
      new AutoDetectParser().parse(in, handler, metadata);
      if (Boolean.parseBoolean(metadata.get(TikaCoreProperties.IS_ENCRYPTED))) {
        throw new DocumentAdmissionException(
            "ENCRYPTED_DOCUMENT", "Encrypted documents are not supported.");
      }
      String text = normalise(handler.toString());
      if (text.length() > maxCharacters) {
        throw new DocumentAdmissionException(
            "EXTRACTED_TEXT_LIMIT", "The extracted document text exceeds the safe limit.");
      }
      Integer pageCount = metadata.getInt(Office.PAGE_COUNT);
      if (pageCount == null) pageCount = metadata.getInt(Office.SLIDE_COUNT);
      return new ExtractionResult(text, pageCount, text.length(), estimateTokens(text));
    } catch (DocumentAdmissionException e) {
      throw e;
    } catch (EncryptedDocumentException e) {
      throw new DocumentAdmissionException(
          "ENCRYPTED_DOCUMENT", "Encrypted documents are not supported.");
    } catch (Exception e) {
      if (handler.toString().length() >= maxCharacters) {
        throw new DocumentAdmissionException(
            "EXTRACTED_TEXT_LIMIT", "The extracted document text exceeds the safe limit.");
      }
      log.warn(
          "Document extraction rejected with parser category {}", e.getClass().getSimpleName());
      throw new DocumentAdmissionException(
          "CORRUPT_DOCUMENT", "The document is corrupt, encrypted, or unsupported.");
    }
  }

  private static String normalise(String raw) {
    if (raw == null) return "";
    // Drop NBSP, collapse Windows line endings, collapse runs of blank lines.
    return raw.replace('\u00A0', ' ').replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
  }

  private static int estimateTokens(String text) {
    if (text == null || text.isBlank()) return 0;
    return Math.max(1, (text.length() + 3) / 4);
  }
}
