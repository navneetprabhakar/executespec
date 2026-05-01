package com.navneet.executespec.contact;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "app.contact")
@Data
public class ContactProperties {

  private String fallbackEmail = "admin@executespec.ai";
  private JiraConfig jira = new JiraConfig();

  @Data
  public static class JiraConfig {
    private String baseUrl = "";
    private String email = "";
    private String apiToken = "";
    private String projectKey = "SUP";
    private String issueType = "Task";

    public boolean isConfigured() {
      return baseUrl != null
          && !baseUrl.isBlank()
          && email != null
          && !email.isBlank()
          && apiToken != null
          && !apiToken.isBlank();
    }
  }
}
