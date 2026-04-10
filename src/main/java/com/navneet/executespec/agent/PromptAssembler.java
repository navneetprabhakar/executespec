package com.navneet.executespec.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
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
    private final PromptHashAuditRepository promptAuditRepository;

    /** Returned by {@link #assembleAndTrack}. */
    public record AssembledPrompt(String text, String hash) {}


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
     * Assemble and record a {@link PromptAudit} row for tracking.
     * Returns the assembled text together with its 12-char SHA-256 prefix hash.
     */
    public AssembledPrompt assembleAndTrack(String promptPath, Map<String, String> variables,
                                            UUID runId, String agentType) {
        String text = assemble(promptPath, variables);
        String hash = sha256Prefix(text, 12);
        try {
            promptAuditRepository.save(PromptAudit.builder()
                    .runId(runId != null ? runId.toString() : null)
                    .agentType(agentType)
                    .promptHash(hash)
                    .charCount(text.length())
                    .build());
        } catch (Exception e) {
            // Non-fatal — audit failure must never block execution
        }
        return new AssembledPrompt(text, hash);
    }

    private static String sha256Prefix(String input, int prefixLen) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, prefixLen);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
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
