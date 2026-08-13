package com.navneet.executespec.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link WorkspaceTestRunner}'s most important contract: when there is no recognized test
 * runner, it returns empty so the scorer falls back to LLM coverage rather than fabricating a
 * count. The resolver short-circuits before the sandbox or containers are consulted, so this needs
 * no toolchain and no Spring context. The multi-framework parse path is pinned separately by {@link
 * TestOutputParserTest}. RL-2026-05-001.
 */
class WorkspaceTestRunnerTest {

  @Test
  void noRecognizedRunner_returnsEmpty(@TempDir Path emptyWorkspace) {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null, null);
    assertTrue(runner.runTests("run-1", emptyWorkspace).isEmpty());
    assertEquals(
        WorkspaceTestRunner.ExecutionStatus.UNSUPPORTED_FRAMEWORK,
        runner.executeTests(new ExecutionContext("run-1", "T1", emptyWorkspace)).status());
  }

  @Test
  void nullWorkspace_returnsEmpty() {
    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(new TestCommandResolver(), null, null, null, null);
    assertTrue(runner.runTests("run-1", null).isEmpty());
  }

  @Test
  void nonzeroUnparseableRunnerExitIsRecordedAsRed(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pyproject.toml"), "[project]\nname='broken'\n");
    Path python = workspace.resolve("venv/bin/python");
    Files.createDirectories(python.getParent());
    Files.writeString(python, "#!/bin/sh\necho 'collection crashed'\nexit 2\n");
    Files.setPosixFilePermissions(python, PosixFilePermissions.fromString("rwxr-xr-x"));

    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(
            new TestCommandResolver(),
            new com.navneet.executespec.tool.ShellSandboxPolicy(),
            null,
            null,
            null);

    var result = runner.runTests("run-red", workspace).orElseThrow();
    assertEquals(1, result.getTotalTests());
    assertEquals(1, result.getFailed());
    assertTrue(result.isExecutionProven());
    assertEquals(
        com.navneet.executespec.testgen.model.TestExecutionSummary.EvidenceScope
            .WORKSPACE_TEST_SUITE,
        result.getEvidenceScope());
    var typed = runner.executeTests(new ExecutionContext("run-red", "T1", workspace));
    assertEquals(WorkspaceTestRunner.ExecutionStatus.PRODUCT_FAILED, typed.status());
    assertTrue(typed.productFailure());
  }

  @Test
  void nestedMavenRepoPublishesFreshSurefireCountsAsExecutableEvidence(@TempDir Path workspace)
      throws Exception {
    Path repository = workspace.resolve("repos/orders-service");
    Files.createDirectories(repository);
    Files.writeString(repository.resolve("pom.xml"), "<project/>");
    Path wrapper = repository.resolve("mvnw");
    Files.writeString(
        wrapper,
        """
#!/bin/sh
mkdir -p target/surefire-reports
i=1
while [ "$i" -le 17 ]; do
  printf '<testsuite tests="4" failures="0" errors="0" skipped="0"/>' > "target/surefire-reports/TEST-suite-$i.xml"
  i=$((i + 1))
done
printf '<testsuite tests="8" failures="0" errors="0" skipped="0"/>' > target/surefire-reports/TEST-suite-18.xml
exit 0
""");
    Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));

    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(
            new TestCommandResolver(),
            new com.navneet.executespec.tool.ShellSandboxPolicy(),
            null,
            null,
            null);

    var detailed = runner.runTestsDetailed("run-nested-maven", workspace).orElseThrow();
    assertEquals("(cd repos/orders-service && ./mvnw -q test)", detailed.command());
    assertEquals(76, detailed.summary().getTotalTests());
    assertEquals(76, detailed.summary().getPassed());
    assertTrue(detailed.summary().isExecutionProven());
    assertEquals(
        com.navneet.executespec.testgen.model.TestExecutionSummary.EvidenceScope
            .WORKSPACE_TEST_SUITE,
        detailed.summary().getEvidenceScope());
  }
}
