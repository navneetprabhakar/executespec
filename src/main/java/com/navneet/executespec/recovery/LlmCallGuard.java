package com.navneet.executespec.recovery;

import com.navneet.executespec.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@Component
@Slf4j
public class LlmCallGuard {

    private final ChatModel chatModel;
    private final RecoveryProperties props;
    private final AgentProperties agentProperties;
    private String lastUsedProvider = "anthropic";

    public LlmCallGuard(@Qualifier("plannerModel") ChatModel chatModel,
                        RecoveryProperties props,
                        AgentProperties agentProperties) {
        this.chatModel = chatModel;
        this.props = props;
        this.agentProperties = agentProperties;
    }

    /**
     * Calls the LLM with a specific model override (e.g. Haiku for Scout-role agents).
     * Attaches the given model as AnthropicChatOptions so the override takes precedence
     * over the application.yml default. maxTokens from agent.token-budget.max-tokens
     * is always set — Anthropic API requires it.
     */
    public ChatResponse call(Prompt prompt, String modelOverride, String taskId, String runId) {
        int maxTokens = agentProperties.getTokenBudget().getMaxTokens();
        Prompt augmented = new Prompt(prompt.getInstructions(),
            AnthropicChatOptions.builder().model(modelOverride).maxTokens(maxTokens).build());
        return call(augmented, taskId, runId);
    }

    /**
     * Calls the LLM with full error classification.
     * Transient failures (rate limit, server error) are retried internally.
     * Permanent failures throw LlmCallException for the caller to handle.
     */
    public ChatResponse call(Prompt prompt, String taskId, String runId) {
        int attempt = 0;

        while (attempt <= props.getMaxTransientRetries()) {
            try {
                ChatResponse response = chatModel.call(prompt);
                lastUsedProvider = "anthropic";
                return response;

            } catch (HttpClientErrorException e) {
                LlmFailure failure = classifyClientError(e);

                switch (failure) {
                    case LlmFailure.RateLimited rl -> {
                        if (attempt < props.getMaxTransientRetries()) {
                            int waitSecs = rl.retryAfterSeconds() > 0
                                ? rl.retryAfterSeconds()
                                : props.getRateLimitBackoffSeconds();
                            log.warn("Rate limited by {} — waiting {}s (attempt {}/{})",
                                rl.provider(), waitSecs, attempt + 1,
                                props.getMaxTransientRetries());
                            sleep(waitSecs);
                            attempt++;
                        } else {
                            throw new LlmCallException(failure);
                        }
                    }
                    default -> throw new LlmCallException(failure);
                }

            } catch (HttpServerErrorException e) {
                if (attempt < props.getMaxTransientRetries()) {
                    int backoff = props.getServerErrorBackoffSeconds() * (attempt + 1);
                    log.warn("Server error {} — backing off {}s", e.getStatusCode(), backoff);
                    sleep(backoff);
                    attempt++;
                } else {
                    throw new LlmCallException(new LlmFailure.ServerError(
                        e.getStatusCode().value(), lastUsedProvider, e.getMessage()
                    ));
                }
            } catch (Exception e) {
                // Catch-all for non-HTTP exceptions (e.g. timeout, network)
                if (attempt < props.getMaxTransientRetries()) {
                    int backoff = props.getServerErrorBackoffSeconds() * (attempt + 1);
                    log.warn("LLM call error (attempt {}) — backing off {}s: {}",
                        attempt + 1, backoff, e.getMessage());
                    sleep(backoff);
                    attempt++;
                } else {
                    throw new LlmCallException(new LlmFailure.Unknown(
                        0, lastUsedProvider, e.getMessage()
                    ));
                }
            }
        }

        throw new LlmCallException(new LlmFailure.Unknown(
            0, lastUsedProvider, "Exhausted retries without resolution"
        ));
    }

    private LlmFailure classifyClientError(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        String body = e.getResponseBodyAsString();
        String provider = lastUsedProvider;

        return switch (status) {
            case 401 -> new LlmFailure.AuthFailure(provider, body);
            case 402 -> new LlmFailure.BudgetExhausted(provider, body);
            case 429 -> {
                boolean isBudget = body.contains("quota") ||
                                   body.contains("billing") ||
                                   body.contains("insufficient_quota");
                if (isBudget) yield new LlmFailure.BudgetExhausted(provider, body);
                int retryAfter = parseRetryAfter(e);
                yield new LlmFailure.RateLimited(retryAfter, provider);
            }
            case 400 -> {
                boolean isContext = body.contains("context_length") ||
                                    body.contains("maximum context") ||
                                    body.contains("too long");
                if (isContext) yield new LlmFailure.ContextTooLong(0, 0, provider);
                yield new LlmFailure.Unknown(status, provider, body);
            }
            default -> new LlmFailure.Unknown(status, provider, body);
        };
    }

    private int parseRetryAfter(HttpClientErrorException e) {
        try {
            String header = e.getResponseHeaders() != null
                ? e.getResponseHeaders().getFirst("Retry-After")
                : null;
            return header != null ? Integer.parseInt(header) : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
