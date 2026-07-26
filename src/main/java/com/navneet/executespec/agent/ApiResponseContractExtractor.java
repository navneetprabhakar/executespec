package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects response-schema fields for one endpoint without leaking sibling resource schemas. */
final class ApiResponseContractExtractor {
  private static final Pattern RESPONSE_SCHEMA =
      Pattern.compile(
          "(?im)^\\s*[-*]?\\s*`?([a-zA-Z0-9_]*(?:Response|DTO|Dto))`?\\s*:\\s*([^\\n]+)$");
  private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\??");

  private ApiResponseContractExtractor() {}

  static boolean isCompactLowercaseHelperSyntax(Matcher matcher) {
    String method = matcher.group(1);
    return method.equals(method.toLowerCase(Locale.ROOT)) && matcher.start(2) - matcher.end(1) == 1;
  }

  static String optionalItemSuffix(String evidence, int endpointEnd) {
    if (endpointEnd >= evidence.length()) return "";
    Matcher suffix =
        Pattern.compile("^\\s*\\[/\\{([a-z_][a-z0-9_]*)}]")
            .matcher(evidence.substring(endpointEnd));
    return suffix.find() ? "/{" + suffix.group(1) + "}" : "";
  }

  static Set<String> responseFields(String evidence, Set<String> endpointDomainTokens) {
    List<NamedFields> schemas = new ArrayList<>();
    Matcher schemaMatcher = RESPONSE_SCHEMA.matcher(safe(evidence));
    while (schemaMatcher.find()) {
      LinkedHashSet<String> fields = new LinkedHashSet<>();
      for (String fragment : schemaMatcher.group(2).split(",")) {
        Matcher identifier = IDENTIFIER.matcher(fragment.trim());
        if (identifier.find()) fields.add(identifier.group(1));
      }
      schemas.add(
          new NamedFields(schemaMatcher.group(1).toLowerCase(Locale.ROOT), Set.copyOf(fields)));
    }
    if (schemas.isEmpty()) return Set.of();
    List<NamedFields> matching =
        schemas.stream()
            .filter(
                schema ->
                    endpointDomainTokens.stream()
                        .anyMatch(token -> schema.name().startsWith(token)))
            .toList();
    if (matching.size() == 1) return matching.getFirst().fields();
    return schemas.size() == 1 ? schemas.getFirst().fields() : Set.of();
  }

  private record NamedFields(String name, Set<String> fields) {}
}
