package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceGenerationArchiveServiceTest {

  @TempDir Path temp;

  @Test
  void restoresExactMultiRepositoryGenerationAndOriginalHeads() throws Exception {
    Path source = temp.resolve("source");
    Files.createDirectories(source);
    init(source);
    Files.writeString(source.resolve("root.txt"), "root-before\n");
    commit(source, "root");

    Path nested = source.resolve("repos/api");
    Files.createDirectories(nested);
    init(nested);
    Files.writeString(nested.resolve("api.txt"), "api-before\n");
    commit(nested, "api");

    Files.writeString(source.resolve("root.txt"), "root-candidate\n");
    Path executable = nested.resolve("run.sh");
    Files.writeString(executable, "#!/bin/sh\necho exact\n");
    Files.setPosixFilePermissions(
        executable,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint candidate = transactions.begin(source);
    WorkspaceGenerationArchiveService archives =
        new WorkspaceGenerationArchiveService(new ObjectMapper(), transactions);
    Path archive = archives.create(candidate);
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(archive));

    Path restoredRoot = temp.resolve("restored");
    CorrectionWorkspaceTransaction.Checkpoint restored =
        archives.restore(archive, digest, restoredRoot, candidate.generation());

    assertThat(restored.generation()).isEqualTo(candidate.generation());
    assertThat(Files.readString(restoredRoot.resolve("root.txt"))).isEqualTo("root-candidate\n");
    assertThat(Files.readString(restoredRoot.resolve("repos/api/run.sh")))
        .isEqualTo("#!/bin/sh\necho exact\n");
    assertThat(Files.isExecutable(restoredRoot.resolve("repos/api/run.sh"))).isTrue();
    assertThat(git(restoredRoot, "rev-parse", "HEAD")).isEqualTo(git(source, "rev-parse", "HEAD"));
    assertThat(git(restoredRoot.resolve("repos/api"), "rev-parse", "HEAD"))
        .isEqualTo(git(nested, "rev-parse", "HEAD"));
  }

  private static void init(Path repository) throws Exception {
    git(repository, "init", "-b", "main");
    git(repository, "config", "user.email", "test@executespec.local");
    git(repository, "config", "user.name", "ExecuteSpec Test");
  }

  private static void commit(Path repository, String message) throws Exception {
    git(repository, "add", "-A");
    git(repository, "commit", "-m", message);
  }

  private static String git(Path repository, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    int exit = process.waitFor();
    if (exit != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output.trim();
  }
}
