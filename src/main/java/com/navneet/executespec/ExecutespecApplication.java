package com.navneet.executespec;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.integration.IntegrationProperties;

import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.*;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication(
    exclude = {
      // ── Anthropic — excluded so app can start without ANTHROPIC_API_KEY ──────
      // Bean created manually in AgentModelConfig with graceful degradation.
      AnthropicChatAutoConfiguration.class,
      // ── Google Gemini — only used via manual FallbackLlmRouter calls ─────────────────
      GoogleGenAiChatAutoConfiguration.class,
      GoogleGenAiEmbeddingConnectionAutoConfiguration.class,
      GoogleGenAiTextEmbeddingAutoConfiguration.class,
      // ── OpenAI — all auto-configs excluded; chat bean created manually ────────
      OpenAiChatAutoConfiguration.class,
      OpenAiAudioSpeechAutoConfiguration.class,
      OpenAiAudioTranscriptionAutoConfiguration.class,
      OpenAiEmbeddingAutoConfiguration.class,
      OpenAiImageAutoConfiguration.class,
      OpenAiModerationAutoConfiguration.class
    })
@EnableScheduling
@EnableAsync
@EnableRetry
@EnableConfigurationProperties({
  DeploymentProperties.class,
  IntegrationProperties.class,
  com.navneet.executespec.router.ModelMatrixProperties.class
})
@Slf4j
public class ExecutespecApplication {

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(ExecutespecApplication.class);
    app.setBannerMode(Banner.Mode.OFF);
    app.run(args);

    // CLI mode has been retired with V1 harness. Web server always stays up.
    if (args.length > 0) {
      log.warn("Ignoring {} CLI args — submit runs via POST /api/runs instead.", args.length);
    }
    log.info("Executespec web server running");
  }
}
