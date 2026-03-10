package com.navneet.executespec.validator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Duration;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidateProjectRequest {
    private Path projectRoot;
    @Builder.Default
    private ValidationLevel level = ValidationLevel.FULL;
    @Builder.Default
    private Duration timeout = Duration.ofSeconds(300);
}
