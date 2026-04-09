package com.navneet.executespec.batch;

import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.RunKeyStore;
import com.navneet.executespec.router.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Routes batch operations to the correct provider client and resolves API keys.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchClientRouter {

    private final AnthropicBatchClient anthropicBatchClient;
    private final OpenAiBatchClient openAiBatchClient;
    private final GoogleBatchClient googleBatchClient;
    private final RunKeyStore runKeyStore;

    @Value("${spring.ai.anthropic.api-key:}")
    private String systemAnthropicKey;

    @Value("${spring.ai.openai.api-key:}")
    private String systemOpenAiKey;

    @Value("${spring.ai.google.genai.api-key:}")
    private String systemGeminiKey;

    public String submitBatch(String runId, List<BatchRequest> requests, LlmProvider provider) {
        String apiKey = resolveKey(runId, provider);
        return clientFor(provider).submitBatch(runId, requests, apiKey);
    }

    public BatchStatus checkStatus(String batchId, String runId, LlmProvider provider) {
        String apiKey = resolveKey(runId, provider);
        return clientFor(provider).checkStatus(batchId, apiKey);
    }

    public BatchResults getResults(String batchId, String runId, LlmProvider provider) {
        String apiKey = resolveKey(runId, provider);
        return clientFor(provider).getResults(batchId, apiKey);
    }

    public void cancelBatch(String batchId, String runId, LlmProvider provider) {
        String apiKey = resolveKey(runId, provider);
        clientFor(provider).cancelBatch(batchId, apiKey);
    }

    private LlmBatchClient clientFor(LlmProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> anthropicBatchClient;
            case OPENAI    -> openAiBatchClient;
            case GOOGLE    -> googleBatchClient;
        };
    }

    /**
     * Resolve the API key for a given run + provider.
     * Uses per-run keys from RunKeyStore if available, falls back to system keys.
     */
    private String resolveKey(String runId, LlmProvider provider) {
        ResolvedKeys keys = runKeyStore.get(runId);
        return switch (provider) {
            case ANTHROPIC -> {
                String key = keys.anthropicKey();
                yield (key != null && !key.isBlank()) ? key : systemAnthropicKey;
            }
            case OPENAI -> {
                String key = keys.openAiKey();
                yield (key != null && !key.isBlank()) ? key : systemOpenAiKey;
            }
            case GOOGLE -> {
                String key = keys.geminiKey();
                yield (key != null && !key.isBlank()) ? key : systemGeminiKey;
            }
        };
    }
}
