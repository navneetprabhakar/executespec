package com.navneet.agentteam.optimiser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.optimiser")
@Data
public class PromptOptimiserProperties {

    private boolean enabled = true;

    /** Run every N completed runs */
    private int runInterval = 10;

    /** Number of recent runs to analyse for patterns */
    private int analysisWindowRuns = 20;

    /** Failure rate (0.0–1.0) that triggers early optimisation */
    private double failureRateThreshold = 0.20;

    /** Minimum occurrences of a pattern before it is acted on */
    private int patternMinOccurrences = 2;

    private String model = "claude-haiku-4-5-20251001";
    private String provider = "anthropic";
}
