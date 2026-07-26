package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildCommandResolverPythonTest {

  @Test
  void rejectsChainedTestSuitesAsPlannerBuildSmokes() {
    assertThat(
            BuildCommandResolver.isRepeatableBuildSmoke(
                "cd backend && python3 -m pytest && cd ../frontend && npx playwright test"))
        .isFalse();
  }

  @Test
  void normalizesBarePipInPlannerDeclaredBuildCommand() {
    assertThat(
            BuildCommandResolver.normalizePython(
                "pip install -r requirements.txt && python -c \"from app.main import app\""))
        .isEqualTo(
            "python3 -m pip install -r requirements.txt && python3 -c \"from app.main import"
                + " app\"");
  }

  @Test
  void leavesExplicitPipVariantsAndPathsUnchanged() {
    assertThat(BuildCommandResolver.normalizePython("pip3 install -r requirements.txt"))
        .isEqualTo("pip3 install -r requirements.txt");
    assertThat(BuildCommandResolver.normalizePython("/usr/local/bin/pip install -e ."))
        .isEqualTo("/usr/local/bin/pip install -e .");
    assertThat(BuildCommandResolver.normalizePython("python3 -m pip install -e ."))
        .isEqualTo("python3 -m pip install -e .");
  }
}
