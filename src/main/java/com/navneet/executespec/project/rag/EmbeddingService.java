package com.navneet.executespec.project.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embeds short text passages with the configured OpenAI embedding model
 * (text-embedding-3-small, 1536 dims). Batches up to 100 inputs per call and
 * retries once on transient failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private static final int BATCH_SIZE = 100;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("No EmbeddingModel bean — RAG embedding requires OpenAI key");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            EmbeddingResponse resp = call(model, batch);
            resp.getResults().forEach(r -> out.add(r.getOutput()));
        }
        return out;
    }

    public float[] embedQuery(String text) {
        return embed(List.of(text)).get(0);
    }

    private EmbeddingResponse call(EmbeddingModel model, List<String> batch) {
        try {
            return model.embedForResponse(batch);
        } catch (Exception first) {
            log.warn("Embedding call failed, retrying once: {}", first.getMessage());
            try {
                return model.embedForResponse(batch);
            } catch (Exception second) {
                throw new RuntimeException("Embedding failed after retry: " + second.getMessage(), second);
            }
        }
    }
}
