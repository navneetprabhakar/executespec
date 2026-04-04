package com.navneet.executespec.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "agent.integrations")
@Data
public class IntegrationProperties {

    /** Master switch — enables webhook endpoints and cleanup job. Individual adapters
     *  still require their own enabled: true flag below. */
    private boolean enabled = false;

    private JiraConfig jira = new JiraConfig();
    private ConfluenceConfig confluence = new ConfluenceConfig();
    private ServiceNowConfig servicenow = new ServiceNowConfig();
    private PagerDutyConfig pagerduty = new PagerDutyConfig();
    private SlackConfig slack = new SlackConfig();

    @Data
    public static class JiraConfig {
        private boolean enabled = false;
        /** @deprecated Use per-org credentials from org table (Settings UI). Kept for backward compat with env vars. */
        @Deprecated private String baseUrl;
        /** @deprecated Use per-org credentials from org table (Settings UI). */
        @Deprecated private String email;
        /** @deprecated Use per-org credentials from org table (Settings UI). */
        @Deprecated private String apiToken;
        private String webhookSecret;
        private String triggerStatus = "Ready for Coding";
        private String inProgressStatus;    // transition on run start (null = skip)
        private String inReviewStatus;      // transition on run complete (null = skip)
        private String reopenStatus;        // transition on run failed (null = skip)
        private String dashboardUrl = "http://localhost:8080";
        private boolean skipApproval = false;
    }

    @Data
    public static class ConfluenceConfig {
        private boolean enabled = false;
        /** @deprecated Use per-org credentials from org table (Settings UI). Kept for backward compat with env vars. */
        @Deprecated private String baseUrl;
        /** @deprecated Use per-org credentials from org table (Settings UI). */
        @Deprecated private String email;
        /** @deprecated Use per-org credentials from org table (Settings UI). */
        @Deprecated private String apiToken;
        private String triggerLabel = "agent-run";
        private String spaceKey = "ENG";
        private String dashboardUrl = "http://localhost:8080";
        private boolean skipApproval = false;
    }

    @Data
    public static class ServiceNowConfig {
        private boolean enabled = false;
        private String baseUrl;
        private String username;
        private String password;
        private String webhookSecret;
        private List<String> triggerCategories = List.of("application");
        private boolean autoResolve = false;
        private String dashboardUrl = "http://localhost:8080";
        private boolean skipApproval = false;
    }

    @Data
    public static class PagerDutyConfig {
        private boolean enabled = false;
        private String apiToken;
        private String webhookSecret;
        private String fromEmail;
        private List<String> triggerTypes = List.of("incident.trigger");
        private boolean autoResolve = false;
        private String dashboardUrl = "http://localhost:8080";
        private boolean skipApproval = false;
    }

    @Data
    public static class SlackConfig {
        private boolean enabled = false;
        private String clientId;
        private String clientSecret;
        private String signingSecret;
        private String redirectUri = "http://localhost:8080/api/integrations/slack/oauth/callback";
        private String dashboardUrl = "http://localhost:8080";
        private String botScopes = "chat:write,chat:write.public,channels:read,groups:read";
        private int maxRetries = 3;
        private int retryDelayMs = 1500;
    }
}
