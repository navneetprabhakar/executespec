package com.navneet.executespec.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves UI style configuration for a run. Loads design system DESIGN.md files
 * from a filesystem-backed design repository, with Caffeine caching, fuzzy matching,
 * and token-budget truncation.
 *
 * <p>Also loads legacy theme presets from classpath:themes/*.json as fallback.
 *
 * <p>3-signal resolution priority:
 * <ol>
 *   <li>Explicit style hint from user request text ("like Stripe", "Notion-inspired")</li>
 *   <li>Planner-declared {@code ui_style} from plan JSON</li>
 *   <li>Project type heuristic (frontend projects → default style)</li>
 * </ol>
 */
@Service
@Slf4j
@EnableConfigurationProperties(DesignSystemProperties.class)
public class UiStyleResolver {

    private static final String DEFAULT_THEME = "vercel-geist";
    private static final Set<String> VALID_THEMES = Set.of(
            "vercel-geist", "minimal-dark", "minimal-light", "corporate-clean", "startup-bold", "neutral-warm");
    private static final Set<String> VALID_DESIGN_SYSTEMS = Set.of("shadcn", "daisy", "tailwind-only");
    private static final Set<String> VALID_LAYOUTS = Set.of("sidebar", "top-nav", "minimal", "dashboard");

    private static final Set<String> BACKEND_ONLY_TYPES = Set.of(
            "SPRING_BOOT", "JAVA", "PYTHON", "GO", "RUST", "NODE_BACKEND");

    /** Patterns to match style hints in user request text. */
    private static final Pattern STYLE_HINT_PATTERN = Pattern.compile(
            "(?i)(?:looks?\\s+like|styled?\\s+(?:like|after)|inspired\\s+by|"
                    + "use\\s+(?:\\w+'?s?\\s+)?design|(?:like|follow)\\s+(?:the\\s+)?)"
                    + "\\s*([a-zA-Z][a-zA-Z0-9._-]*)");

    private final ObjectMapper objectMapper;
    private final DesignSystemProperties designProps;
    private final Map<String, Map<String, Object>> themePresets = new LinkedHashMap<>();

    /** Available design directory names (populated at startup). */
    private Set<String> availableDesigns = Set.of();

    /** Caffeine cache: designName → truncated DESIGN.md content. */
    private Cache<String, String> designCache;

    public UiStyleResolver(ObjectMapper objectMapper, DesignSystemProperties designProps) {
        this.objectMapper = objectMapper;
        this.designProps = designProps;
    }

    @PostConstruct
    void init() {
        loadThemes();
        scanDesignDirectories();
        designCache = Caffeine.newBuilder()
                .maximumSize(60)
                .expireAfterWrite(designProps.getCacheTtl())
                .build();
    }

    private void loadThemes() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:themes/*.json");
            for (Resource r : resources) {
                Map<String, Object> theme = objectMapper.readValue(
                        r.getInputStream(), new TypeReference<>() {});
                String id = (String) theme.get("id");
                if (id != null) {
                    themePresets.put(id, theme);
                    log.info("Loaded theme preset: {}", id);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load theme presets: {}", e.getMessage());
        }
    }

    private void scanDesignDirectories() {
        Path rootPath = Path.of(designProps.getRootPath());
        if (!Files.isDirectory(rootPath)) {
            log.warn("[DesignSystem] Root path does not exist: {} — design system disabled", rootPath);
            availableDesigns = Set.of();
            return;
        }
        try (Stream<Path> dirs = Files.list(rootPath)) {
            Set<String> designs = new LinkedHashSet<>();
            dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("DESIGN.md")))
                    .forEach(d -> designs.add(d.getFileName().toString()));
            availableDesigns = Collections.unmodifiableSet(designs);
            log.info("[DesignSystem] Loaded {} design systems from {}", designs.size(), rootPath);
        } catch (IOException e) {
            log.warn("[DesignSystem] Failed to scan design directories: {}", e.getMessage());
            availableDesigns = Set.of();
        }
    }

    // ── Design Content Loading ──────────────────────────────────────────────

    /**
     * Load a DESIGN.md by design name. Returns truncated content, or null if not found.
     * Results are cached with TTL from config.
     */
    public String loadDesignContent(String designName) {
        if (designName == null || designName.isBlank()) return null;

        String normalized = normalize(designName);
        String cached = designCache.getIfPresent(normalized);
        if (cached != null) return cached;

        // Direct path lookup
        Path designPath = Path.of(designProps.getRootPath(), normalized, "DESIGN.md");

        // Fuzzy match if direct path doesn't exist
        if (!Files.exists(designPath)) {
            String fuzzy = fuzzyMatchDesign(normalized);
            if (fuzzy != null) {
                designPath = Path.of(designProps.getRootPath(), fuzzy, "DESIGN.md");
                normalized = fuzzy; // cache under canonical name
            }
        }

        // Fall back to default
        if (!Files.exists(designPath)) {
            log.warn("[DesignSystem] Design '{}' not found, falling back to '{}'",
                    designName, designProps.getDefaultStyle());
            designPath = Path.of(designProps.getRootPath(), designProps.getDefaultStyle(), "DESIGN.md");
            normalized = designProps.getDefaultStyle();
        }

        if (!Files.exists(designPath)) {
            log.error("[DesignSystem] Default design '{}' not found at {}",
                    designProps.getDefaultStyle(), designPath);
            return null;
        }

        try {
            String content = Files.readString(designPath, StandardCharsets.UTF_8);
            content = enforceTokenLimit(content);
            designCache.put(normalized, content);
            log.debug("[DesignSystem] Loaded design '{}' ({} chars)", normalized, content.length());
            return content;
        } catch (IOException e) {
            log.error("[DesignSystem] Failed to read design file: {}", designPath, e);
            return null;
        }
    }

