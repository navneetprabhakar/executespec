package com.navneet.agentteam.validator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateResult {
    private ValidationGate gate;
    private boolean passed;
    private Duration duration;
    private List<ValidationError> errors;
    private String rawOutput;  // raw stdout+stderr from the tool
}
