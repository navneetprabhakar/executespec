package com.navneet.executespec.project.rag;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts plain text from common document formats via Apache Tika.
 *
 * <p>Parsing untrusted bytes is time-unbounded by construction: {@link BodyContentHandler} bounds
 * emitted characters, not parse time or CPU. Every extraction therefore runs on a bounded parser
 * pool under a hard wall-clock budget, so a pathological document cannot pin the calling (HTTP
 * request) thread. The parse itself observes the same deadline through a deadline-aware stream, so
 * a timed-out task releases its pool thread at the next read instead of leaking it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentExtractor {

  private final ProjectDocumentProperties properties;

  private volatile ExecutorService parserPool;

  public record ExtractionResult(
      String text, Integer pageCount, int characterCount, int tokenCount) {}

  /**
   * Extract normalised UTF-8 text under the configured per-file ceiling.
   *
   * <p>Failures are typed. This intentionally does NOT return {@code ""} on rejection: an
   * encrypted, oversized or corrupt document must not be indistinguishable from a legitimately
   * empty one, or callers build confidently empty downstream evidence from it.
   *
   * @throws DocumentAdmissionException with a stable code for every rejection
   */
  public String extract(Path file, String mimeType) {
    return extractBounded(file, mimeType, properties.getMaxExtractedCharsPerFile()).text();
  }

  /**
   * Extract text under a hard SAX write limit and a hard wall-clock budget. Failures are typed so
   * the project-document admission path never persists parser details or raw paths.
   */
  public ExtractionResult extractBounded(Path file, String mimeType, int maxCharacters) {
    long budgetMillis = Math.max(1_000L, properties.getExtractionTimeoutMillis());
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    Future<ExtractionResult> pending =
        parserPool().submit(() -> parse(file, mimeType, maxCharacters, deadlineNanos));
    try {
      return pending.get(budgetMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timedOut) {
      pending.cancel(true);
      throw timeout();
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw timeout();
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof DocumentAdmissionException typed) throw typed;
      if (cause instanceof Error error) throw error;
      log.warn(
          "Document extraction rejected with parser category {}",
          cause == null ? "UNKNOWN" : cause.getClass().getSimpleName());
      throw new DocumentAdmissionException(
          "CORRUPT_DOCUMENT", "The document is corrupt, encrypted, or unsupported.");
    }
  }

  private ExtractionResult parse(
      Path file, String mimeType, int maxCharacters, long deadlineNanos) {
    BodyContentHandler handler = new BodyContentHandler(maxCharacters + 1);
    try (InputStream in = new DeadlineInputStream(Files.newInputStream(file), deadlineNanos)) {
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
      if (isDeadlineExceeded(e)) throw timeout();
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

  private static boolean isDeadlineExceeded(Throwable error) {
    for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
      if (cursor instanceof ExtractionDeadlineException) return true;
      if (cursor.getCause() == cursor) break;
    }
    return false;
  }

  private static DocumentAdmissionException timeout() {
    return new DocumentAdmissionException(
        "EXTRACTION_TIMEOUT", "The document took too long to process and was rejected.");
  }

  private ExecutorService parserPool() {
    ExecutorService current = parserPool;
    if (current != null) return current;
    synchronized (this) {
      if (parserPool == null) {
        int threads = Math.max(1, properties.getMaxConcurrentExtractions());
        parserPool = Executors.newFixedThreadPool(threads, parserThreadFactory());
      }
      return parserPool;
    }
  }

  private static ThreadFactory parserThreadFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "doc-extract-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  @PreDestroy
  void shutdownParserPool() {
    ExecutorService current = parserPool;
    if (current != null) current.shutdownNow();
  }

  /** Aborts a runaway parse from inside the parser thread once the shared budget elapses. */
  private static final class DeadlineInputStream extends FilterInputStream {

    private final long deadlineNanos;

    private DeadlineInputStream(InputStream delegate, long deadlineNanos) {
      super(delegate);
      this.deadlineNanos = deadlineNanos;
    }

    @Override
    public int read() throws IOException {
      checkDeadline();
      return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      checkDeadline();
      return super.read(buffer, offset, length);
    }

    @Override
    public long skip(long count) throws IOException {
      checkDeadline();
      return super.skip(count);
    }

    private void checkDeadline() throws IOException {
      if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadlineNanos >= 0) {
        throw new ExtractionDeadlineException();
      }
    }
  }

  private static final class ExtractionDeadlineException extends InterruptedIOException {
    private ExtractionDeadlineException() {
      super("document extraction budget elapsed");
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
