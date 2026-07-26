package com.navneet.executespec.config;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = CliCompatibilityHeaderFilterSecurityIntegrationTest.TestApplication.class,
    properties = "executespec.client-compatibility.minimum-cli-version=2.3.4")
@AutoConfigureMockMvc
class CliCompatibilityHeaderFilterSecurityIntegrationTest {

  private final MockMvc mockMvc;

  @Autowired
  CliCompatibilityHeaderFilterSecurityIntegrationTest(MockMvc mockMvc) {
    this.mockMvc = mockMvc;
  }

  @Test
  void configuredFloorSurvivesUnauthenticatedSecurityResponse() throws Exception {
    mockMvc
        .perform(get("/api/private-probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(CliCompatibilityHeaderFilter.MINIMUM_VERSION_HEADER, "2.3.4"));
  }

  @Test
  void staticNonApiResponseDoesNotAdvertiseCliFloor() throws Exception {
    mockMvc
        .perform(get("/assets/not-present.js"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(CliCompatibilityHeaderFilter.MINIMUM_VERSION_HEADER));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        AnthropicChatAutoConfiguration.class,
        GoogleGenAiChatAutoConfiguration.class,
        GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
        GoogleGenAiTextEmbeddingAutoConfiguration.class,
        OpenAiChatAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
      })
  @Import({
    CliCompatibilityHeaderFilter.class,
    TestSecurityConfiguration.class,
    ProbeController.class
  })
  static class TestApplication {}

  @Configuration(proxyBeanMethods = false)
  static class TestSecurityConfiguration {

    @Bean
    SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
      return http.csrf(csrf -> csrf.disable())
          .httpBasic(withDefaults())
          .authorizeHttpRequests(
              requests ->
                  requests.requestMatchers("/api/**").authenticated().anyRequest().permitAll())
          .build();
    }
  }

  @RestController
  static class ProbeController {

    @GetMapping("/api/private-probe")
    String privateProbe() {
      return "must not execute anonymously";
    }
  }
}
