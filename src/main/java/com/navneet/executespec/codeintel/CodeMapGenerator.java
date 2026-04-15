package com.navneet.executespec.codeintel;

import com.navneet.executespec.repo.GitHubRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pilot R3 — produces a markdown "code map" for one repo by scanning its
 * file listing (via GitHub Trees API) and a few key files (routes, entities,
 * components). No shallow clone — keeps generation cheap enough to be a
 * synchronous step on the dashboard regenerate button.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeMapGenerator {

    private final GitHubRepoService github;

    private static final Pattern SPRING_ROUTE = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern EXPRESS_ROUTE = Pattern.compile(
            "(?:app|router)\\.(?:get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern FASTAPI_ROUTE = Pattern.compile(
            "@(?:app|router)\\.(?:get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    public record Result(String markdown, String sha) {}

    public Result generateWithSha(String repoFullName, String defaultBranch, String pat) {
        String sha = github.getBranchHeadSha("https://github.com/" + repoFullName, defaultBranch, pat);
        return new Result(generate(repoFullName, defaultBranch, pat), sha);
    }

    public String generate(String repoFullName, String pat) {
        return generate(repoFullName, null, pat);
    }

    public String generate(String repoFullName, String defaultBranch, String pat) {
        List<String> files;
        try {
            files = github.listRepoFiles("https://github.com/" + repoFullName, defaultBranch, pat);
        } catch (Exception e) {
            log.warn("CodeMapGenerator list-files failed for {}: {}", repoFullName, e.getMessage());
            files = List.of();
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(repoFullName).append("\n\n");
        md.append("**Files**: ").append(files.size()).append("\n\n");

        // Surface major directories
        md.append("## Top-level layout\n\n");
        files.stream()
                .map(p -> p.contains("/") ? p.substring(0, p.indexOf('/')) : p)
                .distinct()
                .sorted()
                .limit(20)
                .forEach(d -> md.append("- ").append(d).append("\n"));
        md.append("\n");

        // Routes — scan a small set of likely files; full-text scan would be too expensive.
        List<String> routes = scanRoutes(repoFullName, defaultBranch, files, pat);
        if (!routes.isEmpty()) {
            md.append("## Routes\n\n");
            routes.stream().limit(40).forEach(r -> md.append("- `").append(r).append("`\n"));
            if (routes.size() > 40) md.append("- ... (+").append(routes.size() - 40).append(" more)\n");
            md.append("\n");
        }

        // Entities / models — best-effort by filename heuristic
        List<String> models = files.stream()
                .filter(p -> p.matches(".*/(?:models?|entit(?:y|ies)|schemas?)/[^/]+\\.(?:java|py|ts|js|go|rs)$"))
                .limit(40)
                .toList();
        if (!models.isEmpty()) {
            md.append("## Models / entities\n\n");
            models.forEach(m -> md.append("- ").append(m).append("\n"));
            md.append("\n");
        }

        return md.toString();
    }

    private List<String> scanRoutes(String repoFullName, String branch, List<String> files, String pat) {
        List<String> out = new ArrayList<>();
        // Pull at most 12 plausible route files to keep API calls bounded.
        files.stream()
                .filter(p -> p.endsWith(".java") || p.endsWith(".py") || p.endsWith(".ts") || p.endsWith(".js"))
                .filter(p -> p.toLowerCase().matches(".*(controller|route|router|api).*"))
                .limit(12)
                .forEach(path -> {
                    String content;
                    try {
                        content = github.readRepoFile(
                                "https://github.com/" + repoFullName, path, branch, pat);
                    } catch (Exception e) {
                        return;
                    }
                    if (content == null || content.isBlank()) return;
                    extract(SPRING_ROUTE, content, out);
                    extract(EXPRESS_ROUTE, content, out);
                    extract(FASTAPI_ROUTE, content, out);
                });
        return out.stream().distinct().sorted().toList();
    }

    private static void extract(Pattern p, String content, List<String> sink) {
        Matcher m = p.matcher(content);
        while (m.find()) sink.add(m.group(1));
    }
}
