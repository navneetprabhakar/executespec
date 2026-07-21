package com.navneet.executespec.project.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

/** Content-only type detection and bounded OOXML container validation. */
@Component
public class DocumentTypeInspector {

  public record InspectedType(String mimeType) {}

  public InspectedType inspect(byte[] bytes, ProjectDocumentProperties limits) {
    if (bytes == null || bytes.length == 0) {
      throw new DocumentAdmissionException("EMPTY_FILE", "The uploaded file is empty.");
    }
    if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
      if (indexOf(bytes, new byte[] {'P', 'K', 3, 4}, 5) >= 0
          || indexOf(bytes, new byte[] {'M', 'Z'}, 5) >= 0) {
        throw new DocumentAdmissionException(
            "POLYGLOT_FILE", "Files containing conflicting formats are not supported.");
      }
      return new InspectedType("application/pdf");
    }
    if (startsWith(bytes, new byte[] {'P', 'K', 3, 4})) {
      return inspectOoxml(bytes, limits);
    }
    if (startsWith(bytes, new byte[] {'M', 'Z'})
        || startsWith(
            bytes,
            new byte[] {
              (byte) 0xD0,
              (byte) 0xCF,
              0x11,
              (byte) 0xE0,
              (byte) 0xA1,
              (byte) 0xB1,
              0x1A,
              (byte) 0xE1
            })) {
      throw new DocumentAdmissionException(
          "UNSUPPORTED_FILE_TYPE", "This binary document format is not supported.");
    }
    validateUtf8Text(bytes);
    return new InspectedType("text/plain");
  }

  private InspectedType inspectOoxml(byte[] bytes, ProjectDocumentProperties limits) {
    validateZipEnd(bytes);
    Set<String> entries = new HashSet<>();
    long inflated = 0;
    int count = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        count++;
        if (count > limits.getMaxArchiveEntries()) {
          throw new DocumentAdmissionException(
              "ARCHIVE_ENTRY_LIMIT", "The document container has too many entries.");
        }
        String name = entry.getName().replace('\\', '/');
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.startsWith("/")
            || name.contains("../")
            || name.equals("..")
            || lower.contains("vbaproject.bin")
            || lower.contains("/embeddings/")
            || lower.contains("/externallinks/")) {
          throw new DocumentAdmissionException(
              "UNSAFE_DOCUMENT_CONTAINER", "The document container contains unsafe entries.");
        }
        if (!entries.add(name)) {
          throw new DocumentAdmissionException(
              "UNSAFE_DOCUMENT_CONTAINER", "Duplicate document-container entries are not allowed.");
        }
        int read;
        while ((read = zip.read(buffer)) != -1) {
          inflated += read;
          if (inflated > limits.getMaxArchiveInflatedBytes()
              || inflated > (long) bytes.length * limits.getMaxCompressionRatio()) {
            throw new DocumentAdmissionException(
                "DECOMPRESSION_LIMIT", "The document expands beyond the safe extraction limit.");
          }
        }
        zip.closeEntry();
      }
    } catch (DocumentAdmissionException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new DocumentAdmissionException(
          "CORRUPT_DOCUMENT", "The document container is corrupt or encrypted.");
    }
    if (!entries.contains("[Content_Types].xml") || !entries.contains("_rels/.rels")) {
      throw new DocumentAdmissionException(
          "ARCHIVE_NOT_ALLOWED", "Archive uploads are not supported.");
    }
    boolean word = entries.stream().anyMatch(n -> n.startsWith("word/"));
    boolean sheet = entries.stream().anyMatch(n -> n.startsWith("xl/"));
    boolean slides = entries.stream().anyMatch(n -> n.startsWith("ppt/"));
    if ((word ? 1 : 0) + (sheet ? 1 : 0) + (slides ? 1 : 0) != 1) {
      throw new DocumentAdmissionException(
          "UNSUPPORTED_FILE_TYPE", "Only DOCX, XLSX, and PPTX document containers are supported.");
    }
    if (word) {
      return new InspectedType(
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
    if (sheet) {
      return new InspectedType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
    return new InspectedType(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation");
  }

  private static void validateUtf8Text(byte[] bytes) {
    try {
      var decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
      int controls = 0;
      for (int i = 0; i < decoded.length(); i++) {
        char c = decoded.charAt(i);
        if (c == 0) {
          throw new DocumentAdmissionException(
              "UNSUPPORTED_FILE_TYPE", "Binary files are not supported.");
        }
        if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
          controls++;
        }
      }
      if (controls > Math.max(8, decoded.length() / 100)) {
        throw new DocumentAdmissionException(
            "UNSUPPORTED_FILE_TYPE", "Binary files are not supported.");
      }
    } catch (CharacterCodingException e) {
      throw new DocumentAdmissionException(
          "UNSUPPORTED_FILE_TYPE", "Only UTF-8 text and approved document formats are supported.");
    }
  }

  private static void validateZipEnd(byte[] bytes) {
    int min = Math.max(0, bytes.length - 65_557);
    for (int i = bytes.length - 22; i >= min; i--) {
      if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 5 && bytes[i + 3] == 6) {
        int commentLength = (bytes[i + 20] & 0xff) | ((bytes[i + 21] & 0xff) << 8);
        if (i + 22 + commentLength != bytes.length) {
          throw new DocumentAdmissionException(
              "POLYGLOT_FILE", "Trailing data after the document container is not allowed.");
        }
        return;
      }
    }
    throw new DocumentAdmissionException("CORRUPT_DOCUMENT", "The document container is corrupt.");
  }

  private static boolean startsWith(byte[] bytes, byte[] marker) {
    if (bytes.length < marker.length) return false;
    for (int i = 0; i < marker.length; i++) if (bytes[i] != marker[i]) return false;
    return true;
  }

  private static int indexOf(byte[] bytes, byte[] marker, int from) {
    outer:
    for (int i = Math.max(0, from); i <= bytes.length - marker.length; i++) {
      for (int j = 0; j < marker.length; j++) if (bytes[i + j] != marker[j]) continue outer;
      return i;
    }
    return -1;
  }
}