    /**
     * Fuzzy match a style hint against available design directory names.
     * Tries: exact → contains → base name (strip .app/.ai suffix) → prefix match.
     */
    public String fuzzyMatchDesign(String hint) {
        if (hint == null || hint.isBlank()) return null;
        String lower = hint.toLowerCase().trim();

        // Exact match
        if (availableDesigns.contains(lower)) return lower;

        // Contains match (longest match first for specificity)
        List<String> sorted = new ArrayList<>(availableDesigns);
        sorted.sort(Comparator.comparingInt(String::length).reversed());

        for (String design : sorted) {
            // Design contains hint or hint contains design base
            String base = design.replace(".app", "").replace(".ai", "").replace(".com", "");
            if (design.contains(lower) || lower.contains(base)) {
                return design;
            }
        }

        // Prefix match
        for (String design : sorted) {
            if (design.startsWith(lower) || lower.startsWith(design)) {
                return design;
            }
        }

        return null;
    }

    // ── 3-Signal Resolution ─────────────────────────────────────────────────

    /**
     * Resolve the design system name from 3 signals (priority order):
     * <ol>
     *   <li>explicitHint — parsed from user request text</li>
     *   <li>plannerStyle — ui_style field from TaskPlan</li>
     *   <li>projectType — heuristic (frontend projects get default style)</li>
     * </ol>
     * Returns null for backend-only projects.
     */
    public String resolveDesignName(String explicitHint, String plannerStyle, String projectType) {
        if (isBackendOnly(projectType)) return null;

        // Signal 1: explicit user hint
        if (explicitHint != null && !explicitHint.isBlank()) {
            String matched = fuzzyMatchDesign(explicitHint);
            if (matched != null) return matched;
        }

        // Signal 2: planner-declared ui_style
        if (plannerStyle != null && !plannerStyle.isBlank()) {
            String matched = fuzzyMatchDesign(plannerStyle);
            if (matched != null) return matched;
        }

        // Signal 3: project type heuristic → default style for frontend projects
        return defaultForProjectType(projectType);
    }

    /**
     * Extract a design style hint from user request text.
     * Matches patterns like "looks like Stripe", "Notion-inspired", "use Linear's design".
     * Also does a direct scan of request text against available design names.
     */
    public String extractStyleHint(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return null;
        String lower = userRequest.toLowerCase();

        // Scan available design names directly (longest first for "linear.app" before "linear")
        List<String> sorted = new ArrayList<>(availableDesigns);
        sorted.sort(Comparator.comparingInt(String::length).reversed());

        for (String design : sorted) {
            String base = design.replace(".app", "").replace(".ai", "").replace(".com", "");
            if (lower.contains(design) || lower.contains(base)) {
                return design;
            }
        }

        // Try regex pattern matching
        Matcher m = STYLE_HINT_PATTERN.matcher(userRequest);
        if (m.find()) {
            String candidate = m.group(1).toLowerCase();
            return fuzzyMatchDesign(candidate);
        }

        return null;
    }

    // ── Token Truncation ────────────────────────────────────────────────────

