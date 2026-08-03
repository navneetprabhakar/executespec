package com.navneet.executespec.codeintel;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;

/** Deterministic minimum-quality gate for provider-generated project documents. */
final class ProjectGeneratedDocValidator {

  private static final int MIN_NON_WHITESPACE_CHARACTERS = 400;
  private static final Pattern FENCED_BLOCK =
      Pattern.compile("(?ms)^(`{3,}|~{3,})([^\\r\\n]*)\\R(.*?)^\\1\\s*$");
  private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\r\\n]+)`(?!`)");
  private static final Pattern ENCODED_SETUP_SOURCE =
      Pattern.compile(
          "(?m)^- Source-Path-Base64: ([A-Za-z0-9+/=]+)\\R"
              + "  Content-Base64: ([A-Za-z0-9+/=]*)\\R"
              + "  Truncated: (?:true|false)$");
  private static final Pattern SETUP_ENVELOPE =
      Pattern.compile(
          "(?s)"
              + Pattern.quote(DeterministicProjectFactEvidence.SETUP_BEGIN)
              + "(.*?)"
              + Pattern.quote(DeterministicProjectFactEvidence.SETUP_END));
  private static final Pattern DERIVED_COMMAND =
      Pattern.compile("(?m)^  Derived-Command-Base64: ([A-Za-z0-9+/=]+)$");
  private static final Pattern PACKAGE_SCRIPTS =
      Pattern.compile("(?s)\\\"scripts\\\"\\s*:\\s*\\{(.*?)\\}");
  private static final Pattern JSON_STRING_PAIR =
      Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
  private static final Pattern INDENTED_CODE = Pattern.compile("(?m)^(?: {4}|\\t)(\\S.*)$");
  private static final Pattern CLONE =
      Pattern.compile(
          "^git\\s+clone\\s+((?:https://(?:github\\.com|bitbucket\\.org)/|"
              + "git@(?:github\\.com|bitbucket\\.org):)[^\\s]+)(?:\\s+([A-Za-z0-9][A-Za-z0-9._-]*))?$");
  private static final Pattern SHELL_META = Pattern.compile("[;&|<>`$(){}\\[\\]\\r\\n\\\\]");
  private static final Pattern FENCE_LINE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
  private static final Set<String> SINGLE_TOKEN_EXECUTABLES =
      Set.of(
          "bash",
          "bun",
          "cargo",
          "curl",
          "docker",
          "env",
          "fish",
          "git",
          "go",
          "gradle",
          "gradlew",
          "id",
          "java",
          "kubectl",
          "make",
          "mvn",
          "mvnw",
          "node",
          "npm",
          "pip",
          "pip3",
          "pnpm",
          "powershell",
          "printenv",
          "pwsh",
          "pytest",
          "python",
          "python3",
          "sh",
          "terraform",
          "wget",
          "whoami",
          "yarn",
          "zsh");

  private ProjectGeneratedDocValidator() {}

  static Optional<String> validate(DocType type, String content, Set<String> expectedRepositories) {
    return validate(type, content, expectedRepositories, "");
  }

  static Optional<String> validate(
      DocType type, String content, Set<String> expectedRepositories, String codemapEvidence) {
    if (content == null || content.isBlank()) return Optional.of("empty output");
    long meaningful = content.chars().filter(c -> !Character.isWhitespace(c)).count();
    if (meaningful < MIN_NON_WHITESPACE_CHARACTERS) return Optional.of("output is too short");
    if (hasUnbalancedFence(content)) return Optional.of("unbalanced fenced code block");

    String normalized = content.toLowerCase(Locale.ROOT);
    List<String> required =
        switch (type) {
          case EXPLANATION ->
              List.of(
                  "# ",
                  "## repositories",
                  "## high-level architecture",
                  "## key data flows",
                  "## where to contribute");
          case SETUP ->
              List.of(
                  "# setting up",
                  "## prerequisites",
                  "## clone",
                  "## environment variables",
                  "## first-run per repo",
                  "## running the full stack locally",
                  "## troubleshooting");
        };
    Optional<String> missingHeading =
        required.stream()
            .filter(requiredHeading -> !hasHeading(normalized, requiredHeading))
            .findFirst()
            .map(missing -> "missing required heading " + missing.trim());
    if (missingHeading.isPresent()) return missingHeading;

    for (String heading : required) {
      if (!heading.startsWith("## ")) continue;
      if (sectionBodyMeaningful(normalized, heading) < 20) {
        return Optional.of("required section has no meaningful content " + heading.trim());
      }
    }

    if (expectedRepositories != null) {
      for (String repo : expectedRepositories) {
        if (repo != null && !repo.isBlank() && !containsExactRepository(content, repo)) {
          return Optional.of("missing linked repository " + repo);
        }
      }
    }

    if (type == DocType.SETUP) {
      String clone = section(normalized, "## clone");
      if (!clone.contains("git clone") || !FENCED_BLOCK.matcher(clone).find()) {
        return Optional.of("clone commands must be present in a fenced block");
      }
      Optional<String> unsupportedCommand =
          unsupportedSetupCommand(content, codemapEvidence, expectedRepositories);
      if (unsupportedCommand.isPresent()) return unsupportedCommand;
    }
    if (type == DocType.EXPLANATION) {
      Optional<String> unsupported =
          GeneratedSignatureGrounding.unsupportedBehaviorClaim(content, codemapEvidence);
      if (unsupported.isPresent()) return unsupported;
    }
    Optional<String> unsupportedFacts =
        GeneratedProjectFactGrounding.unsupportedFactualClaim(content, codemapEvidence);
    if (unsupportedFacts.isPresent()) return unsupportedFacts;
    return Optional.empty();
  }

  private static boolean hasHeading(String normalized, String heading) {
    if ("# ".equals(heading)) return Pattern.compile("(?m)^#\\s+\\S").matcher(normalized).find();
    if (heading.startsWith("# ")) {
      return Pattern.compile("(?m)^" + Pattern.quote(heading) + "(?:\\s+.+)?$")
          .matcher(normalized)
          .find();
    }
    return Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$").matcher(normalized).find();
  }

  private static Optional<String> unsupportedSetupCommand(
      String content, String deterministicEvidence, Set<String> expectedRepositories) {
    SetupAuthority authority = decodedSetupSources(deterministicEvidence, expectedRepositories);
    if (!authority.valid()) return Optional.of(authority.error());
    List<CommandSnippet> executableSnippets = new ArrayList<>();
    Matcher blocks = FENCED_BLOCK.matcher(content);
    StringBuilder prose = new StringBuilder(content.length());
    int cursor = 0;
    while (blocks.find()) {
      prose.append(content, cursor, blocks.start());
      String language = blocks.group(2).strip().toLowerCase(Locale.ROOT);
      boolean shellFence = language.matches("(?:bash|sh|shell|zsh|fish|powershell|pwsh)");
      boolean consoleFence = language.equals("console");
      String presentedRepository =
          authority.presentedRepository(content.substring(cursor, blocks.start()));
      commandsInBlock(blocks.group(3), shellFence, consoleFence, authority).stream()
          .map(command -> new CommandSnippet(command, presentedRepository))
          .forEach(executableSnippets::add);
      cursor = blocks.end();
    }
    prose.append(content, cursor, content.length());
    Matcher inline = INLINE_CODE.matcher(prose);
    while (inline.find()) {
      String candidate = inline.group(1).strip();
      if (looksCommandLike(candidate, authority))
        executableSnippets.add(
            new CommandSnippet(
                normalizePrompt(candidate),
                authority.presentedRepository(prose.substring(0, inline.start()))));
    }
    Matcher indented = INDENTED_CODE.matcher(prose);
    while (indented.find()) {
      String candidate = indented.group(1).strip();
      if (looksCommandLike(candidate, authority))
        executableSnippets.add(
            new CommandSnippet(
                normalizePrompt(candidate),
                authority.presentedRepository(prose.substring(0, indented.start()))));
    }

    for (CommandSnippet snippet : executableSnippets) {
      String command = snippet.command();
      if (isGitClone(command)) {
        Optional<String> invalidTarget = invalidCloneTarget(command, authority);
        if (invalidTarget.isPresent()) return invalidTarget;
        continue;
      }
      if (SHELL_META.matcher(command).find()) {
        return Optional.of("setup command must be one literal command without shell operators");
      }
      if (!authority.authorizes(snippet.repository(), command)) {
        return Optional.of("setup command is not present in encoded exact-revision setup evidence");
      }
    }
    return Optional.empty();
  }

  private static List<String> commandsInBlock(
      String body, boolean shellFence, boolean consoleFence, SetupAuthority authority) {
    List<String> commands = new ArrayList<>();
    StringBuilder continuation = new StringBuilder();
    for (String raw : body.split("\\R", -1)) {
      String line = raw.strip();
      if (line.isBlank() || line.startsWith("#")) continue;
      boolean prompted = line.startsWith("$ ") || line.startsWith("> ");
      if (consoleFence && !prompted) continue; // captured output, not an instruction
      line = normalizePrompt(line);
      if (!continuation.isEmpty()) continuation.append(' ');
      continuation.append(
          line.endsWith("\\") ? line.substring(0, line.length() - 1).strip() : line);
      if (line.endsWith("\\")) continue;
      String command = continuation.toString().strip();
      continuation.setLength(0);
      if (shellFence || prompted || looksCommandLike(command, authority)) commands.add(command);
    }
    if (!continuation.isEmpty()) commands.add(continuation.toString().strip());
    return commands;
  }

  private static String normalizePrompt(String candidate) {
    String command = candidate.strip();
    return command.startsWith("$ ") || command.startsWith("> ")
        ? command.substring(2).strip()
        : command;
  }

  private static boolean looksCommandLike(String candidate, SetupAuthority authority) {
    String command = normalizePrompt(candidate);
    if (command.isBlank() || command.startsWith("{") || command.startsWith("[")) return false;
    if (authority.authorizesAnywhere(command) || isGitClone(command)) return true;
    if (command.startsWith("./") || command.startsWith("../") || command.startsWith("/"))
      return true;
    if (SHELL_META.matcher(command).find()) return true;
    String[] tokens = command.split("\\s+");
    int index = 0;
    while (index < tokens.length && tokens[index].matches("[A-Za-z_][A-Za-z0-9_]*=.*")) index++;
    if (index >= tokens.length || !tokens[index].matches("[A-Za-z0-9_.+-]+")) return false;
    if (index + 1 >= tokens.length) {
      return SINGLE_TOKEN_EXECUTABLES.contains(tokens[index].toLowerCase(Locale.ROOT));
    }
    String argument = tokens[index + 1];
    return argument.startsWith("-")
        || argument.startsWith(".")
        || argument.startsWith("/")
        || argument.matches("[A-Za-z0-9_.@:+/-]+");
  }

  private static boolean containsExactRepository(String content, String repository) {
    return exactRepositoryPattern(repository).matcher(content).find();
  }

  private static Pattern exactRepositoryPattern(String repository) {
    return Pattern.compile(
        "(?i)(?<![A-Za-z0-9_./-])" + Pattern.quote(repository) + "(?![A-Za-z0-9_./-])");
  }

  private static boolean isGitClone(String command) {
    return command.matches("(?s)^git\\s+clone(?:\\s+.*)?$");
  }

  private static Optional<String> invalidCloneTarget(String command, SetupAuthority authority) {
    if (SHELL_META.matcher(command).find()) {
      return Optional.of("git clone must be one literal command without shell operators");
    }
    Matcher target = CLONE.matcher(command);
    if (!target.matches()) {
      return Optional.of("git clone must target exactly one linked source repository");
    }
    String url = target.group(1);
    String host = url.toLowerCase(Locale.ROOT).contains("bitbucket.org") ? "BITBUCKET" : "GITHUB";
    String repository =
        url.replaceFirst(
                "(?i)^(?:https://(?:github\\.com|bitbucket\\.org)/|git@(?:github\\.com|bitbucket\\.org):)",
                "")
            .replaceFirst("(?i)\\.git$", "");
    if (!authority.matchesClone(repository, host)) {
      return Optional.of("git clone target is not a linked repository");
    }
    return Optional.empty();
  }

  private static SetupAuthority decodedSetupSources(
      String evidence, Set<String> expectedRepositories) {
    if (evidence == null || evidence.isBlank())
      return SetupAuthority.invalid("setup evidence is missing");
    Set<String> expected = normalizedRepositories(expectedRepositories);
    Set<SetupSource> decoded = new LinkedHashSet<>();
    java.util.Map<String, Set<String>> derived = new java.util.LinkedHashMap<>();
    java.util.Map<String, String> scmByRepository = new java.util.LinkedHashMap<>();
    Matcher envelope = SETUP_ENVELOPE.matcher(evidence);
    int blocks = 0;
    while (envelope.find()) {
      blocks++;
      String block = envelope.group(1);
      if (!block.contains("- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2"))
        return SetupAuthority.invalid("setup evidence format is not current");
      String repositoryEncoded = field(block, "Repository-Base64");
      String revision = field(block, "Source-Revision");
      String scm = field(block, "Scm-Provider");
      String repository = decodeCanonical(repositoryEncoded, 512);
      if (repository == null
          || revision == null
          || !revision.matches("[0-9a-fA-F]{40}")
          || scm == null) return SetupAuthority.invalid("setup evidence identity is malformed");
      String normalizedRepository = repository.toLowerCase(Locale.ROOT);
      if (!expected.contains(normalizedRepository)
          || scmByRepository.put(normalizedRepository, scm.toUpperCase(Locale.ROOT)) != null)
        return SetupAuthority.invalid("setup evidence repository identity is invalid");
      Matcher source = ENCODED_SETUP_SOURCE.matcher(block);
      while (source.find()) {
        String path = decodeCanonical(source.group(1), 4_096);
        String content = decodeCanonical(source.group(2), 1_200);
        if (path == null || content == null)
          return SetupAuthority.invalid("setup source encoding is malformed");
        decoded.add(new SetupSource(normalizedRepository, path, content));
      }
      Matcher command = DERIVED_COMMAND.matcher(block);
      while (command.find()) {
        String value = decodeCanonical(command.group(1), 4_096);
        if (value == null) return SetupAuthority.invalid("derived command encoding is malformed");
        derived.computeIfAbsent(normalizedRepository, ignored -> new LinkedHashSet<>()).add(value);
      }
    }
    if (blocks != expected.size() || scmByRepository.size() != expected.size())
      return SetupAuthority.invalid("setup evidence does not cover every linked repository");
    if (occurrences(evidence, DeterministicProjectFactEvidence.SETUP_BEGIN) != blocks
        || occurrences(evidence, DeterministicProjectFactEvidence.SETUP_END) != blocks)
      return SetupAuthority.invalid("setup evidence contains an unframed or duplicate marker");
    return new SetupAuthority(
        true,
        Set.copyOf(decoded),
        immutableCommandMap(derived),
        java.util.Map.copyOf(scmByRepository),
        null);
  }

  private static java.util.Map<String, Set<String>> immutableCommandMap(
      java.util.Map<String, Set<String>> commands) {
    java.util.Map<String, Set<String>> immutable = new java.util.LinkedHashMap<>();
    commands.forEach((repository, values) -> immutable.put(repository, Set.copyOf(values)));
    return java.util.Map.copyOf(immutable);
  }

  private static Set<String> normalizedRepositories(Set<String> repositories) {
    if (repositories == null) return Set.of();
    return repositories.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static String field(String block, String name) {
    Matcher matcher =
        Pattern.compile("(?m)^- " + Pattern.quote(name) + ": ([^\\r\\n]+)$").matcher(block);
    if (!matcher.find()) return null;
    String value = matcher.group(1).strip();
    return matcher.find() ? null : value;
  }

  private static String decodeCanonical(String encoded, int maxDecodedBytes) {
    if (encoded == null || encoded.length() > ((maxDecodedBytes + 2) / 3) * 4) return null;
    try {
      byte[] bytes = Base64.getDecoder().decode(encoded);
      if (bytes.length > maxDecodedBytes
          || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) return null;
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (IllegalArgumentException | CharacterCodingException malformed) {
      return null;
    }
  }

  private static int occurrences(String source, String marker) {
    int occurrences = 0;
    int cursor = 0;
    while ((cursor = source.indexOf(marker, cursor)) >= 0) {
      occurrences++;
      cursor += marker.length();
    }
    return occurrences;
  }

  private record SetupAuthority(
      boolean valid,
      Set<SetupSource> sources,
      java.util.Map<String, Set<String>> derivedCommandsByRepository,
      java.util.Map<String, String> scmByRepository,
      String error) {

    static SetupAuthority invalid(String error) {
      return new SetupAuthority(false, Set.of(), java.util.Map.of(), java.util.Map.of(), error);
    }

    boolean authorizes(String repository, String command) {
      if (repository == null) return false;
      if (derivedCommandsByRepository.getOrDefault(repository, Set.of()).contains(command)) {
        return true;
      }
      for (SetupSource source : sources) {
        if (!source.repository().equals(repository)) continue;
        for (String line : source.content().split("\\R")) {
          String normalized = normalizePrompt(line.strip());
          if (normalized.equals(command)) return true;
        }
        if (source.isPackageJson() && packageScriptBodyMatches(source.content(), command)) {
          return true;
        }
      }
      return false;
    }

    boolean authorizesAnywhere(String command) {
      return scmByRepository.keySet().stream()
          .anyMatch(repository -> authorizes(repository, command));
    }

    String presentedRepository(String context) {
      if (scmByRepository.size() == 1) return scmByRepository.keySet().iterator().next();
      String relevant = context == null ? "" : context;
      Matcher heading = Pattern.compile("(?m)^#{2,6}\\s+").matcher(relevant);
      int lastHeading = -1;
      while (heading.find()) lastHeading = heading.start();
      if (lastHeading >= 0) relevant = relevant.substring(lastHeading);

      Set<String> mentioned = new LinkedHashSet<>();
      for (String repository : scmByRepository.keySet()) {
        Matcher mention = exactRepositoryPattern(repository).matcher(relevant);
        if (mention.find()) mentioned.add(repository);
      }
      return mentioned.size() == 1 ? mentioned.iterator().next() : null;
    }

    private static boolean packageScriptBodyMatches(String content, String command) {
      Matcher scripts = PACKAGE_SCRIPTS.matcher(content);
      if (!scripts.find()) return false;
      Matcher pair = JSON_STRING_PAIR.matcher(scripts.group(1));
      while (pair.find()) {
        if (pair.group(2).equals(command)) return true;
      }
      return false;
    }

    boolean matchesClone(String repository, String scm) {
      String normalized = repository.toLowerCase(Locale.ROOT);
      return scm.equals(scmByRepository.get(normalized));
    }
  }

  private record SetupSource(String repository, String path, String content) {
    boolean isPackageJson() {
      try {
        return java.nio.file.Path.of(path)
            .getFileName()
            .toString()
            .equalsIgnoreCase("package.json");
      } catch (RuntimeException invalidPath) {
        return false;
      }
    }
  }

  private record CommandSnippet(String command, String repository) {}

  private static int sectionBodyMeaningful(String normalized, String heading) {
    return (int)
        section(normalized, heading).chars().filter(c -> !Character.isWhitespace(c)).count();
  }

  private static String section(String normalized, String heading) {
    Matcher start = Pattern.compile("(?m)^" + Pattern.quote(heading) + "\\s*$").matcher(normalized);
    if (!start.find()) return "";
    int bodyStart = start.end();
    Matcher next = Pattern.compile("(?m)^#{1,2}\\s+").matcher(normalized);
    next.region(bodyStart, normalized.length());
    int bodyEnd = next.find() ? next.start() : normalized.length();
    return normalized.substring(bodyStart, bodyEnd);
  }

  private static boolean hasUnbalancedFence(String content) {
    String open = null;
    for (String line : content.split("\\R", -1)) {
      Matcher matcher = FENCE_LINE.matcher(line);
      if (!matcher.matches()) continue;
      String marker = matcher.group(1);
      String suffix = matcher.group(2).strip();
      if (open == null) {
        open = marker;
      } else if (marker.charAt(0) == open.charAt(0) && suffix.isEmpty()) {
        if (marker.length() != open.length()) return true;
        open = null;
      }
    }
    return open != null;
  }
}
