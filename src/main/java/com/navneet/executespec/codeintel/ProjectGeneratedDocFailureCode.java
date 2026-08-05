package com.navneet.executespec.codeintel;

/** Stable customer-safe subcodes for deterministic generated-document rejection. */
final class ProjectGeneratedDocFailureCode {

  private ProjectGeneratedDocFailureCode() {}

  static String fromReason(String reason) {
    if (reason == null || reason.isBlank()) return "OTHER_CONTRACT_VIOLATION";
    if (reason.equals("empty output")) return "EMPTY_OUTPUT";
    if (reason.equals("output is too short")) return "OUTPUT_TOO_SHORT";
    if (reason.equals("unbalanced fenced code block")) return "UNBALANCED_FENCE";
    if (reason.startsWith("missing required heading")) return "MISSING_REQUIRED_HEADING";
    if (reason.startsWith("required section has no meaningful content"))
      return "EMPTY_REQUIRED_SECTION";
    if (reason.startsWith("missing linked repository")) return "MISSING_LINKED_REPOSITORY";
    if (reason.equals("clone commands must be present in a fenced block"))
      return "CLONE_BLOCK_REQUIRED";
    if (reason.startsWith("setup evidence") || reason.startsWith("setup source encoding"))
      return "SETUP_EVIDENCE_INVALID";
    if (reason.startsWith("setup command must be") || reason.startsWith("git clone must be one"))
      return "SETUP_COMMAND_UNSAFE";
    if (reason.startsWith("setup command is not present")) return "SETUP_COMMAND_UNAUTHORIZED";
    if (reason.startsWith("git clone must target") || reason.startsWith("git clone target is not"))
      return "CLONE_TARGET_INVALID";
    if (reason.startsWith("unsupported ")) return "UNSUPPORTED_FACTUAL_CLAIM";
    return "OTHER_CONTRACT_VIOLATION";
  }
}
