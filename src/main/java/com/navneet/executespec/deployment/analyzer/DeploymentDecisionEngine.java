package com.navneet.executespec.deployment.analyzer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes an {@link InfrastructureManifest} to available deployment paths.
 * <p>
 * For stateless apps: only Cloud Run Direct (no choice needed).
 * For stateful apps: both Compose on VM and Managed Infrastructure are offered.
 * The engine recommends but the user decides.
 */
@Service
@Slf4j
public class DeploymentDecisionEngine {

    /**
     * Decide available deployment paths based on the infrastructure manifest.
     */
    public DeploymentDecision decide(InfrastructureManifest manifest) {
        if (!manifest.hasStatefulDependencies() && manifest.stateless()) {
            // Stateless, no external deps → only one sensible path
            log.info("Decision: stateless app → Cloud Run Direct");
            return DeploymentDecision.builder()
                    .availablePaths(List.of(DeploymentPath.CLOUD_RUN_DIRECT))
                    .recommendedPath(DeploymentPath.CLOUD_RUN_DIRECT)
                    .reason("Stateless application with no external dependencies. Cloud Run is optimal.")
                    .userChoiceRequired(false)
                    .pathComparison(null)
                    .build();
        }

        // Stateful — present both options
        log.info("Decision: stateful app → user chooses between Compose VM and Managed Infrastructure");
        return DeploymentDecision.builder()
                .availablePaths(List.of(DeploymentPath.COMPOSE_ON_VM, DeploymentPath.MANAGED_INFRASTRUCTURE))
                .recommendedPath(DeploymentPath.COMPOSE_ON_VM) // default for cost
                .reason("Application requires external services (" + describeDeps(manifest)
                        + "). Choose your deployment tier.")
                .userChoiceRequired(true)
                .pathComparison(buildComparison(manifest))
                .build();
    }

    // ── Comparison builder ────────────────────────────────────────────────────

    private PathComparison buildComparison(InfrastructureManifest manifest) {
        return new PathComparison(
                buildStarterPath(manifest),
                buildProductionPath(manifest)
        );
    }

    private PathDetail buildStarterPath(InfrastructureManifest manifest) {
        List<String> provisions = new ArrayList<>();
        provisions.add("1x GCE VM (" + recommendVmSize(manifest) + ")");
        provisions.add("Docker container: " + manifest.appType().name().toLowerCase());
        if (manifest.database() != null)
            provisions.add("Docker container: " + manifest.database().type().name().toLowerCase());
        if (manifest.cache() != null)
            provisions.add("Docker container: " + manifest.cache().type().name().toLowerCase());
        if (manifest.queue() != null)
            provisions.add("Docker container: " + manifest.queue().type().name().toLowerCase());
        if (manifest.database() != null)
            provisions.add("Automated daily database backups");
        provisions.add("Firewall rule for HTTP/HTTPS");

        CostEstimate cost = estimateVmCost(manifest);

        return new PathDetail(
                DeploymentPath.COMPOSE_ON_VM,
                "Starter",
                "All services on a single VM with Docker Compose",
                provisions,
                List.of("Cheapest option", "Simple architecture", "Fast to deploy",
                        "Easy to debug (everything on one box)"),
                List.of("Single point of failure", "Manual vertical scaling only",
                        "VM maintenance is on you"),
                cost
        );
    }

    private PathDetail buildProductionPath(InfrastructureManifest manifest) {
        List<String> provisions = new ArrayList<>();
        provisions.add("1x Cloud Run service (auto-scaling)");
        if (manifest.database() != null)
            provisions.add("1x Cloud SQL " + manifest.database().type().name() + " instance (db-f1-micro)");
        if (manifest.cache() != null)
            provisions.add("1x Memorystore Redis (basic, 1GB)");
        if (manifest.storage() != null)
            provisions.add("1x GCS bucket for file storage");
        if (manifest.database() != null || manifest.cache() != null)
            provisions.add("VPC + Serverless VPC Connector");
        provisions.add("Secret Manager entries for credentials");

        CostEstimate cost = estimateManagedCost(manifest);

        return new PathDetail(
                DeploymentPath.MANAGED_INFRASTRUCTURE,
                "Production",
                "Cloud Run + managed Cloud SQL / Memorystore",
                provisions,
                List.of("Auto-scaling", "Managed database with automated backups",
                        "High availability options", "Zero server maintenance"),
                List.of("Higher cost", "More complex architecture",
                        "Slower initial provisioning (~5-10 min)"),
                cost
        );
    }

    // ── Cost estimation (simple lookup) ───────────────────────────────────────

    private CostEstimate estimateVmCost(InfrastructureManifest manifest) {
        String vmType = recommendVmSize(manifest);
        double vmCost = switch (vmType) {
            case "e2-small" -> 13;
            case "e2-medium" -> 25;
            case "e2-standard-2" -> 49;
            default -> 25;
        };
        return new CostEstimate("USD", vmCost - 3, vmCost + 5);
    }

    private CostEstimate estimateManagedCost(InfrastructureManifest manifest) {
        double low = 0, high = 0;
        // Cloud Run (scale-to-zero)
        low += 0; high += 15;
        // Cloud SQL
        if (manifest.database() != null) { low += 8; high += 15; }
        // Memorystore
        if (manifest.cache() != null) { low += 30; high += 40; }
        // VPC Connector
        if (manifest.database() != null || manifest.cache() != null) { low += 7; high += 10; }
        // GCS
        if (manifest.storage() != null) { low += 1; high += 5; }
        return new CostEstimate("USD", low, high);
    }

    private String recommendVmSize(InfrastructureManifest manifest) {
        int services = 1; // app
        if (manifest.database() != null) services++;
        if (manifest.cache() != null) services++;
        if (manifest.queue() != null) services++;
        return switch (services) {
            case 1 -> "e2-small";
            case 2 -> "e2-medium";
            default -> "e2-standard-2";
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String describeDeps(InfrastructureManifest manifest) {
        List<String> deps = new ArrayList<>();
        if (manifest.database() != null) deps.add(manifest.database().type().name());
        if (manifest.cache() != null) deps.add(manifest.cache().type().name());
        if (manifest.queue() != null) deps.add(manifest.queue().type().name());
        if (manifest.storage() != null) deps.add(manifest.storage().type().name());
        return String.join(" + ", deps);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @lombok.Builder
    public record DeploymentDecision(
            List<DeploymentPath> availablePaths,
            DeploymentPath recommendedPath,
            String reason,
            boolean userChoiceRequired,
            PathComparison pathComparison
    ) {}

    public record PathComparison(
            PathDetail starterPath,
            PathDetail productionPath
    ) {}

    public record PathDetail(
            DeploymentPath path,
            String label,
            String description,
            List<String> whatGetsProvisioned,
            List<String> pros,
            List<String> cons,
            CostEstimate estimatedCost
    ) {}

    public record CostEstimate(
            String currency,
            double monthlyLow,
            double monthlyHigh
    ) {}
}
