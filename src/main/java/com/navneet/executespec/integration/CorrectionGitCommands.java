package com.navneet.executespec.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounded Git subprocess execution for {@link CorrectionWorkspaceTransaction}.
 *
 * <p>Every remaining mutation/recovery command runs with a hard timeout, merged bounded output
 * capture, and no shell. Workspace-generation identity no longer uses this subprocess adapter; it
 * is produced by {@link NonExecutingGitTreeWriter} so repository filters/helpers cannot execute
 * while evidence is calculated.
 */
final class CorrectionGitCommands {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_OUTPUT_BYTES = 16_384;

  String git(Path repository, String... args) {
    List<String> command = new ArrayList<>(args.length + 1);
    command.add("git");
    command.addAll(List.of(args));
    CommandResult result = run(repository, Map.of(), command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw commandFailure(repository, result, String.join(" ", command));
    }
    return result.output();
  }

  BinaryCommandResult gitBytes(Path repository, int captureLimit, String... args) {
    List<String> command = new ArrayList<>(args.length + 1);
    command.add("git");
    command.addAll(List.of(args));
    BinaryCommandResult result = runBytes(repository, captureLimit, command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          String.join(" ", command)
              + " failed in "
              + repository
              + " (exit="
              + result.exitCode()
              + ")");
    }
    return result;
  }

  private IllegalStateException commandFailure(
      Path repository, CommandResult result, String command) {
    return new IllegalStateException(
        command
            + " failed in "
            + repository
            + " (exit="
            + result.exitCode()
            + "): "
            + result.output());
  }

  private CommandResult run(Path repository, Map<String, String> environment, String... command) {
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
      builder.environment().putAll(environment);
      Process process = builder.start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var input = process.getInputStream()) {
                      input.transferTo(output);
                    } catch (IOException ignored) {
                      // Process exit/timeout remains the authoritative failure signal.
                    }
                  });
      boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        reader.join(Duration.ofSeconds(5));
        throw new IllegalStateException(
            String.join(" ", command) + " exceeded the correction checkpoint timeout");
      }
      reader.join(Duration.ofSeconds(5));
      byte[] bytes = output.toByteArray();
      int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
      return new CommandResult(
          process.exitValue(), new String(bytes, 0, length, StandardCharsets.UTF_8).trim());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Correction checkpoint command was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Correction checkpoint command could not start", e);
    }
  }

  private BinaryCommandResult runBytes(Path repository, int captureLimit, String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(repository.toFile())
              .redirectErrorStream(true)
              .start();
      BoundedCountingOutputStream output = new BoundedCountingOutputStream(captureLimit);
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var input = process.getInputStream()) {
                      input.transferTo(output);
                    } catch (IOException ignored) {
                      // Process exit/timeout remains the authoritative failure signal.
                    }
                  });
      boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        reader.join(Duration.ofSeconds(5));
        throw new IllegalStateException(
            String.join(" ", command) + " exceeded the correction checkpoint timeout");
      }
      reader.join(Duration.ofSeconds(5));
      return new BinaryCommandResult(process.exitValue(), output.captured(), output.count());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Correction checkpoint command was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Correction checkpoint command could not start", e);
    }
  }

  record CommandResult(int exitCode, String output) {}

  record BinaryCommandResult(int exitCode, byte[] output, long fullOutputBytes) {}

  private static final class BoundedCountingOutputStream extends OutputStream {
    private final ByteArrayOutputStream captured;
    private final int limit;
    private long count;

    private BoundedCountingOutputStream(int limit) {
      this.limit = Math.max(0, limit);
      this.captured = new ByteArrayOutputStream(this.limit);
    }

    @Override
    public void write(int value) {
      count++;
      if (captured.size() < limit) captured.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      count += length;
      int remaining = limit - captured.size();
      if (remaining > 0) captured.write(bytes, offset, Math.min(remaining, length));
    }

    private byte[] captured() {
      return captured.toByteArray();
    }

    private long count() {
      return count;
    }
  }
}