    /**
     * Truncate DESIGN.md to fit within token budget.
     * Keeps sections 1-5 (theme, colors, typography, components, layout).
     * Drops sections 6-9 (elevation, do/don't, responsive, agent guide) if over budget.
     */
    String enforceTokenLimit(String content) {
        int estimatedTokens = content.length() / 4;
        if (estimatedTokens <= designProps.getMaxTokens()) return content;

        int charLimit = designProps.getMaxTokens() * 4;
        String[] lines = content.split("\n");
        StringBuilder trimmed = new StringBuilder();
        int sectionCount = 0;

        for (String line : lines) {
            if (line.startsWith("## ")) sectionCount++;
            if (trimmed.length() + line.length() > charLimit && sectionCount > 4) {
                trimmed.append("\n<!-- Truncated: remaining sections omitted to fit token budget -->\n");
                break;
            }
            trimmed.append(line).append("\n");
        }
        return trimmed.toString();
    }

    // ── Frontend Constraint Block ───────────────────────────────────────────

    /**
     * Build the frontend constraint block to inject into worker task assignment.
     * Uses loaded DESIGN.md content when available, falls back to legacy theme JSON.
     */
    public String buildFrontendConstraintBlock(String uiStyleJson, String designContent) {
        if (designContent != null && !designContent.isBlank()) {
            return buildDesignSystemBlock(designContent);
        }
        // Legacy fallback: use theme JSON presets
        return buildLegacyConstraintBlock(uiStyleJson);
    }

    /**
     * Overload for backward compatibility — delegates to design-aware version with null design.
     */
    public String buildFrontendConstraintBlock(String uiStyleJson) {
        return buildFrontendConstraintBlock(uiStyleJson, null);
    }

    private String buildDesignSystemBlock(String designContent) {
        return """
                ═══ FRONTEND DESIGN SYSTEM (AUTHORITATIVE) ═══

                %s

                RULES (NON-NEGOTIABLE):
                - This design system is AUTHORITATIVE for all visual decisions in this task.
                - Do NOT import Tailwind, Pico.css, Bootstrap, Material UI, or any external CSS framework via CDN or npm.
                - All colors, typography, spacing, and component styling MUST use the tokens and patterns defined above.
                - If the design system defines CSS custom properties, use them. Do not hardcode hex values.
                - If a component you need is not defined above, extrapolate from the existing patterns.
                - Prefer inline <style> blocks with CSS custom properties over utility classes.
                - Match the design system's visual language exactly — spacing rhythm, border styles, color roles.

                ═══ END DESIGN SYSTEM ═══
                """.formatted(designContent);
    }

    private String buildLegacyConstraintBlock(String uiStyleJson) {
        if (uiStyleJson == null || uiStyleJson.isBlank() || "{}".equals(uiStyleJson)) return null;

        try {
            Map<String, String> style = objectMapper.readValue(uiStyleJson, new TypeReference<>() {});
            String ds = style.getOrDefault("design_system", "shadcn");
            String themeId = style.getOrDefault("theme", DEFAULT_THEME);
            String density = style.getOrDefault("density", "comfortable");
            String layout = style.getOrDefault("layout", "top-nav");
            String darkMode = style.getOrDefault("dark_mode", "auto");

            String themeJson = getThemeJson(themeId);

            String dsLabel;
            switch (ds) {
                case "shadcn" -> dsLabel = "shadcn/ui (React components with Tailwind CSS)";
                case "daisy" -> dsLabel = "DaisyUI (Tailwind CSS component library)";
                default -> dsLabel = "Tailwind CSS utility classes only (no component library)";
            }

            return """
                    ═══ FRONTEND GENERATION RULES (NON-NEGOTIABLE) ═══

                    DESIGN SYSTEM: %s
                    - Use ONLY components from the design system above.
                    - Do NOT write custom CSS. Use Tailwind utility classes exclusively.
                    - Do NOT use inline styles.
                    - Import components from the design system library, do not recreate them.

                    THEME: %s
                    - Apply the theme configuration below exactly. Do not invent your own colors.
                    - Density: %s
                    - Layout: %s
                    - Dark mode: %s

                    THEME CONFIGURATION:
                    %s

                    ═══ END FRONTEND RULES ═══
                    """.formatted(dsLabel, themeId, density, layout, darkMode, themeJson);
        } catch (Exception e) {
            log.warn("Failed to build frontend constraint block: {}", e.getMessage());
            return null;
        }
    }

    // ── Legacy API (backward compat) ────────────────────────────────────────

