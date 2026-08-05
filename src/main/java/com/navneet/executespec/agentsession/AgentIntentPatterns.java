package com.navneet.executespec.agentsession;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;

final class AgentIntentPatterns {

  /**
   * The canonical run identifier shape. Run ids are UUIDs, and a bare hex run of eight or more
   * characters is not one: it matches commit SHAs, hex-looking words and long digit strings, each
   * of which used to produce a credit-charging approve/cancel confirmation card for a "run" that
   * does not exist — or, on collision, for the wrong one.
   */
  private static final Pattern RUN_ID =
      Pattern.compile("\\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\b");

  private static final Pattern QUOTED_TEXT = Pattern.compile("\"[^\"\\n]*\"|`[^`\\n]*`|'[^'\\n]*'");

  private AgentIntentPatterns() {}

  static boolean forbidsEdits(String lower) {
    return containsAny(
        lower,
        "do not edit",
        "don't edit",
        "dont edit",
        "do not modify code",
        "don't modify code",
        "dont modify code",
        "do not modify files",
        "without modifying code",
        "do not create a run",
        "don't create a run",
        "dont create a run",
        "no run should be created",
        "analysis only",
        "inspection only",
        "keep this read-only",
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

  static boolean looksLikeCapabilitiesQuestion(String lower) {
    return containsAny(
        lower,
        "what can you do",
        "what are your features",
        "tell me about your features",
        "what are your capabilities",
        "tell me about your capabilities",
        "how can you help me");
  }

  static boolean looksLikeReadOnlyCodeHelp(String lower) {
    if (lower.contains("find ") && lower.contains("bug") && !lower.contains("fix ")) {
      return true;
    }
    return containsAny(
        lower,
        "explain",
        "analyse",
        "analyze",
        "inspect",
        "identify",
        "locate",
        "trace",
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
        "what is in",
        // P1-3 (2026-08-01): interrogative questions ABOUT code ownership/location are read-only
        // code help, so they get the grounded repository tool surface instead of bare chat.
        "which file",
        "which files",
        "which class",
        "which module",
        "where is",
        "where are",
        "what implements",
        "what code");
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
    // Quoted identifiers/examples are evidence, not imperatives. Without this boundary a turn
    // such as `Please handle "fix"` bypasses the structured classifier and creates a cloud run.
    String actionableText = QUOTED_TEXT.matcher(lower).replaceAll(" ");
    boolean vocabularyHit =
        containsWordAny(
                actionableText,
                "fix",
                "implement",
                "add",
                "update",
                "change",
                "refactor",
                "debug",
                "build",
                "create",
                "make")
            || containsAny(
                actionableText,
                "failing test",
                "failing tests",
                "run tests",
                "run the tests",
                "create codemap",
                "create codemap.md");
    if (!vocabularyHit) {
      return false;
    }
    // P1-3 (2026-08-01): "which files implement the simulation logic?" is a question ABOUT code,
    // not an instruction to change it — the live router turned exactly that phrasing into a paid
    // cloud-run proposal. A mutation keyword counts only when at least one clause uses it outside
    // an interrogative-information context; polite imperatives ("can you implement …") remain
    // code changes.
    return !isOnlyAskingAboutCode(actionableText);
  }

  private static final java.util.regex.Pattern CLAUSE_SPLIT =
      java.util.regex.Pattern.compile("[.?!;:\\n]+");

  private static final String[] MUTATION_WORDS = {
    "fix", "implement", "add", "update", "change", "refactor", "debug", "build", "create", "make"
  };

  private static final String[] INTERROGATIVE_LEADS = {
    "which", "what", "where", "who", "when", "why", "how", "does", "do", "is", "are"
  };

  private static final String[] POLITE_IMPERATIVE_MARKERS = {
    "can you", "could you", "would you", "will you", "please", "go ahead"
  };

  /**
   * True only when EVERY clause containing mutation vocabulary reads as an information question: an
   * interrogative word precedes the mutation keyword in the same clause and the clause carries no
   * polite-imperative marker. "Explain X. Then implement Y" therefore stays a code change, while
   * "which files implement X" and "where is the retry logic added" become read-only.
   */
  private static boolean isOnlyAskingAboutCode(String actionableText) {
    boolean sawMutationClause = false;
    for (String clause : CLAUSE_SPLIT.split(actionableText)) {
      String trimmed = clause.strip();
      if (trimmed.isEmpty()) continue;
      int mutationIndex = earliestWordIndex(trimmed, MUTATION_WORDS);
      if (mutationIndex < 0) continue;
      sawMutationClause = true;
      if (containsAny(trimmed, POLITE_IMPERATIVE_MARKERS)) {
        return false;
      }
      int interrogativeIndex = earliestWordIndex(trimmed, INTERROGATIVE_LEADS);
      if (interrogativeIndex < 0 || interrogativeIndex > mutationIndex) {
        return false;
      }
    }
    return sawMutationClause;
  }

  private static int earliestWordIndex(String clause, String[] words) {
    int earliest = -1;
    for (String word : words) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b")
              .matcher(clause);
      if (m.find() && (earliest < 0 || m.start() < earliest)) {
        earliest = m.start();
      }
    }
    return earliest;
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

  /**
   * @return the canonical run id present in the message, or null when there is none
   */
  static String extractRunId(String message) {
    if (message == null) return null;
    Matcher m = RUN_ID.matcher(message);
    return m.find() ? m.group() : null;
  }

  static boolean containsWordAny(String haystack, String... words) {
    for (String word : words) {
      if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find()) {
        return true;
      }
    }
    return false;
  }
}
