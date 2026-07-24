package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkspaceExportServiceExclusionTest {

  @Test
  void excludesPythonRuntimeAndTestCachesFromPublishedArchives() {
    assertThat(WorkspaceExportService.shouldExclude("app/__pycache__")).isTrue();
    assertThat(WorkspaceExportService.shouldExclude("app/__pycache__/service.cpython-312.pyc"))
        .isTrue();
    assertThat(WorkspaceExportService.shouldExclude(".pytest_cache/v/cache/nodeids")).isTrue();
    assertThat(WorkspaceExportService.shouldExclude("src/module.pyc")).isTrue();
    assertThat(WorkspaceExportService.shouldExclude("src/module.pyo")).isTrue();
  }

  @Test
  void keepsMaintainedPythonTestsAndApplicationSources() {
    assertThat(WorkspaceExportService.shouldExclude("tests/test_inventory.py")).isFalse();
    assertThat(WorkspaceExportService.shouldExclude("app/services.py")).isFalse();
  }
}
