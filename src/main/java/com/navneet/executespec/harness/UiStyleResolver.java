package com.navneet.executespec.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves UI style configuration for a run. Loads theme presets from
 * classpath:themes/*.json and detects design system / theme from the
 * user's spec or request text.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UiStyleResolver {

    private static final String DEFAULT_THEME = "minimal-light";
    private static final Set<String> VALID_THEMES = Set.of(
            "minimal-dark", "minimal-light", "corporate-clean", "startup-bold", "neutral-warm");
    private static final Set<String> VALID_DESIGN_SYSTEMS = Set.of("shadcn", "daisy", "tailwind-only");
    private static final Set<String> VALID_LAYOUTS = Set.of("sidebar", "top-nav", "minimal", "dashboard");

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, Object>> themePresets = new LinkedHashMap<>();

    @PostConstruct
    void loadThemes() {
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
     * framework, design_system, theme, density, layout, dark_mode
     */
    public String resolve(String userRequest, String explicitUiStyle) {
        String framework = "react";
        String designSystem = "shadcn";
        String theme = DEFAULT_THEME;
        String density = "comfortable";
        String layout = "top-nav";
        String darkMode = "auto";

        // Parse explicit ui_style block if provided (YAML-like key: value lines)
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

            // Framework detection
            if (lower.contains("html") || lower.contains("static site") || lower.contains("vanilla")) {
                framework = "html";
                designSystem = "tailwind-only";
            } else if (lower.contains("daisy") || lower.contains("daisyui")) {
                designSystem = "daisy";
            }

            // Theme heuristics
            if (lower.contains("dark mode") || lower.contains("dark theme")) {
                theme = "minimal-dark";
                darkMode = "true";
            } else if (lower.contains("corporate") || lower.contains("enterprise") || lower.contains("formal")) {
                theme = "corporate-clean";
            } else if (lower.contains("bold") || lower.contains("vibrant") || lower.contains("startup") || lower.contains("marketing")) {
                theme = "startup-bold";
            } else if (lower.contains("warm") || lower.contains("editorial") || lower.contains("content")) {
                theme = "neutral-warm";
            }

            // Layout heuristics
            if (lower.contains("dashboard")) {
                layout = "dashboard";
            } else if (lower.contains("sidebar")) {
                layout = "sidebar";
            } else if (lower.contains("landing") || lower.contains("minimal")) {
                layout = "minimal";
            }
        }

        // Validate
        if (!VALID_THEMES.contains(theme)) theme = DEFAULT_THEME;
        if (!VALID_DESIGN_SYSTEMS.contains(designSystem)) designSystem = "shadcn";
        if (!VALID_LAYOUTS.contains(layout)) layout = "top-nav";

        try {
            Map<String, String> uiStyle = new LinkedHashMap<>();
            uiStyle.put("framework", framework);
            uiStyle.put("design_system", designSystem);
            uiStyle.put("theme", theme);
            uiStyle.put("density", density);
            uiStyle.put("layout", layout);
            uiStyle.put("dark_mode", darkMode);
            return objectMapper.writeValueAsString(uiStyle);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Build the frontend constraint block to inject into worker task assignment
     * for frontend tasks. Returns null if the run has no ui_style.
     */
    public String buildFrontendConstraintBlock(String uiStyleJson) {
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

    private static String extractField(String text, String field, String defaultVal) {
        Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(field) + "\\s*:\\s*(.+?)\\s*$");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim().replaceAll("^['\"]|['\"]$", "");
        }
        return defaultVal;
    }
}
