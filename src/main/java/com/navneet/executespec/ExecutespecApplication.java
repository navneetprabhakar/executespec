package com.navneet.executespec;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.integration.IntegrationProperties;
import com.navneet.executespec.harness.PipelineHarness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiEmbeddingConnectionAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.embedding.GoogleGenAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Scanner;

@SpringBootApplication(exclude = {
    // ── Anthropic — excluded so app can start without ANTHROPIC_API_KEY ──────
    // Bean created manually in AgentModelConfig with graceful degradation.
    AnthropicChatAutoConfiguration.class,
    // ── Google Gemini — only used via manual LlmRouter calls ─────────────────
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
@EnableConfigurationProperties({DeploymentProperties.class, IntegrationProperties.class,
        com.navneet.executespec.router.ModelMatrixProperties.class})
@Slf4j
public class ExecutespecApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ExecutespecApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        ConfigurableApplicationContext ctx = app.run(args);

        if (args.length > 0) {
            // CLI mode: run once synchronously; web server stays alive afterward
            String request = String.join(" ", args);
            PipelineHarness harness = ctx.getBean(PipelineHarness.class);
            harness.run(request);
        }
        // No ctx.close() — web server stays up for REST, SSE, and Web UI
        log.info("Executespec web server running");
    }

    private static String promptUser(String prompt) {
        log.info(prompt);
        return new Scanner(System.in).nextLine().trim();
    }
}
