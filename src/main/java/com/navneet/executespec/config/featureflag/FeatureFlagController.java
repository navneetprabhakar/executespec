package com.navneet.executespec.config.featureflag;

import com.navneet.executespec.auth.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/feature-flags")
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping
    public ResponseEntity<List<FeatureFlagDto>> list() {
        List<FeatureFlagDto> dtos = featureFlagService.listAll().stream()
                .map(FeatureFlagDto::from)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * v2.5.8 — public read-only endpoint for the frontend to gate UI on feature
     * flags. Previously the frontend rendered "Configure" buttons on integrations
     * even when the backend flag was off, causing silent-failure form fills.
     *
     * <p>Returns only boolean flags with public-safe keys (integrations.*,
     * billing.*, pipeline.*). Admin-only flags are excluded. Values are exposed
     * as {@code key → boolean}; the frontend checks {@code flags["integrations.jira.enabled"]}
     * before offering the Configure CTA.
     */
    @GetMapping("/public")
    public ResponseEntity<Map<String, Boolean>> listPublic(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        if (userDetails == null) return ResponseEntity.status(401).build();
        java.util.Map<String, Boolean> result = new java.util.LinkedHashMap<>();
        for (FeatureFlag f : featureFlagService.listAll()) {
            if (f.getValueType() != FeatureFlag.ValueType.BOOLEAN) continue;
            String key = f.getFlagKey();
            // Whitelist prefixes that are safe to expose to any authenticated user.
            if (key.startsWith("integrations.") || key.startsWith("billing.")
                    || key.startsWith("pipeline.") || key.startsWith("execution.view.")
                    || key.startsWith("project.")) {
                result.put(key, Boolean.parseBoolean(f.getFlagValue()));
            }
        }
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{key}")
    public ResponseEntity<FeatureFlagDto> update(
            @PathVariable String key,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().build();
        }
        FeatureFlag updated = featureFlagService.update(key, value, userDetails.getUser().getId());
        return ResponseEntity.ok(FeatureFlagDto.from(updated));
    }

    // ── DTO ──────────────────────────────────────────────────────────────────

    public record FeatureFlagDto(
            Long id,
            String flagKey,
            String flagValue,
            String valueType,
            String scope,
            String description,
            String updatedBy,
            String updatedAt
    ) {
        static FeatureFlagDto from(FeatureFlag f) {
            return new FeatureFlagDto(
                    f.getId(),
                    f.getFlagKey(),
                    f.getFlagValue(),
                    f.getValueType().name(),
                    f.getScope().name(),
                    f.getDescription(),
                    f.getUpdatedBy(),
                    f.getUpdatedAt() != null ? f.getUpdatedAt().toString() : null
            );
        }
    }
}
