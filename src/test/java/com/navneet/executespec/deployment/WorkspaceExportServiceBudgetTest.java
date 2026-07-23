package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-165 — a workspace/repo export must refuse an oversized repository (by total uncompressed bytes
 * or member count) instead of materializing it without limit.
 */
class WorkspaceExportServiceBudgetTest {

  @Test
  void rejectsWhenTotalBytesExceedLimit(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("big.bin");
    Files.write(file, new byte[100]);
    var budget = new WorkspaceExportService.ExportBudget(40, 1000);
    try (ZipOutputStream zos = new ZipOutputStream(new ByteArrayOutputStream())) {
      zos.putNextEntry(new java.util.zip.ZipEntry("big.bin"));
      assertThatThrownBy(() -> budget.copyInto(file, zos))
          .isInstanceOf(WorkspaceExportService.ExportTooLargeException.class)
          .hasMessageContaining("bytes");
    }
  }

  @Test
  void rejectsWhenFileCountExceedsLimit(@TempDir Path dir) throws Exception {
    Path a = dir.resolve("a");
    Path b = dir.resolve("b");
    Files.write(a, new byte[1]);
    Files.write(b, new byte[1]);
    var budget = new WorkspaceExportService.ExportBudget(1_000_000, 1);
    try (ZipOutputStream zos = new ZipOutputStream(new ByteArrayOutputStream())) {
      zos.putNextEntry(new java.util.zip.ZipEntry("a"));
      budget.copyInto(a, zos);
      zos.putNextEntry(new java.util.zip.ZipEntry("b"));
      assertThatThrownBy(() -> budget.copyInto(b, zos))
          .isInstanceOf(WorkspaceExportService.ExportTooLargeException.class)
          .hasMessageContaining("files");
    }
  }

  @Test
  void copiesUnderLimitContent(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ok.txt");
    Files.writeString(file, "content");
    var budget = new WorkspaceExportService.ExportBudget(1_000_000, 1000);
    var out = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(out)) {
      zos.putNextEntry(new java.util.zip.ZipEntry("ok.txt"));
      budget.copyInto(file, zos);
      zos.closeEntry();
    }
    assertThat(out.size()).isGreaterThan(0);
  }
}
