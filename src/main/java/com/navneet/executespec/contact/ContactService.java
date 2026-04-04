package com.navneet.executespec.contact;

import com.navneet.executespec.auth.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContactService {

    private final ContactProperties contactProperties;
    private final MailService mailService;
    private final RestTemplate restTemplate = new RestTemplate();

    public void submitFeedback(String userEmail, String userName, String subject, String body) {
        ContactProperties.JiraConfig jira = contactProperties.getJira();

        if (jira.isConfigured()) {
            try {
                createJiraIssue(jira, userEmail, userName, subject, body);
                log.info("Contact feedback JIRA ticket created — from: {}, subject: {}", userEmail, subject);
                return;
            } catch (Exception e) {
                log.warn("JIRA ticket creation failed, falling back to email — from: {}, error: {}",
                        userEmail, e.getMessage());
            }
        }

        mailService.sendContactFeedbackEmail(
                contactProperties.getFallbackEmail(), userEmail, userName, subject, body);
        log.info("Contact feedback email sent — from: {}, subject: {}", userEmail, subject);
    }

    private void createJiraIssue(ContactProperties.JiraConfig jira,
                                  String userEmail, String userName,
                                  String subject, String body) {
        String credentials = Base64.getEncoder().encodeToString(
                (jira.getEmail() + ":" + jira.getApiToken()).getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + credentials);

        // ADF v1 body format for JIRA Cloud REST API v3
        Map<String, Object> payload = Map.of(
                "fields", Map.of(
                        "project", Map.of("key", jira.getProjectKey()),
                        "issuetype", Map.of("name", jira.getIssueType()),
                        "summary", "[Contact] " + subject,
                        "description", Map.of(
                                "version", 1,
                                "type", "doc",
                                "content", List.of(
                                        Map.of("type", "paragraph", "content", List.of(
                                                Map.of("type", "text", "text",
                                                        "From: " + userName + " <" + userEmail + ">")
                                        )),
                                        Map.of("type", "paragraph", "content", List.of(
                                                Map.of("type", "text", "text", body)
                                        ))
                                )
                        )
                )
        );

        String url = jira.getBaseUrl().replaceAll("/+$", "") + "/rest/api/3/issue";
        restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
    }
}
