package com.navneet.executespec.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles LLM prompt files by resolving {@code {{include:fragment-name}}} markers
 * and substituting runtime variables. Fragment files live in
 * {@code classpath:prompts/fragments/} and provide a single source of truth for
 * shared rules that are used across multiple provider-specific prompt files.
 *
 * <p>Include syntax:
 * <ul>
 *   <li>{@code {{include:fragment-name}}} — loads {@code fragments/fragment-name.md}</li>
 *   <li>{@code {{include:fragment-name:role}}} — loads base fragment plus optional
 *       {@code fragments/fragment-name-role.md} appended after</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PromptAssembler {

    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("\\{\\{include:([a-z-]+)(?::([a-z-]+))?\\}\\}");

    private static final String PROMPTS_BASE = "classpath:prompts/";

    private final ResourceLoader resourceLoader;

    @Value("${prompts.base-path:classpath:prompts}")
    private String basePath;

    /**
     * Assemble a prompt file, resolving all include markers. No variable substitution.
     */
    public String assemble(String promptPath) {
        return assemble(promptPath, Map.of());
    }

    /**
     * Assemble a prompt file, resolving all include markers and then substituting
     * the provided variables (format: {@code {{KEY}}} → value).
     */
    public String assemble(String promptPath, Map<String, String> variables) {
        String template = loadFile(promptPath);
        template = resolveIncludes(template);
        for (var entry : variables.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    private String resolveIncludes(String template) {
        Matcher matcher = INCLUDE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String role = matcher.group(2);
            String fragment = loadFile("fragments/" + name + ".md");
            if (role != null) {
                String roleAddition = loadFileSafe("fragments/" + name + "-" + role + ".md");
                if (roleAddition != null) {
                    fragment = fragment + "\n\n" + roleAddition;
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(fragment));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Load a file relative to the prompts base path. Throws if not found.
     */
    public String loadFile(String rel) {
        Resource resource = resourceLoader.getResource(PROMPTS_BASE + rel);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load prompt file: prompts/" + rel, e);
        }
    }

    /**
     * Load a file relative to the prompts base path. Returns {@code null} if not found.
     */
    public String loadFileSafe(String rel) {
        try {
            return loadFile(rel);
        } catch (Exception e) {
            return null;
        }
    }
}
