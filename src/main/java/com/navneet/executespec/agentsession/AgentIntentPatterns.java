package com.navneet.executespec.agentsession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;

final class AgentIntentPatterns {

  private static final Pattern UUIDISH =
      Pattern.compile(
          "\\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\b|\\b[0-9a-fA-F]{8,}\\b");

  private AgentIntentPatterns() {}

  static boolean forbidsEdits(String lower) {
    return containsAny(
        lower,
        "do not edit",
        "don't edit",
        "dont edit",
        "chat only",
        "no code changes",
        "no edits",
        "just explain",
        "only explain",
        "generate a patch in chat only");
  }

  static boolean isUnsafe(String lower) {
    return containsAny(
        lower,
        "delete everything",
        "drop database",
        "wipe the repo",
        "rm -rf",
        "exfiltrate",
        "steal token",
        "dump secrets",
        "push credentials",
        "remove all customer data",
        "disable auth and expose admin");
  }

  static boolean looksLikeReview(String lower) {
    return containsWordAny(lower, "review", "audit", "findings")
        || containsAny(lower, "code smell", "security review");
  }

  static boolean looksLikePlan(String lower) {
    return containsAny(
        lower,
        "write a plan",
        "make a plan",
        "create a plan",
        "implementation plan",
        "how would",
        "what should",
        "approach",
        "design this",
        "architecture");
  }

  static boolean looksLikeReadOnlyCodeHelp(String lower) {
    if (lower.contains("find ") && lower.contains("bug") && !lower.contains("fix ")) {
      return true;
    }
    return containsAny(
        lower,
        "explain",
        "what does",
        "how does",
        "summarize",
        "show me",
        "show file",
        "read file",
        "open file",
        "list files",
        "search",
        "grep",
        "find the bug",
        "find likely bug",
        "likely bug",
        "what changed",
        "what is in");
  }

  static boolean looksLikeLocalDeterministicAction(String lower) {
    return containsAny(
        lower,
        "git status",
        "git diff",
        "diff ",
        "revert ",
        "commit ",
        "push ",
        "commit and push",
        "commit all",
        "run command",
        "shell command");
  }

  static boolean looksLikeVagueMutation(String lower, InterpretIntentRequest request) {
    if (!containsAny(lower, "fix this", "debug this", "make this work", "make it work")) {
      return false;
    }
    boolean hasProject =
        request != null && request.projectId() != null && !request.projectId().isBlank();
    boolean hasChangedFiles =
        request != null
            && request.repoContext() != null
            && request.repoContext().changedFiles() != null
            && !request.repoContext().changedFiles().isEmpty();
    return !hasProject && !hasChangedFiles;
  }

  static boolean looksLikeCodeChange(String lower) {
    return containsAny(
        lower,
        "fix ",
        "implement ",
        "add ",
        "update ",
        "change ",
        "refactor ",
        "debug ",
        "failing test",
        "failing tests",
        "create ",
        "run tests",
        "run the tests",
        "create codemap",
        "create codemap.md",
        "make ");
  }

  static String routeForReadOnly(String lower) {
    if (looksLikeReview(lower)) return "REVIEW_ONLY";
    if (looksLikePlan(lower)) return "PLAN_ONLY";
    if (looksLikeReadOnlyCodeHelp(lower)) return "READ_ONLY_CODE_HELP";
    return "CHAT_ONLY";
  }

  static boolean containsAny(String haystack, String... needles) {
    for (String n : needles) {
      if (haystack.contains(n)) return true;
    }
    return false;
  }

  static String extractRunId(String message) {
    Matcher m = UUIDISH.matcher(message);
    return m.find() ? m.group() : null;
  }

  private static boolean containsWordAny(String haystack, String... words) {
    for (String word : words) {
      if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find()) {
        return true;
      }
    }
    return false;
  }
}
