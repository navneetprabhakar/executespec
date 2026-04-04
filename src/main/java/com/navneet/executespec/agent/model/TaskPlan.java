package com.navneet.executespec.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TaskPlan {

    private String projectType;     // planner-declared: SPRING_BOOT, STATIC_HTML, NODE_FRONTEND, etc.
    private String uiStyle;         // planner-declared: "stripe", "vercel", "notion", etc. (nullable)
    private String planTitle;
    private String planSummary;
    private List<TaskDefinition> tasks;
}
