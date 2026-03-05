package com.navneet.agentteam.config;

import com.google.genai.Client;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
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
            case "anthropic" -> ctx.getBean(AnthropicChatModel.class);
            case "openai"    -> ctx.getBean(OpenAiChatModel.class);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
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
        log.info("[AgentModelConfig] Creating system Gemini bean (gemini-3.1-flash-lite-preview)");
        Client client = Client.builder().apiKey(apiKey).build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder().model("gemini-3.1-flash-lite-preview").build())
                .build();
    }
}
