package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CodeMapGeneratorExportedFunctionEvidenceTest {

  @Test
  void codemapCarriesExactEvidenceFromTheSamePinnedSourceRead() {
    GitHubRepoService github = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> summaries = mock(ObjectProvider.class);
    CodeMapGenerator generator =
        new CodeMapGenerator(
            github,
            new SpringHttpArchitectureEvidenceRenderer(github, new SpringHttpSurfaceExtractor()),
            summaries);
    String revision = "b".repeat(40);
    String path = "src/catalog.js";
    String source =
        "export function countFeatured(items) {\n"
            + "  return items.filter((item) => item.tags.includes('featured')).length;\n"
            + "}\n";
    when(github.listRepoFilesResult(any(), eq(revision), any()))
        .thenReturn(GitHubRepoService.RepoFileListResult.success(List.of(path)));
    lenient()
        .when(github.readRepoFile(any(), eq(path), eq(revision), any()))
        .thenReturn(GitHubRepoService.RepoFileResult.success(source));

    String codemap = generator.generate("acme/catalog", revision, "pat");

    assertThat(codemap)
        .contains(ExportedFunctionEvidence.BEGIN, ExportedFunctionEvidence.END)
        .contains("Source-Revision: `" + revision + "`")
        .contains("Path-Json: \"src/catalog.js\"")
        .contains("Symbol-Json: \"countFeatured\"")
        .contains("item.tags.includes('featured')")
        .contains("Truncated: false");
    verify(github, times(1)).readRepoFile(any(), eq(path), eq(revision), any());
  }
}
