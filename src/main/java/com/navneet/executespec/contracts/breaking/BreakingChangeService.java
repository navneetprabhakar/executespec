package com.navneet.executespec.contracts.breaking;

import com.navneet.executespec.contracts.ContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * E2.7 — picks the right {@link BreakingChangeClassifier} for a given
 * {@link ContractRegistry.ContractType} and runs it. Returns an
 * UNCLASSIFIED diff with a clear hint when no classifier handles the type
 * (e.g. EVENT_SCHEMA in v1).
 *
 * <p>Spring auto-collects every {@link BreakingChangeClassifier} bean into
 * a list at construction; the service indexes by {@code type()} for
 * O(1) lookup at classify time.
 */
@Service
@Slf4j
public class BreakingChangeService {

    private final Map<ContractRegistry.ContractType, BreakingChangeClassifier> byType = new HashMap<>();

    public BreakingChangeService(List<BreakingChangeClassifier> classifiers) {
        for (BreakingChangeClassifier c : classifiers) {
            byType.put(c.type(), c);
        }
        log.info("[BreakingChangeService] registered {} classifier(s) for types {}",
                byType.size(), byType.keySet());
    }

    public ContractDiff classify(ContractRegistry.ContractType type,
                                 String oldContent,
                                 String newContent) {
        BreakingChangeClassifier classifier = byType.get(type);
        if (classifier == null) {
            return new ContractDiff(
                    "No classifier registered for type " + type
                            + " (deferred to v1.1 — see plan §13)",
                    List.of(),
                    ContractDiff.Confidence.UNCLASSIFIED,
                    "(none)");
        }
        return classifier.classify(oldContent, newContent);
    }

    /** Test seam — package-private; reveals which classifier handles each type. */
    Map<ContractRegistry.ContractType, BreakingChangeClassifier> registeredClassifiers() {
        return Map.copyOf(byType);
    }
}
