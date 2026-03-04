package com.navneet.agentteam.agent;

import com.navneet.agentteam.agent.TokenBudgetMonitor.BudgetStatus;
import com.navneet.agentteam.agent.TokenBudgetMonitor.Status;
import com.navneet.agentteam.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetMonitorTest {

    private TokenBudgetMonitor monitor;
    private AgentProperties props;

    @BeforeEach
    void setup() {
        props = new AgentProperties();
        // Use production defaults: low=2048, medium=4096, high=8192
        monitor = new TokenBudgetMonitor(props);
    }

    @Test
    void status_ok_whenBelowWarnThreshold() {
        // haiku/claude → 200k context; 0.64 * 200k = 128k tokens → below warn=0.65
        int tokens = (int)(0.64 * 200_000);
        BudgetStatus status = monitor.check("claude-haiku-4-5", "MEDIUM", tokens);
        assertThat(status.status()).isEqualTo(Status.OK);
        assertThat(status.contextLimit()).isEqualTo(200_000);
    }

    @Test
    void status_warn_whenAboveWarnBelowCompact() {
        // 0.70 * 200k = 140k → above warn(0.65) but below compact(0.75)
        int tokens = (int)(0.70 * 200_000);
        BudgetStatus status = monitor.check("claude-haiku-4-5", "MEDIUM", tokens);
        assertThat(status.status()).isEqualTo(Status.WARN);
    }

    @Test
    void status_compact_whenAboveCompactThreshold() {
        // 0.80 * 200k = 160k → above compact=0.75
        int tokens = (int)(0.80 * 200_000);
        BudgetStatus status = monitor.check("claude-haiku-4-5", "MEDIUM", tokens);
        assertThat(status.status()).isEqualTo(Status.COMPACT);
    }

    @Test
    void status_hardStop_whenAboveHardStopThreshold() {
        // 0.92 * 200k = 184k → above hardStop=0.90
        int tokens = (int)(0.92 * 200_000);
        BudgetStatus status = monitor.check("claude-haiku-4-5", "MEDIUM", tokens);
        assertThat(status.status()).isEqualTo(Status.HARD_STOP);
    }

    @Test
    void highComplexity_getsMoreHeadroom() {
        // At 0.78 usage: MEDIUM → COMPACT (0.78 >= 0.75), HIGH → WARN (0.78 < 0.80)
        int tokens = (int)(0.78 * 200_000);
        BudgetStatus mediumStatus = monitor.check("claude-haiku-4-5", "MEDIUM", tokens);
        BudgetStatus highStatus   = monitor.check("claude-haiku-4-5", "HIGH", tokens);
        assertThat(mediumStatus.status()).isEqualTo(Status.COMPACT);
        assertThat(highStatus.status()).isEqualTo(Status.WARN);
    }

    @Test
    void openaiModels_use128kContextLimit() {
        BudgetStatus status = monitor.check("gpt-4o-mini", "MEDIUM", 1_000);
        assertThat(status.contextLimit()).isEqualTo(128_000);
    }

    @Test
    void maxOutputTokens_returnsCorrectTierValues() {
        // Production defaults: low=2048, medium=4096, high=8192
        assertThat(monitor.maxOutputTokens("LOW")).isEqualTo(2048);
        assertThat(monitor.maxOutputTokens("MEDIUM")).isEqualTo(4096);
        assertThat(monitor.maxOutputTokens("HIGH")).isEqualTo(8192);
        assertThat(monitor.maxOutputTokens(null)).isEqualTo(4096); // null → MEDIUM
    }
}
