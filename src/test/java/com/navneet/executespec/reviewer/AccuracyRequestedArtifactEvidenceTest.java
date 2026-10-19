package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.tool.FileSystemTool;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AccuracyRequestedArtifactEvidenceTest {
  @Test
  void unquotedRequestedTextArtifactIsIncludedBeforeGenericConfigs() {
    var service = new AccuracyAssessmentService(null, null, null, null, new ObjectMapper());
    assertThat(
            service.requestedArtifactEvidencePaths(
                "hello.txt\nrequirements.txt\nREADME.md",
                "Create hello.txt containing exactly one line: Hello World"))
        .containsExactly("hello.txt", "README.md", "requirements.txt");
  }

  @Test
  void requestedArtifactContentAndTrueLineCountReachTheAssessor() {
    var files = mock(FileSystemTool.class);
    // Preserve the service's existing constructor ordering through its field seam.
    var service = new AccuracyAssessmentService(null, null, null, null, new ObjectMapper());
    ReflectionTestUtils.setField(service, "fileSystemTool", files);
    when(files.readFile("run", "hello.txt")).thenReturn("Hello World\n");
    String evidence =
        ReflectionTestUtils.invokeMethod(
            service,
            "buildConfigFileSnippets",
            "run",
            "hello.txt",
            "Write hello.txt with one line Hello World");
    assertThat(evidence)
        .contains("### hello.txt", "fileLines=1", "snippetComplete=true", "Hello World");
  }

  @Test
  void substringMentionsTraversalAndVendoredFilesDoNotCreateArtifactEvidence() {
    var service = new AccuracyAssessmentService(null, null, null, null, new ObjectMapper());
    assertThat(
            service.requestedArtifactEvidencePaths(
                "hello.txt\n../outside.txt\nvendor/report.txt",
                "Use foohello.txt.bak and ../outside.txt and vendor/report.txt"))
        .isEmpty();
  }
}
