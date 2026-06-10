package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class SemgrepRunnerTest {

  private final SemgrepRunner runner = new SemgrepRunner(new ObjectMapper());

  @Test
  void parseMapsSemgrepCriticalToCriticalSeverity() {
    String json =
        """
        {
          "paths": { "scanned": ["src/App.java"] },
          "results": [
            {
              "check_id": "java.security.critical",
              "path": "src/App.java",
              "start": { "line": 7 },
              "end": { "line": 8 },
              "extra": {
                "severity": "CRITICAL",
                "message": "critical issue",
                "lines": "dangerousCall();",
                "metadata": { "category": "security" }
              }
            }
          ]
        }
        """;

    SemgrepRunner.ParsedResult parsed = runner.parse(json);

    assertThat(parsed.scanned()).isEqualTo(1);
    assertThat(parsed.findings()).hasSize(1);
    SemgrepRunner.Finding finding = parsed.findings().get(0);
    assertThat(finding.severity()).isEqualTo("CRITICAL");
    assertThat(finding.category()).isEqualTo("SECURITY");
  }
}
