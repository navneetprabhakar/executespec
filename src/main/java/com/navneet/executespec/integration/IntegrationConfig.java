package com.navneet.executespec.integration;

import com.navneet.executespec.integration.confluence.ConfluenceAdapter;
import com.navneet.executespec.integration.confluence.ConfluenceClient;
import com.navneet.executespec.integration.jira.JiraAdapter;
import com.navneet.executespec.integration.jira.JiraClient;
import com.navneet.executespec.integration.pagerduty.PagerDutyAdapter;
import com.navneet.executespec.integration.pagerduty.PagerDutyClient;
import com.navneet.executespec.integration.servicenow.ServiceNowAdapter;
import com.navneet.executespec.integration.servicenow.ServiceNowClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

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
}
