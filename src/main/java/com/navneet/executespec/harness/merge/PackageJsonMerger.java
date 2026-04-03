package com.navneet.executespec.harness.merge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic merger for package.json files.
 *
 * <p>Strategy: union dependencies, devDependencies, scripts.
 * If the same package has different versions in both sides,
 * falls through to LLM resolver.
 */
@Component
@Order(3)
@Slf4j
public class PackageJsonMerger implements SpecializedMerger {

    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final List<String> MERGE_SECTIONS = List.of(
        "dependencies", "devDependencies", "peerDependencies", "scripts"
    );

    @Override
    public boolean canHandle(String filePath) {
        return filePath.endsWith("package.json");
    }

    @Override
    public Optional<String> merge(String base, String ours, String theirs) {
        try {
            Map<String, Object> baseMap = parseJson(base);
            Map<String, Object> oursMap = parseJson(ours);
            Map<String, Object> theirsMap = parseJson(theirs);

            Set<String> unionKeys = new LinkedHashSet<>();
            unionKeys.addAll(oursMap.keySet());
            unionKeys.addAll(theirsMap.keySet());

            for (String key : unionKeys) {
                if (MERGE_SECTIONS.contains(key)) {
                    Map<String, String> baseSec = getSectionAsMap(baseMap, key);
                    Map<String, String> oursSec = getSectionAsMap(oursMap, key);
                    Map<String, String> theirsSec = getSectionAsMap(theirsMap, key);

                    Map<String, String> merged = mergeSections(baseSec, oursSec, theirsSec, key);
                    if (merged == null) {
                        log.warn("Version conflict in package.json section '{}'", key);
                        return Optional.empty();
                    }
                    if (!merged.isEmpty()) {
                        oursMap.put(key, new LinkedHashMap<>(merged));
                    }
                } else {
                    // For top-level string/primitive fields (e.g. main, type), semantics:
                    // Copilot apply/fix branches (theirs) override original branches (ours)
                    Object baseVal = baseMap.get(key);
                    Object theirVal = theirsMap.get(key);
                    if (theirsMap.containsKey(key)) {
                        boolean theirsChanged = !Objects.equals(theirVal, baseVal);
                        
                        if (theirsChanged) {
                            oursMap.put(key, theirVal); // Prefer theirs if they changed it
                        } else if (!oursMap.containsKey(key)) {
                            oursMap.put(key, theirVal); // Or if we don't have it
                        }
                    } // else if they deleted it but we kept it, we keep it (conservative)
                }
            }

            return Optional.of(JSON.writeValueAsString(oursMap));
        } catch (Exception e) {
            log.debug("PackageJsonMerger failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Three-way merge of a flat string→string section.
     * Returns null if the same key has conflicting values from both sides.
     */
    private Map<String, String> mergeSections(Map<String, String> base,
                                               Map<String, String> ours,
                                               Map<String, String> theirs,
                                               String sectionName) {
        Map<String, String> result = new LinkedHashMap<>(ours);

        for (Map.Entry<String, String> entry : theirs.entrySet()) {
            String key = entry.getKey();
            String theirVal = entry.getValue();

            if (!result.containsKey(key)) {
                result.put(key, theirVal);
            } else {
                String ourVal = result.get(key);
                if (!Objects.equals(ourVal, theirVal)) {
                    String baseVal = base.getOrDefault(key, null);
                    boolean oursChanged = !Objects.equals(ourVal, baseVal);
                    boolean theirsChanged = !Objects.equals(theirVal, baseVal);
                    
                    if (oursChanged && theirsChanged) {
                        // For scripts, favor 'theirs' (copilot fix) when both changed
                        if ("scripts".equals(sectionName)) {
                            result.put(key, theirVal);
                        } else {
                            return null; // can't auto-resolve dependencies securely
                        }
                    } else if (theirsChanged) {
                        result.put(key, theirVal);
                    }
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getSectionAsMap(Map<String, Object> root, String key) {
        Object val = root.get(key);
        if (val instanceof Map) {
            Map<String, String> result = new LinkedHashMap<>();
            ((Map<String, Object>) val).forEach((k, v) -> result.put(k, String.valueOf(v)));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> parseJson(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        return JSON.readValue(json, MAP_TYPE);
    }
}
