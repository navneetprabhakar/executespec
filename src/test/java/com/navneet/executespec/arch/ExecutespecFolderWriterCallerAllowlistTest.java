package com.navneet.executespec.arch;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Caller allowlist for {@code ExecutespecFolderWriter#writeFiles}.
 *
 * <p>Background: every write to a customer's {@code .executespec/} folder is
 * tied to a strict contract — the {@code ProjectRepo.executespecFolderStatus}
 * machine ({@code MISSING / BOOTSTRAP_PENDING / PR_PENDING / READY}), the
 * {@code executespecPrMode} dispatch (PR vs DIRECT_PUSH), and the bootstrap-PR
 * preflight that has to land first. New callers that bypass this surface
 * trample the contract and can corrupt customer git history.
 *
 * <p>This test pins the set of classes that may invoke the writer's mutating
 * surface. When a new caller is added, the developer must:
 * <ol>
 *   <li>verify the call site respects the bootstrap-status machine and PR
 *       dispatch (i.e. it routes through the writer's public API, not around
 *       it),</li>
 *   <li>add the class to the allowlist below with a one-line justification.</li>
 * </ol>
 *
 * <p>BootstrapService and the writer itself are excluded — the writer's own
 * package owns the contract, so internal calls do not count.
 */
class ExecutespecFolderWriterCallerAllowlistTest {

    private static final String WRITER_FQN =
            "com.navneet.executespec.folder.ExecutespecFolderWriter";

    /** Mutating methods on the writer (read-only methods like {@code readFile}
     *  do not need allowlisting since they cannot corrupt customer git). */
    private static final Set<String> MUTATING_METHODS = Set.of("writeFiles");

    /**
     * Classes that may invoke {@code ExecutespecFolderWriter#writeFiles}.
     * Each entry uses the simple class name. Justification lives next to the
     * entry; if a justification rots, prune the caller, don't soften the rule.
     */
    private static final Set<String> APPROVED_CALLERS = new TreeSet<>(Set.of(
            // E1.5 — bootstrap flow itself owns the very first write that
            // creates the .executespec/ scaffold + flips the project_repo
            // status machine to READY. Same package as the writer.
            "BootstrapService",

            // E2.6 — drift resolution writes the contract index into the
            // producing repo's .executespec/contracts/ subtree. Routes through
            // the public writer surface so PR-mode/DIRECT_PUSH dispatch is
            // honoured.
            "ContractDriftController",

            // E2.7 — consumer registration mirrors the consumer's pinned
            // contract version into its own .executespec/contracts/consumed/.
            // Same writer surface, same dispatch.
            "ContractConsumerController",

            // E1.6 — the codemap/architecture publish path commits versioned
            // codemaps + the combined arch doc into .executespec/. The
            // ProjectArchitectureService routes through the writer so the
            // bootstrap-status machine gates the publish.
            "ProjectArchitectureService"
    ));

    @Test
    void writerCallerAllowlistInSync() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.navneet.executespec");

        Set<String> actualCallers = classes.stream()
                .flatMap(c -> c.getAccessesFromSelf().stream())
                .filter((JavaAccess<?> a) -> a instanceof JavaMethodCall)
                .map(a -> (JavaMethodCall) a)
                .filter(call -> WRITER_FQN.equals(call.getTargetOwner().getName()))
                .filter(call -> MUTATING_METHODS.contains(call.getTarget().getName()))
                // A class shouldn't have to allowlist itself for in-package calls;
                // exclude the writer's own class.
                .filter(call -> !WRITER_FQN.equals(call.getOriginOwner().getName()))
                .map(call -> simpleName(call.getOriginOwner().getName()))
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> added = new TreeSet<>(actualCallers);
        added.removeAll(APPROVED_CALLERS);
        Set<String> removed = new TreeSet<>(APPROVED_CALLERS);
        removed.removeAll(actualCallers);

        String msg = "ExecutespecFolderWriter caller allowlist drift detected."
                + (added.isEmpty() ? "" : "\n  NEW callers of writeFiles (verify bootstrap-status + PR-mode dispatch contract before allowlisting): " + added)
                + (removed.isEmpty() ? "" : "\n  REMOVED callers (drop from APPROVED_CALLERS): " + removed)
                + "\n\nContext: writes to customer .executespec/ folders must"
                + " honour the project_repo.executespec_folder_status machine"
                + " and the executespec_pr_mode dispatch. Bypassing the writer"
                + " can corrupt customer git history.";

        assertEquals(APPROVED_CALLERS, actualCallers, msg);
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        String simple = dot < 0 ? fqcn : fqcn.substring(dot + 1);
        // Strip nested-class suffixes (lambdas / anonymous inner classes) so
        // both ContractDriftController and ContractDriftController$Lambda$1
        // collapse to the outer class.
        int dollar = simple.indexOf('$');
        return dollar < 0 ? simple : simple.substring(0, dollar);
    }
}
