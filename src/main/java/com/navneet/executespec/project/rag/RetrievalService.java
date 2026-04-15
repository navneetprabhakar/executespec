package com.navneet.executespec.project.rag;

import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Always-on retrieval surface used by the planner + chat. Returns a compact
 * markdown block ready to paste into a system prompt; empty string when the
 * project has no indexed documents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private static final int DEFAULT_K = 3;
    private static final int DEFAULT_TOKEN_CAP = 1500;
    private static final int CHARS_PER_TOKEN = 4;

    private final ProjectDocumentRepository documentRepo;
    private final EmbeddingService embeddingService;
    private final ChunkStore chunkStore;

    public String retrieve(String projectId, String query) {
        return retrieve(projectId, query, DEFAULT_K, DEFAULT_TOKEN_CAP);
    }

    public String retrieve(String projectId, String query, int k, int tokenCap) {
        if (projectId == null || query == null || query.isBlank()) return "";
        long readyCount = documentRepo.countByProjectIdAndStatusAndDeletedAtIsNull(
                projectId, ProjectDocument.Status.READY);
        if (readyCount == 0) return "";
        try {
            float[] qVec = embeddingService.embedQuery(query);
            List<ChunkStore.RetrievedChunk> hits = chunkStore.nearest(projectId, qVec, k);
            if (hits.isEmpty()) return "";
            StringBuilder out = new StringBuilder("## REFERENCE DOCS\n");
            int charBudget = tokenCap * CHARS_PER_TOKEN;
            int written = 0;
            int i = 1;
            for (var h : hits) {
                String header = "[S" + i++ + "] " + h.filename() + " (chunk " + h.chunkIndex() + ")\n";
                String body = h.content().trim() + "\n\n";
                if (written + header.length() + body.length() > charBudget) {
                    int remaining = Math.max(0, charBudget - written - header.length());
                    if (remaining < 200) break;
                    out.append(header).append(body, 0, Math.min(remaining, body.length()));
                    break;
                }
                out.append(header).append(body);
                written += header.length() + body.length();
            }
            return out.toString();
        } catch (Exception e) {
            log.warn("RetrievalService failed for project={}: {}", projectId, e.getMessage());
            return "";
        }
    }
}
