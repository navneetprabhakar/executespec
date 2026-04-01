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
