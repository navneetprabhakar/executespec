package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentTypeInspectorSecurityTest {

  private final DocumentTypeInspector inspector = new DocumentTypeInspector();
  private ProjectDocumentProperties limits;

  @BeforeEach
  void setUp() {
    limits = new ProjectDocumentProperties();
  }

  @Test
  void detectsContentWithoutTrustingDeclaredMimeOrFilename() {
    assertThat(
            inspector
                .inspect("plain UTF-8 notes".getBytes(StandardCharsets.UTF_8), limits)
                .mimeType())
        .isEqualTo("text/plain");
    assertThat(
            inspector
                .inspect("%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII), limits)
                .mimeType())
        .isEqualTo("application/pdf");
  }

  @Test
  void rejectsBinarySpoofedAsDocument() {
    byte[] executable = {'M', 'Z', 0, 1, 2, 3};
    assertCode(executable, "UNSUPPORTED_FILE_TYPE");
  }

  @Test
  void rejectsPdfZipPolyglot() {
    byte[] pdf = "%PDF-1.7\nPK\003\004payload".getBytes(StandardCharsets.ISO_8859_1);
    assertCode(pdf, "POLYGLOT_FILE");
  }

  @Test
  void rejectsOrdinaryArchiveAndTraversalEntry() throws Exception {
    assertCode(zip(Map.of("payload.txt", "hello".getBytes())), "ARCHIVE_NOT_ALLOWED");
    Map<String, byte[]> traversal = validDocxEntries();
    traversal.put("word/../../escape", "x".getBytes());
    assertCode(zip(traversal), "UNSAFE_DOCUMENT_CONTAINER");
  }

  @Test
  void acceptsBoundedDocxButRejectsMacrosAndTrailingPolyglotData() throws Exception {
    byte[] docx = zip(validDocxEntries());
    assertThat(inspector.inspect(docx, limits).mimeType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    Map<String, byte[]> macro = validDocxEntries();
    macro.put("word/vbaProject.bin", new byte[] {1});
    assertCode(zip(macro), "UNSAFE_DOCUMENT_CONTAINER");

    byte[] trailing = java.util.Arrays.copyOf(docx, docx.length + 3);
    trailing[docx.length] = 'M';
    trailing[docx.length + 1] = 'Z';
    assertCode(trailing, "POLYGLOT_FILE");
  }

  @Test
  void rejectsEntryCountAndDecompressionBombs() throws Exception {
    limits.setMaxArchiveEntries(2);
    assertCode(zip(validDocxEntries()), "ARCHIVE_ENTRY_LIMIT");

    limits = new ProjectDocumentProperties();
    limits.setMaxArchiveInflatedBytes(1_000);
    Map<String, byte[]> bomb = validDocxEntries();
    bomb.put("word/document.xml", new byte[20_000]);
    assertCode(zip(bomb), "DECOMPRESSION_LIMIT");
  }

  @Test
  void rejectsInvalidUtf8AndNulHeavyBinary() {
    assertCode(new byte[] {(byte) 0xC3, 0x28}, "UNSUPPORTED_FILE_TYPE");
    assertCode(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0}, "UNSUPPORTED_FILE_TYPE");
  }

  private void assertCode(byte[] bytes, String code) {
    assertThatThrownBy(() -> inspector.inspect(bytes, limits))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(e -> ((DocumentAdmissionException) e).code())
        .isEqualTo(code);
  }

  private static Map<String, byte[]> validDocxEntries() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
    entries.put("_rels/.rels", "<Relationships/>".getBytes(StandardCharsets.UTF_8));
    entries.put("word/document.xml", "<document>hello</document>".getBytes(StandardCharsets.UTF_8));
    return entries;
  }

  private static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
