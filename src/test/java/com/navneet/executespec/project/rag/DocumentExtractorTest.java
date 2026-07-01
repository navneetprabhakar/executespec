package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExtractorTest {

  private final DocumentExtractor extractor = new DocumentExtractor();

  @Test
  void extractsPlainTextAndMarkdownAfterParserPruning(@TempDir Path tempDir) throws Exception {
    Path text = tempDir.resolve("notes.md");
    Files.writeString(text, "# Launch notes\n\nRemote sandbox plan gate stays visible.");

    String extracted = extractor.extract(text, "text/markdown");

    assertThat(extracted).contains("Launch notes").contains("Remote sandbox plan gate");
  }

  @Test
  void extractsDocxAfterParserPruning(@TempDir Path tempDir) throws Exception {
    Path docx = tempDir.resolve("brief.docx");
    try (XWPFDocument doc = new XWPFDocument();
        OutputStream out = Files.newOutputStream(docx)) {
      doc.createParagraph().createRun().setText("ExecuteSpec project brief from DOCX");
      doc.write(out);
    }

    String extracted =
        extractor.extract(
            docx, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    assertThat(extracted).contains("ExecuteSpec project brief from DOCX");
  }
}
