package com.navneet.executespec.harness;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the filesystem-backed design system.
 * Design files are loaded from {@code rootPath}/{styleName}/DESIGN.md.
 */
@Data
@ConfigurationProperties(prefix = "executespec.designs")
public class DesignSystemProperties {

    /** Root directory containing design subdirectories (each with a DESIGN.md). */
    private String rootPath = "/opt/executespec/designs/design-md";

    /** Cache TTL for loaded design content. */
    private Duration cacheTtl = Duration.ofMinutes(30);

    /** Max estimated token count for truncated design content (chars/4). */
    private int maxTokens = 2500;

    /** Default design style for frontend projects when no style is specified. */
    private String defaultStyle = "vercel";

    /** Curated list of design styles shown to planners and users. */
    private List<String> curatedStyles = List.of(
            "airbnb", "claude", "figma", "linear.app", "notion",
            "stripe", "supabase", "vercel", "spotify", "posthog",
            "resend", "mintlify"
    );
}
