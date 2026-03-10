package com.navneet.executespec.integration;

import com.navneet.executespec.integration.confluence.ConfluenceAdapter;
import com.navneet.executespec.integration.confluence.ConfluenceClient;
import com.navneet.executespec.integration.jira.JiraAdapter;
import com.navneet.executespec.integration.jira.JiraClient;
import com.navneet.executespec.integration.pagerduty.PagerDutyAdapter;
import com.navneet.executespec.integration.pagerduty.PagerDutyClient;
import com.navneet.executespec.integration.servicenow.ServiceNowAdapter;
import com.navneet.executespec.integration.servicenow.ServiceNowClient;
import com.navneet.executespec.integration.slack.SlackClient;
import com.navneet.executespec.integration.slack.SlackMessageBuilder;
import com.navneet.executespec.integration.slack.SlackOAuthService;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegrationRepository;
import com.navneet.executespec.integration.slack.model.ProjectSlackChannelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfig {

    @Bean
    public RestTemplate integrationRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ── Jira ──────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty("agent.integrations.jira.enabled")
    public JiraClient jiraClient(RestTemplate integrationRestTemplate, IntegrationProperties props) {
        return new JiraClient(integrationRestTemplate, props.getJira());
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.jira.enabled")
    public JiraAdapter jiraAdapter(JiraClient jiraClient, IntegrationProperties props, ObjectMapper mapper) {
        return new JiraAdapter(jiraClient, props.getJira(), mapper);
    }

    // ── Confluence ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty("agent.integrations.confluence.enabled")
    public ConfluenceClient confluenceClient(RestTemplate integrationRestTemplate, IntegrationProperties props) {
        return new ConfluenceClient(integrationRestTemplate, props.getConfluence());
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.confluence.enabled")
    public ConfluenceAdapter confluenceAdapter(ConfluenceClient confluenceClient, IntegrationProperties props, ObjectMapper mapper) {
        return new ConfluenceAdapter(confluenceClient, props.getConfluence(), mapper);
    }

    // ── ServiceNow ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty("agent.integrations.servicenow.enabled")
    public ServiceNowClient serviceNowClient(RestTemplate integrationRestTemplate, IntegrationProperties props) {
        return new ServiceNowClient(integrationRestTemplate, props.getServicenow());
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.servicenow.enabled")
    public ServiceNowAdapter serviceNowAdapter(ServiceNowClient serviceNowClient, IntegrationProperties props, ObjectMapper mapper) {
        return new ServiceNowAdapter(serviceNowClient, props.getServicenow(), mapper);
    }

    // ── PagerDuty ─────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty("agent.integrations.pagerduty.enabled")
    public PagerDutyClient pagerDutyClient(RestTemplate integrationRestTemplate, IntegrationProperties props) {
        return new PagerDutyClient(integrationRestTemplate, props.getPagerduty());
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.pagerduty.enabled")
    public PagerDutyAdapter pagerDutyAdapter(PagerDutyClient pagerDutyClient, IntegrationProperties props, ObjectMapper mapper) {
        return new PagerDutyAdapter(pagerDutyClient, props.getPagerduty(), mapper);
    }

    // ── Slack ──────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty("agent.integrations.slack.enabled")
    public SlackClient slackClient(RestTemplate integrationRestTemplate) {
        return new SlackClient(integrationRestTemplate);
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.slack.enabled")
    public SlackMessageBuilder slackMessageBuilder(IntegrationProperties props) {
        return new SlackMessageBuilder(props.getSlack().getDashboardUrl());
    }

    @Bean
    @ConditionalOnProperty("agent.integrations.slack.enabled")
    public SlackOAuthService slackOAuthService(
            SlackClient slackClient,
            OrgSlackIntegrationRepository integrationRepo,
            ProjectSlackChannelRepository channelRepo,
            IntegrationProperties props,
            @Value("${app.jwt.secret:executespec-default-secret}") String jwtSecret) {
        return new SlackOAuthService(slackClient, integrationRepo, channelRepo,
                props.getSlack(), jwtSecret);
    }

    @Bean(name = "slackNotificationExecutor")
    @ConditionalOnProperty("agent.integrations.slack.enabled")
    public TaskExecutor slackNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("slack-notif-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
