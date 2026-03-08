package com.navneet.agentteam.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelSelection {
    private ModelTier tier;
    private LlmProvider provider;
    private String modelName;   // exact model string for the API call, e.g. "claude-haiku-4-5-20251001"
}
