package com.navneet.executespec.project.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Compact durable source/correlation identities for document and repository embedding jobs. */
final class EmbeddingOperationIdentity {

  private EmbeddingOperationIdentity() {}

  static String document(String documentId, String sourceSha256, int processingAttempt) {
    return compact(
        "project-document",
        Objects.requireNonNull(documentId, "documentId"),
        Objects.requireNonNull(sourceSha256, "sourceSha256"),
        Integer.toString(processingAttempt));
  }

  static String codeIndex(UUID repoId, String sourceRevision) {
    return compact(
        "project-code",
        Objects.requireNonNull(repoId, "repoId").toString(),
        Objects.requireNonNull(sourceRevision, "sourceRevision"));
  }

  private static String compact(String prefix, String... components) {
    String evidence = String.join("\u001f", components);
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(evidence.getBytes(StandardCharsets.UTF_8)));
      return prefix + ":" + digest.substring(0, 32);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
