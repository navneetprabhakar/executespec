package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExtractorTest {

  private final ProjectDocumentProperties properties = new ProjectDocumentProperties();
  private final DocumentExtractor extractor = new DocumentExtractor(properties);

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

  /** RR-1533: a typed rejection must never be laundered into a legitimately empty document. */
  @Test
  void compatibilityOverloadSurfacesTypedRejectionsInsteadOfEmptyText(@TempDir Path tempDir)
      throws Exception {
    Path bogus = tempDir.resolve("broken.pdf");
    Files.write(bogus, "%PDF-1.4 not really a pdf".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> extractor.extract(bogus, "application/pdf"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(error -> ((DocumentAdmissionException) error).code())
        .isEqualTo("CORRUPT_DOCUMENT");
  }

  /** RR-1533: the ceiling comes from configuration, not a hardcoded constant. */
  @Test
  void compatibilityOverloadHonoursTheConfiguredExtractionCeiling(@TempDir Path tempDir)
      throws Exception {
    Path text = tempDir.resolve("long.txt");
    Files.writeString(text, "x".repeat(4_000));
    properties.setMaxExtractedCharsPerFile(100);

    assertThatThrownBy(() -> extractor.extract(text, "text/plain"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(error -> ((DocumentAdmissionException) error).code())
        .isEqualTo("EXTRACTED_TEXT_LIMIT");
  }

  /**
   * RR-1530: a parse that outlives its budget is rejected with a typed code and releases the
   * calling thread instead of pinning it. The stream is deliberately slow rather than large, so the
   * character ceiling cannot be what stops it.
   */
  @Test
  void extractionExceedingTheWallClockBudgetIsRejectedAndReleasesTheCallingThread(
      @TempDir Path tempDir) throws Exception {
    Path fifo = tempDir.resolve("never-completes.txt");
    assumeThat(makeFifo(fifo)).as("POSIX named pipe available").isTrue();
    properties.setExtractionTimeoutMillis(1_000);

    long started = System.nanoTime();
    assertThatThrownBy(() -> extractor.extract(fifo, "text/plain"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(error -> ((DocumentAdmissionException) error).code())
        .isEqualTo("EXTRACTION_TIMEOUT");
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
    // The point of the fix: the caller is released on the budget, not held until the parse ends.
    assertThat(elapsedMillis).isLessThan(15_000L);
  }

  /** A named pipe with no writer never completes a read — a stand-in for a pathological parse. */
  private static boolean makeFifo(Path path) {
    try {
      Process process = new ProcessBuilder("mkfifo", path.toString()).start();
      return process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
          && process.exitValue() == 0
          && Files.exists(path);
    } catch (Exception unsupported) {
      return false;
    }
  }
}
