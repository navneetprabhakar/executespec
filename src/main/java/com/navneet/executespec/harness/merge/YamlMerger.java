package com.navneet.executespec.harness.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Deterministic merger for YAML files (application.yml, docker-compose.yml, etc.).
 *
 * <p>Strategy: deep-merge maps from both sides. If the same scalar key has
 * different values from ours and theirs (and it was changed from base),
 * falls through to LLM resolver (returns empty).
 */
@Component
@Order(2)
@Slf4j
public class YamlMerger implements SpecializedMerger {

    @Override
    public boolean canHandle(String filePath) {
        return filePath.endsWith(".yml") || filePath.endsWith(".yaml");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> merge(String base, String ours, String theirs) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> baseMap = yaml.load(base);
            Map<String, Object> oursMap = yaml.load(ours);
            Map<String, Object> theirsMap = yaml.load(theirs);

            if (baseMap == null) baseMap = Map.of();
            if (oursMap == null) oursMap = new LinkedHashMap<>();
            if (theirsMap == null) theirsMap = Map.of();

            Map<String, Object> merged = deepMerge(oursMap, theirsMap, baseMap);
            if (merged == null) return Optional.empty(); // unresolvable scalar conflict

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            Yaml dumper = new Yaml(options);
            return Optional.of(dumper.dump(merged));
        } catch (Exception e) {
            log.debug("YamlMerger failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Three-way deep merge.
     * Returns null if an unresolvable scalar conflict is detected.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> ours,
                                           Map<String, Object> theirs,
                                           Map<String, Object> base) {
        Map<String, Object> result = new LinkedHashMap<>(ours);

        for (Map.Entry<String, Object> entry : theirs.entrySet()) {
            String key = entry.getKey();
            Object theirVal = entry.getValue();
            Object ourVal = result.get(key);
            Object baseVal = base != null ? base.get(key) : null;

            if (!result.containsKey(key)) {
                // Key only in theirs — add it
                result.put(key, theirVal);
            } else if (ourVal instanceof Map && theirVal instanceof Map) {
                // Both have nested maps — recurse
                Map<String, Object> mergedChild = deepMerge(
                    (Map<String, Object>) ourVal,
                    (Map<String, Object>) theirVal,
                    baseVal instanceof Map ? (Map<String, Object>) baseVal : Map.of()
                );
                if (mergedChild == null) return null;
                result.put(key, mergedChild);
            } else if (ourVal instanceof List && theirVal instanceof List) {
                // Both lists — union elements
                Set<Object> union = new LinkedHashSet<>((List<?>) ourVal);
                union.addAll((List<?>) theirVal);
                result.put(key, new ArrayList<>(union));
            } else if (!Objects.equals(ourVal, theirVal)) {
                // Scalar conflict — check if only one side changed from base
                boolean oursChanged = !Objects.equals(ourVal, baseVal);
                boolean theirsChanged = !Objects.equals(theirVal, baseVal);
                if (oursChanged && theirsChanged) {
                    // Both sides changed the same scalar — can't auto-resolve
                    log.warn("YAML scalar conflict for key '{}': ours={} theirs={}", key, ourVal, theirVal);
                    return null;
                }
                if (theirsChanged) {
                    // Only theirs changed — take theirs
                    result.put(key, theirVal);
                }
                // Only ours changed — keep ours (already in result)
            }
        }
        return result;
    }
}
