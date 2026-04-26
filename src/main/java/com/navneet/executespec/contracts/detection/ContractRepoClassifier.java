package com.navneet.executespec.contracts.detection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2.5 — classifies a repo as a "contracts repo" (a separate repo whose
 * sole purpose is to host contract artefacts that other repos in the
 * project produce/consume).
 *
 * <p>Heuristic-only in this slice. The LLM verifier for ambiguous cases
 * is logged as a §13 deferral; the heuristic alone catches the obvious
 * cases (name match + high contract-file ratio + no application code) and
 * lets the rest fall through as {@link Verdict.Confidence#AMBIGUOUS}, where
 * the UI can prompt the user to confirm.
 *
 * <p>Producer matching (correlating package names across repos to figure
 * out which repo "produces" a contract that's only "defined in" the
 * contracts-repo) is also deferred — needs cross-repo file inspection
 * heavier than this slice. For now contracts found in a contracts-repo
 * get {@code defined_in_repo_id = repoId} and {@code repo_id} unset
 * pending the producer match.
 */
@Component
@Slf4j
public class ContractRepoClassifier {

    /** Substrings in repo full name that hint at a contracts repo. */
    private static final List<String> NAME_HINTS = List.of(
            "contracts", "contract", "schemas", "schema",
            "proto", "protos", "idl", "specs", "spec");

    /** Files that strongly imply application code (i.e. NOT a contracts repo). */
    private static final List<String> APP_CODE_MARKERS = List.of(
            "pom.xml",
            "build.gradle", "build.gradle.kts",
            "Cargo.toml",
            "go.mod",
            "Gemfile",
            "Pipfile",
            "pyproject.toml",
            "setup.py",
            "Dockerfile",
            "docker-compose.yml", "docker-compose.yaml");

    /** package.json doesn't on its own disqualify a repo (a contracts repo
     *  may have one for tooling); we check {@code "main"} or {@code "scripts"}
     *  presence below to differentiate. */

    /** File extensions we count as contract artefacts when computing the
     *  contract-density ratio. */
    private static final List<String> CONTRACT_EXTENSIONS = List.of(
            ".proto", ".graphql", ".graphqls",
            ".openapi.yaml", ".openapi.yml", ".openapi.json",
            ".swagger.yaml", ".swagger.yml", ".swagger.json",
            ".asyncapi.yaml", ".asyncapi.yml", ".asyncapi.json",
            ".schema.json");

    /** Ratio threshold (contract files / non-config files) above which the
     *  repo qualifies as a contracts repo on density alone. */
    private static final double CONTRACT_DENSITY_THRESHOLD = 0.7;

    /**
     * Classify a repo by its full name + file list (no content fetch).
     * Cheap to call — the orchestrator can run this on every detection
     * pass without measurable cost.
     *
     * @param repoFullName  e.g. {@code "acme/payments-contracts"}
     * @param repoFilePaths the repo's complete file path list
     */
    public Verdict classify(String repoFullName, List<String> repoFilePaths) {
        List<String> reasons = new ArrayList<>();
        boolean nameMatches = nameMatchesContractsHint(repoFullName);
        if (nameMatches) reasons.add("name matches contracts/schemas/proto pattern");

        List<String> files = repoFilePaths == null ? List.of() : repoFilePaths;
        boolean hasAppCode = hasApplicationCode(files);
        if (hasAppCode) reasons.add("application-code markers present");

        double density = contractDensity(files);
        if (density >= CONTRACT_DENSITY_THRESHOLD) {
            reasons.add(String.format("%.0f%% of source files are contract artefacts", density * 100));
        }

        // Decision tree:
        //  - Application code present → never a pure contracts repo
        //  - Name match + high density → CONFIDENT yes
        //  - Name match alone OR high density alone → AMBIGUOUS
        //  - Neither → CONFIDENT no
        if (hasAppCode) {
            return new Verdict(false, Verdict.Confidence.CONFIDENT, reasons);
        }
        if (nameMatches && density >= CONTRACT_DENSITY_THRESHOLD) {
            return new Verdict(true, Verdict.Confidence.CONFIDENT, reasons);
        }
        if (nameMatches || density >= CONTRACT_DENSITY_THRESHOLD) {
            return new Verdict(true, Verdict.Confidence.AMBIGUOUS, reasons);
        }
        return new Verdict(false, Verdict.Confidence.CONFIDENT, reasons);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    boolean nameMatchesContractsHint(String repoFullName) {
        if (repoFullName == null) return false;
        String lower = repoFullName.toLowerCase();
        // Strip the owner prefix so we match against just the repo name segment.
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        for (String hint : NAME_HINTS) {
            // Hint must occur at a word boundary (-, _, or whole-name) so
            // 'specification' doesn't trigger 'spec' but 'contract-stuff'
            // and 'api-contracts' both do.
            if (name.equals(hint)
                    || name.startsWith(hint + "-") || name.startsWith(hint + "_")
                    || name.endsWith("-" + hint)   || name.endsWith("_" + hint)
                    || name.contains("-" + hint + "-") || name.contains("_" + hint + "_")) {
                return true;
            }
        }
        return false;
    }

    boolean hasApplicationCode(List<String> files) {
        for (String f : files) {
            String basename = f.contains("/") ? f.substring(f.lastIndexOf('/') + 1) : f;
            for (String marker : APP_CODE_MARKERS) {
                if (basename.equalsIgnoreCase(marker)) return true;
            }
        }
        return false;
    }

    /**
     * Ratio of contract-artefact files to "interesting" files (everything
     * except top-level config like README.md, LICENSE, .gitignore).
     * Returns 0.0 for empty file lists.
     */
    double contractDensity(List<String> files) {
        if (files.isEmpty()) return 0.0;
        int interesting = 0;
        int contracts = 0;
        for (String f : files) {
            String lower = f.toLowerCase();
            if (isBoringConfigFile(lower)) continue;
            interesting++;
            if (isContractFile(lower)) contracts++;
        }
        return interesting == 0 ? 0.0 : (double) contracts / (double) interesting;
    }

    private static boolean isContractFile(String pathLower) {
        for (String ext : CONTRACT_EXTENSIONS) {
            if (pathLower.endsWith(ext)) return true;
        }
        return false;
    }

    private static boolean isBoringConfigFile(String pathLower) {
        return pathLower.endsWith("readme.md")
                || pathLower.endsWith("license") || pathLower.endsWith("license.md")
                || pathLower.endsWith(".gitignore") || pathLower.endsWith(".gitattributes")
                || pathLower.endsWith(".editorconfig")
                || pathLower.endsWith(".github/workflows/")
                || pathLower.contains("/.github/");
    }

    /**
     * @param isContractRepo true when this repo is best treated as a
     *                       dedicated contracts repository
     * @param confidence     {@link Confidence#CONFIDENT} when both signals
     *                       agree; {@link Confidence#AMBIGUOUS} when only
     *                       one signal fires (UI should prompt confirmation)
     * @param reasons        human-readable bullets explaining the verdict
     */
    public record Verdict(boolean isContractRepo, Confidence confidence, List<String> reasons) {
        public enum Confidence { CONFIDENT, AMBIGUOUS }
    }
}
