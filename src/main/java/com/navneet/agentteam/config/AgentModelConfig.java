package com.navneet.agentteam.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AgentModelConfig {

    private final AgentProperties props;
    private final ApplicationContext ctx;

    // ── Prompt caching ────────────────────────────────────────────────────────

    /**
     * Registers {@link AnthropicCacheInterceptor} on every {@link org.springframework.web.client.RestClient}
     * built from the Spring Boot auto-configured {@code RestClient.Builder}.
     *
     * <p>Spring AI 1.0's {@code AnthropicAutoConfiguration} injects the shared
     * {@code RestClient.Builder} to construct its internal {@code AnthropicApi} HTTP client.
     * Spring Boot applies all {@code RestClientCustomizer} beans to that builder before it is
     * injected, so our interceptor is transparently present on every Anthropic API call.
     *
     * <p>The interceptor is URL-aware: it only modifies requests to {@code anthropic.com}
     * (adds the {@code anthropic-beta} header + cache_control block). Requests to other
     * providers (OpenAI etc.) are passed through unchanged.
     *
     * <p>Effect: {@code worker.txt} (~1 800 tokens) is cached after the first iteration of
     * each task. Subsequent iterations (typically 4–14 more per task) receive cache reads —
     * ~85 % lower latency and ~90 % lower token cost on the system-prompt prefix.
     */
    @Bean
    public RestClientCustomizer anthropicPromptCachingCustomizer() {
        return builder -> builder.requestInterceptor(new AnthropicCacheInterceptor());
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
}