    /**
     * Returns the full theme JSON for a given theme ID.
     */
    public String getThemeJson(String themeId) {
        Map<String, Object> theme = themePresets.get(themeId);
        if (theme == null) theme = themePresets.get(DEFAULT_THEME);
        if (theme == null) return "{}";
        try {
            return objectMapper.writeValueAsString(theme);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Resolve a complete ui_style JSON string from the user request and optional
     * explicit ui_style spec text. Returns a JSON object with keys:
     * framework, design_system, theme, density, layout, dark_mode, design_name
     */
    public String resolve(String userRequest, String explicitUiStyle) {
        String framework = "react";
        String designSystem = "shadcn";
        String theme = DEFAULT_THEME;
        String density = "comfortable";
        String layout = "top-nav";
        String darkMode = "true";

        // Parse explicit ui_style block if provided
        if (explicitUiStyle != null && !explicitUiStyle.isBlank()) {
            framework = extractField(explicitUiStyle, "framework", framework);
            designSystem = extractField(explicitUiStyle, "design_system", designSystem);
            theme = extractField(explicitUiStyle, "theme", theme);
            density = extractField(explicitUiStyle, "density", density);
            layout = extractField(explicitUiStyle, "layout", layout);
            darkMode = extractField(explicitUiStyle, "dark_mode", darkMode);
        }

        // Heuristic detection from user request when explicit style is absent
        if (explicitUiStyle == null || explicitUiStyle.isBlank()) {
            String lower = (userRequest != null ? userRequest : "").toLowerCase();

            if (lower.contains("html") || lower.contains("static site") || lower.contains("vanilla")) {
                framework = "html";
                designSystem = "tailwind-only";
            } else if (lower.contains("daisy") || lower.contains("daisyui")) {
                designSystem = "daisy";
            }

            if (lower.contains("dark mode") || lower.contains("dark theme")) {
                theme = "minimal-dark"; darkMode = "true";
            } else if (lower.contains("corporate") || lower.contains("enterprise") || lower.contains("formal")) {
                theme = "corporate-clean";
            } else if (lower.contains("bold") || lower.contains("vibrant") || lower.contains("startup") || lower.contains("marketing")) {
                theme = "startup-bold";
            } else if (lower.contains("warm") || lower.contains("editorial") || lower.contains("content")) {
                theme = "neutral-warm";
            }

            if (lower.contains("dashboard")) { layout = "dashboard"; }
            else if (lower.contains("sidebar")) { layout = "sidebar"; }
            else if (lower.contains("landing") || lower.contains("minimal")) { layout = "minimal"; }
        }

        if (!VALID_THEMES.contains(theme)) theme = DEFAULT_THEME;
        if (!VALID_DESIGN_SYSTEMS.contains(designSystem)) designSystem = "shadcn";
        if (!VALID_LAYOUTS.contains(layout)) layout = "top-nav";

        // Resolve design name from user request
        String designName = extractStyleHint(userRequest);
        if (designName == null) designName = designProps.getDefaultStyle();

        try {
            Map<String, String> uiStyle = new LinkedHashMap<>();
            uiStyle.put("framework", framework);
            uiStyle.put("design_system", designSystem);
            uiStyle.put("theme", theme);
            uiStyle.put("density", density);
            uiStyle.put("layout", layout);
            uiStyle.put("dark_mode", darkMode);
            uiStyle.put("design_name", designName);
            return objectMapper.writeValueAsString(uiStyle);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Enrich an existing ui_style JSON with a design_name key. */
    public String enrichWithDesignName(String uiStyleJson, String designName) {
        if (uiStyleJson == null || uiStyleJson.isBlank()) return uiStyleJson;
        try {
            Map<String, String> style = objectMapper.readValue(uiStyleJson, new TypeReference<>() {});
            style.put("design_name", designName != null ? designName : designProps.getDefaultStyle());
            return objectMapper.writeValueAsString(style);
        } catch (Exception e) {
            return uiStyleJson;
        }
    }

    /** Get the list of curated styles for display. */
    public List<String> getCuratedStyles() {
        return designProps.getCuratedStyles();
    }

    /** Get all available design directory names. */
    public Set<String> getAvailableDesigns() {
        return availableDesigns;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isBackendOnly(String projectType) {
        if (projectType == null) return false;
        return BACKEND_ONLY_TYPES.contains(projectType);
    }

    private String defaultForProjectType(String projectType) {
        if (projectType == null) return designProps.getDefaultStyle();
        return switch (projectType) {
            case "NODE_FRONTEND", "STATIC_HTML", "FULL_STACK" -> designProps.getDefaultStyle();
            default -> null;
        };
    }

    private String normalize(String input) {
        return input.trim().toLowerCase()
                .replace(" ", "-")
                .replaceAll("[^a-z0-9.\\-]", "");
    }

    private static String extractField(String text, String field, String defaultVal) {
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(field) + "\\s*:\\s*(.+?)\\s*$");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return defaultVal;
    }
}
