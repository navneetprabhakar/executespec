package com.navneet.executespec.readymade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.readymade.dto.ReadymadeVariantDto;
import com.navneet.executespec.readymade.dto.VariantResolutionDto;
import com.navneet.executespec.readymade.entity.ReadymadeVariant;
import com.navneet.executespec.readymade.repository.ReadymadeVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * R-329 — picks the right {@link ReadymadeVariant} for a (solution,
 * project, repo) tuple.
 *
 * <p>Reads the linked {@link ProjectRepo}'s {@code detected_stack}
 * column (set at attach + sync time by {@code StackDetector}),
 * normalises the language/framework strings via
 * {@link StackNormalizer}, and queries
 * {@link ReadymadeVariantRepository#findBySolutionIdAndLanguageAndFramework}.
 *
 * <p>Resolution outcomes (surfaced in {@link VariantResolutionDto#reason}):
 * <ul>
 *   <li>{@code OK} — exact (language, framework) match found.</li>
 *   <li>{@code NO_VARIANT_FOR_STACK} — stack detected, but no
 *       variant authored for it. The UI should offer the user the
 *       full {@code availableVariants} list to pick manually.</li>
 *   <li>{@code STACK_NOT_DETECTED} — repo doesn't have a populated
 *       {@code detected_stack} (newly-attached repo before first
 *       sync, or detection failed). UI shows all available
 *       variants and asks the user to pick.</li>
 *   <li>{@code REPO_NOT_LINKED} — the {@code repoId} isn't actually
 *       linked to this project; controller-level 400.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VariantResolver {

    private final ReadymadeVariantRepository variantRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final ProjectRepoLinkRepository linkRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VariantResolutionDto resolve(UUID solutionId, String projectId, UUID repoId) {
        // Confirm the repo is actually linked to the project. The
        // controller will translate REPO_NOT_LINKED to 400; we
        // surface it as a reason to keep this service pure.
        if (linkRepo.findByProjectIdAndRepoId(projectId, repoId).isEmpty()) {
            return new VariantResolutionDto(
                null, allVariantDtos(solutionId), null, null, "REPO_NOT_LINKED"
            );
        }

        Optional<ProjectRepo> repoOpt = projectRepoRepo.findById(repoId);
        if (repoOpt.isEmpty()) {
            return new VariantResolutionDto(
                null, allVariantDtos(solutionId), null, null, "REPO_NOT_LINKED"
            );
        }

        ProjectRepo repo = repoOpt.get();
        String stackJson = repo.getDetectedStack();
        if (stackJson == null || stackJson.isBlank()) {
            return new VariantResolutionDto(
                null, allVariantDtos(solutionId), null, null, "STACK_NOT_DETECTED"
            );
        }

        String language;
        String framework;
        try {
            JsonNode tree = objectMapper.readTree(stackJson);
            language = StackNormalizer.normalizeLanguage(tree.path("language").asText(null));
            framework = StackNormalizer.normalizeFramework(tree.path("framework").asText(null));
        } catch (Exception e) {
            log.debug("[R-329] could not parse detected_stack for repo {}: {}",
                repoId, e.getMessage());
            return new VariantResolutionDto(
                null, allVariantDtos(solutionId), null, null, "STACK_NOT_DETECTED"
            );
        }

        if (language.isEmpty()) {
            return new VariantResolutionDto(
                null, allVariantDtos(solutionId), language, framework, "STACK_NOT_DETECTED"
            );
        }

        // Exact (solution, language, framework) lookup first.
        List<ReadymadeVariant> exact = variantRepo
            .findBySolutionIdAndLanguageAndFramework(solutionId, language, framework);
        if (!exact.isEmpty()) {
            return new VariantResolutionDto(
                ReadymadeVariantDto.from(exact.get(0)),
                allVariantDtos(solutionId),
                language, framework, "OK"
            );
        }

        // No exact match — return the full available list so the UI
        // can offer manual variant selection.
        return new VariantResolutionDto(
            null, allVariantDtos(solutionId), language, framework, "NO_VARIANT_FOR_STACK"
        );
    }

    private List<ReadymadeVariantDto> allVariantDtos(UUID solutionId) {
        return variantRepo.findBySolutionId(solutionId).stream()
            .map(ReadymadeVariantDto::from)
            .toList();
    }
}
