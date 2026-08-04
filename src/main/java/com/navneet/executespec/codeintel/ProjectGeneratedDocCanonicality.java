package com.navneet.executespec.codeintel;

import java.util.Set;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;

/** Central cache/publication canonicality policy for generated project documents. */
final class ProjectGeneratedDocCanonicality {

  private ProjectGeneratedDocCanonicality() {}

  static boolean isCanonical(
      ProjectGeneratedDoc document, String projectName, String evidence, Set<String> repositories) {
    return switch (document.getDocType()) {
      case EXPLANATION ->
          ProjectExplanationOperationalGrounding.isCanonical(
              document.getContent(), evidence, repositories);
      case SETUP ->
          ProjectSetupOperationalGrounding.isCanonical(
              document.getContent(), projectName, evidence, repositories);
    };
  }
}
