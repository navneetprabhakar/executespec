package com.navneet.executespec.codeintel;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 2 of the 3-step architecture generation pipeline: codemap → per-repo
 * HLD + LLD architecture doc in natural language.
 *
 * <p>Separate from {@link ArchitectureDocGenerator} which does the Step 3
 * combined synthesis across repos. Each generator dispatches through
 * {@link LlmRouter} via the LIGHTWEIGHT tier (cheap, fast).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RepoArchitectureGenerator {

    private final LlmRouter llmRouter;
    private final ModelResolver modelResolver;

    private static final String SYSTEM_PROMPT = """
            You are writing the architecture document for a single repository in a
            multi-repo project. The input is a code map (directory outline, routes,
            models). Produce a single markdown document in natural language with:

            # <repo full name> — Architecture

            ## 1. High-Level Design (HLD)
            2-4 paragraphs on the purpose of this repo, its place in the project,
            the major components / layers, and the external dependencies it talks
            to (databases, queues, other services, third-party APIs).

            ## 2. Low-Level Design (LLD)
            Grouped subsections — one per major component surfaced in the codemap
            (controllers, services, repositories, workers, UI modules, etc.).
            Under each, name the concrete classes/files, describe their responsibility
            in 1-3 sentences, and cite the file path from the codemap.

            ## 3. Surface area
            - Routes / endpoints this repo exposes (copy from the codemap if present)
            - Entities / domain models
            - Background jobs / schedulers

            ## 4. Integration points
            What this repo consumes from other repos in the project and what it
            publishes. If unclear from the codemap, state 'not evident from the
            codemap — confirm with reviewer'.

            Rules:
            - Natural language prose, not bullet-dumps, except where lists improve scanning.
            - Do NOT invent files, classes, or routes that are not in the codemap.
            - Cite file paths inline with backticks.
            - Aim for 500-1000 words. Dense, scannable, citation-rich.
            """;

    public record Result(String markdown, int inputTokens, int outputTokens, String modelUsed) {}

    /**
     * Generate the per-repo architecture doc from a codemap.
     *
     * @param repoFullName    e.g. "pplai-app/api-server"
     * @param codeMapMarkdown the markdown produced by {@link CodeMapGenerator}
     * @param provider        LLM provider; falls back to OPENAI on null
     */
    public Result generate(String repoFullName, String codeMapMarkdown, LlmProvider provider) {
        if (codeMapMarkdown == null || codeMapMarkdown.isBlank()) {
            throw new IllegalArgumentException("code map is empty for " + repoFullName);
        }

        LlmProvider effective = provider != null ? provider : LlmProvider.OPENAI;
        ResolvedModel resolved = modelResolver.resolve("RepoArchitectureGenerator", effective);

        String userPayload = "Repository: " + repoFullName + "\n\n"
                + "Code map:\n\n" + codeMapMarkdown;

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userPayload)));

        ChatResponse response = llmRouter.callWithFallback(
                resolved.providerName(), resolved.getModel(), prompt,
                "repo-arch-" + safeSlug(repoFullName),
                "repo-arch",
                LlmRouter.FallbackMode.IMMEDIATE, 0, false);

        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("LLM returned empty arch content for " + repoFullName);
        }
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        int inTok = usage == null ? 0 : safeInt(usage.getPromptTokens());
        int outTok = usage == null ? 0 : safeInt(usage.getCompletionTokens());
        log.info("Generated per-repo arch for {} ({} in / {} out tokens, model={})",
                repoFullName, inTok, outTok, resolved.getModel());
        return new Result(text.trim(), inTok, outTok, resolved.getModel());
    }

    private static String safeSlug(String s) {
        if (s == null) return "unknown";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    private static int safeInt(Integer i) { return i == null ? 0 : i; }
}
