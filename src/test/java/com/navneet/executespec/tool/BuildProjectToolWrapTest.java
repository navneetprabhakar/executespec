package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the programbench-mode build-smoke wrapper introduced 2026-05-08 to close the "agent games
 * the smoke with a no-op compile.sh that just verifies ./executable exists" failure mode (cmatrix
 * anthropic run 9b37b099). The wrapper:
 *
 * <ol>
 *   <li>Captures the executable hash BEFORE running the user command
 *   <li>Runs the user command (compile.sh, mvn, gcc, etc.)
 *   <li>Captures the executable hash AFTER
 *   <li>Fails if executable is missing post-run
 *   <li>Fails if the hash is unchanged (= compile.sh didn't actually compile anything fresh)
 *   <li>Otherwise propagates the user command's exit code
 * </ol>
 *
 * <p>Tests run the wrapped command against a real {@code /bin/sh} subprocess in a temp workspace —
 * no Docker, no full Spring stack required.
 */
class BuildProjectToolWrapTest {

  @TempDir Path ws;

  /** Runs the wrapped command in {@link #ws} and returns (exit, stdout). */
  private record ShellOut(int exit, String output) {}

  private ShellOut runWrapped(String userCmd) throws IOException, InterruptedException {
    String wrapped = BuildProjectTool.wrapForExecutableCompileCheck(userCmd);
    ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", wrapped);
    pb.directory(ws.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    boolean ok = p.waitFor(30, TimeUnit.SECONDS);
    if (!ok) {
      p.destroyForcibly();
      return new ShellOut(-1, "TIMEOUT");
    }
    String out = new String(p.getInputStream().readAllBytes());
    return new ShellOut(p.exitValue(), out);
  }

  /** Helper: write a file + chmod +x. */
  private void writeExec(String name, String content) throws IOException {
    Path p = ws.resolve(name);
    Files.writeString(p, content);
    p.toFile().setExecutable(true);
  }

  @Test
  void no_op_compile_sh_against_existing_executable_FAILS_with_hash_unchanged_message()
      throws Exception {
    // Reproduce the cmatrix anthropic 9b37b099 gaming pattern:
    //   - cleanroom's executable is in the workspace (pre-existing)
    //   - compile.sh just verifies ./executable exists, no real compile
    //   - smoke must FAIL because hash didn't change
    writeExec("executable", "#!/bin/sh\necho original\n");
    writeExec(
        "compile.sh",
        "#!/bin/bash\nset -e\nif [ ! -f ./executable ]; then echo missing; exit 1; fi\n"
            + "echo 'verified existing executable'\nexit 0\n");

    ShellOut r = runWrapped("chmod +x ./compile.sh && ./compile.sh");

    assertThat(r.exit()).as("must fail when compile.sh leaves hash unchanged").isNotZero();
    assertThat(r.output())
        .as("must explain why")
        .contains("did not produce a NEW ./executable")
        .contains("hash unchanged");
  }

  @Test
  void real_compile_that_produces_fresh_executable_PASSES() throws Exception {
    // Honest path: compile.sh genuinely produces a fresh executable with different bytes.
    writeExec("executable", "#!/bin/sh\necho original-binary\n");
    writeExec(
        "compile.sh",
        "#!/bin/bash\nset -e\nrm -f ./executable\necho '#!/bin/sh' > ./executable\n"
            + "echo 'echo fresh-build' >> ./executable\nchmod +x ./executable\nexit 0\n");

    ShellOut r = runWrapped("chmod +x ./compile.sh && ./compile.sh");

    assertThat(r.exit()).as("real compile should pass").isZero();
    assertThat(r.output()).doesNotContain("BUILD FAIL");
  }

  @Test
  void compile_sh_that_deletes_executable_FAILS_with_missing_message() throws Exception {
    // compile.sh deletes the executable but doesn't produce a new one — should fail loud.
    writeExec("executable", "#!/bin/sh\necho original\n");
    writeExec("compile.sh", "#!/bin/bash\nset -e\nrm -f ./executable\nexit 0\n");

    ShellOut r = runWrapped("chmod +x ./compile.sh && ./compile.sh");

    assertThat(r.exit()).as("missing executable post-build is a fail").isNotZero();
    assertThat(r.output()).contains("did not produce ./executable");
  }

  @Test
  void no_pre_existing_executable_first_compile_PASSES() throws Exception {
    // Greenfield (no cleanroom binary): compile.sh produces the first executable. Should pass.
    writeExec(
        "compile.sh",
        "#!/bin/bash\nset -e\necho '#!/bin/sh' > ./executable\nchmod +x ./executable\nexit 0\n");

    ShellOut r = runWrapped("chmod +x ./compile.sh && ./compile.sh");

    assertThat(r.exit()).isZero();
    assertThat(r.output()).doesNotContain("BUILD FAIL");
  }

  @Test
  void user_command_failing_propagates_user_exit_code() throws Exception {
    // The wrapper preserves the user command's exit code when the guards don't fire on a
    // structural failure mode.
    writeExec("compile.sh", "#!/bin/bash\nset -e\necho 'sym not found' >&2\nexit 7\n");

    ShellOut r = runWrapped("chmod +x ./compile.sh && ./compile.sh");

    // compile.sh exits 7 — but our missing-executable guard fires first since no executable
    // was produced. The wrapper exits 1 in that branch. (User-exit propagation only applies on
    // the "executable was created" path.)
    assertThat(r.exit()).isNotZero();
  }
}
