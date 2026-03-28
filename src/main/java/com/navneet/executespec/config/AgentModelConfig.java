package com.navneet.executespec.config;
import com.navneet.executespec.config.ModelConstants;

import com.google.genai.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentModelConfig {

    private final AgentProperties props;
    private final ApplicationContext ctx;

    /**
     * Replaces the default (no-timeout) JDK HTTP client factory with one that has an
     * explicit 5-minute read timeout and forces HTTP/1.1.
     *
     * <p>Why needed: LLM responses for large tasks routinely take 30–90 seconds.
     * The JDK HTTP client's HTTP/2 ALPN negotiation can also stall silently.
     * HTTP/1.1 is simpler, always supported by Anthropic/OpenAI, and avoids
     * any ALPN-related connection hangs.
     */
    @Bean
    public RestClientCustomizer llmHttpClientCustomizer() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)          // avoid HTTP/2 ALPN stalls
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(300));       // 5 min — long enough for any LLM call

        log.info("[AgentModelConfig] HTTP client: HTTP/1.1, connect=30s, read=300s");
        return builder -> builder.requestFactory(factory);
    }

    // ── Model bean resolution ─────────────────────────────────────────────────

    @Bean("plannerModel")
    public ChatModel plannerModel() {
        return resolveModel(props.getRoles().getPlanner());
    }

    @Bean("workerModel")
    public ChatModel workerModel() {
        return resolveModel(props.getRoles().getWorker());
    }

    @Bean("reviewerModel")
    public ChatModel reviewerModel() {
        return resolveModel(props.getRoles().getReviewer());
    }

    private ChatModel resolveModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> {
                AnthropicChatModel model = ctx.getBean(AnthropicChatModel.class);
                if (model == null) {
                    throw new IllegalStateException("Anthropic provider requested but ANTHROPIC_API_KEY not configured");
                }
                yield model;
            }
            case "openai"    -> {
                OpenAiChatModel model = ctx.getBean(OpenAiChatModel.class);
                if (model == null) {
                    throw new IllegalStateException("OpenAI provider requested but OPENAI_API_KEY not configured");
                }
                yield model;
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    /**
     * System-level Anthropic bean — only created when ANTHROPIC_API_KEY is configured.
     * Auto-config is excluded (requires non-empty key at startup); we create manually
     * so the app can start without an Anthropic key (graceful degradation on MIG restart).
     * When absent, LlmRouter skips Anthropic in the fallback chain.
     */
    @Bean
    public AnthropicChatModel anthropicChatModel(
            @Value("${spring.ai.anthropic.api-key:}") String apiKey,
            @Value("${spring.ai.anthropic.chat.options.model:claude-haiku-4-5-20251001}") String model,
            @Value("${spring.ai.anthropic.chat.options.max-tokens:16000}") int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AgentModelConfig] No ANTHROPIC_API_KEY configured — Anthropic provider DISABLED. "
                   + "Runs will fail until the key is provided via Secret Manager or env file.");
            return null;
        }
        log.info("[AgentModelConfig] Creating system Anthropic bean ({})", model);
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(model)
                        .maxTokens(maxTokens)
                        .temperature(0.0)
                        .build())
                .build();
    }

    /**
     * System-level OpenAI bean — only created when OPENAI_API_KEY is configured.
     * Auto-config is excluded (requires non-empty key at startup); we create manually
     * so the app can start without an OpenAI key (graceful degradation).
     * When absent, LlmRouter skips OpenAI in the fallback chain.
     */
    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[AgentModelConfig] No OPENAI_API_KEY configured — system OpenAI fallback disabled");
            return null;
        }
        log.info("[AgentModelConfig] Creating system OpenAI bean (gpt-5.4)");
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }

    /**
     * System-level Google Gemini bean — only created when GOOGLE_API_KEY is configured.
     * Used as the 3rd fallback provider in LlmRouter when no user/org key is present.
     * When absent, LlmRouter gracefully skips Gemini in the fallback chain.
     */
    @Bean
    public GoogleGenAiChatModel googleGenAiChatModel(
            @Value("${spring.ai.google.genai.api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[AgentModelConfig] No GOOGLE_API_KEY configured — system Gemini fallback disabled");
            return null;
        }
        log.info("[AgentModelConfig] Creating system Gemini bean ({})", ModelConstants.GEMINI_CAPABLE);
        Client client = Client.builder().apiKey(apiKey).build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder().model(ModelConstants.GEMINI_CAPABLE).temperature(0.0).build())
                .build();
    }
}
