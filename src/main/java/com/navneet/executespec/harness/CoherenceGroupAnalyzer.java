package com.navneet.executespec.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Groups planned output files into coherence sets using naming heuristics and UnionFind.
 *
 * <p>Since planned files don't exist yet (we can't parse imports), this uses
 * filename patterns to identify naturally coupled file pairs:
 * <ul>
 *   <li>Interface + Implementation: {@code UserService.java} + {@code UserServiceImpl.java}</li>
 *   <li>Source + Test: {@code UserService.java} + {@code UserServiceTest.java}</li>
 *   <li>Source + Spec: {@code user.component.ts} + {@code user.component.spec.ts}</li>
 *   <li>Model + Migration: {@code User.java} + {@code V*__create_users.sql}</li>
 *   <li>Component + styles: {@code user.component.ts} + {@code user.component.css/scss}</li>
 *   <li>Schema + Resolver: {@code schema.graphql} + {@code *Resolver.java}</li>
 *   <li>Same directory + shared base name: files sharing a stem in the same directory</li>
 * </ul>
 *
 * <p>Groups are capped at {@link #MAX_GROUP_SIZE} files. Groups of 1–3 files
 * become one subtask; larger groups are split.
 */
@Component
@Slf4j
public class CoherenceGroupAnalyzer {

    /** Max files per coherence group (produces one subtask). */
    public static final int MAX_GROUP_SIZE = 3;

    /**
     * Partitions {@code files} into coherence groups. Files in the same group
     * should be implemented together in a single subtask.
     *
     * @param files list of planned file paths (relative to workspace root)
     * @return list of groups, each containing 1..MAX_GROUP_SIZE file paths
     */
    public List<List<String>> analyze(List<String> files) {
        if (files == null || files.isEmpty()) return List.of();
        if (files.size() == 1) return List.of(new ArrayList<>(files));

        UnionFind uf = new UnionFind(files);

        // Pass 1: union pairs by naming heuristics
        for (int i = 0; i < files.size(); i++) {
            for (int j = i + 1; j < files.size(); j++) {
                if (areCoherent(files.get(i), files.get(j))) {
                    uf.union(files.get(i), files.get(j));
                }
            }
        }

        // Collect groups
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String f : files) {
            String root = uf.find(f);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(f);
        }

        // Split groups larger than MAX_GROUP_SIZE
        List<List<String>> result = new ArrayList<>();
        for (List<String> group : groups.values()) {
            if (group.size() <= MAX_GROUP_SIZE) {
                result.add(group);
            } else {
                // Split into chunks of MAX_GROUP_SIZE
                for (int i = 0; i < group.size(); i += MAX_GROUP_SIZE) {
                    result.add(new ArrayList<>(group.subList(i, Math.min(i + MAX_GROUP_SIZE, group.size()))));
                }
            }
        }

        log.debug("[CoherenceAnalyzer] {} files → {} coherence groups", files.size(), result.size());
        return result;
    }

    // ── Heuristics ────────────────────────────────────────────────────────────

    private boolean areCoherent(String a, String b) {
        String nameA = fileName(a);
        String nameB = fileName(b);
        String stemA = stem(nameA);
        String stemB = stem(nameB);
        String dirA  = dir(a);
        String dirB  = dir(b);

        // Interface + Implementation (UserService + UserServiceImpl)
        if (stemB.equals(stemA + "Impl") || stemA.equals(stemB + "Impl")) return true;

        // Source + Test (UserService + UserServiceTest / UserServiceTests)
        if (stemB.equals(stemA + "Test") || stemB.equals(stemA + "Tests")
         || stemA.equals(stemB + "Test") || stemA.equals(stemB + "Tests")) return true;

        // TypeScript spec (user.component + user.component.spec)
        if (nameA.equals(nameB.replace(".spec.", "."))
         || nameB.equals(nameA.replace(".spec.", "."))) return true;

        // Component + stylesheet (user.component.ts + user.component.css/scss/less)
        if (isSameComponentStyle(nameA, nameB) || isSameComponentStyle(nameB, nameA)) return true;

        // Same directory + same stem (different extension: e.g. schema.graphql + schema.ts)
        if (dirA.equals(dirB) && !stemA.isEmpty() && stemA.equalsIgnoreCase(stemB)
                && !extOf(nameA).equals(extOf(nameB))) return true;

        // Migration + entity: V*__create_users.sql near User.java (same entity name)
        if (isMigrationEntityPair(a, nameA, b, nameB)) return true;

        return false;
    }

    private boolean isSameComponentStyle(String component, String style) {
        // user.component.ts + user.component.scss
        if (!component.endsWith(".ts") && !component.endsWith(".tsx")) return false;
        String withoutExt = component.replaceAll("\\.(ts|tsx)$", "");
        return style.equals(withoutExt + ".css")
            || style.equals(withoutExt + ".scss")
            || style.equals(withoutExt + ".less")
            || style.equals(withoutExt + ".module.css")
            || style.equals(withoutExt + ".module.scss");
    }

    private boolean isMigrationEntityPair(String pathA, String nameA, String pathB, String nameB) {
        String migName = null, entityName = null;
        String migPath = null, entityPath = null;

        if (nameA.matches("V\\d+__.*\\.sql")) { migName = nameA; migPath = pathA; }
        if (nameB.matches("V\\d+__.*\\.sql")) { migName = nameB; migPath = pathB; }
        if (migName == null) return false;

        entityName = nameA.equals(migName) ? nameB : nameA;
        entityPath = nameA.equals(migName) ? pathB : pathA;

        // Extract table name from migration: V1__create_users.sql → users → User
        String tableName = migName.replaceAll("V\\d+__", "").replace(".sql", "");
        // Remove common prefixes: create_, add_, alter_
        tableName = tableName.replaceAll("^(create|add|alter|update)_", "");
        String singular = tableName.endsWith("s") ? tableName.substring(0, tableName.length() - 1) : tableName;

        String entityStem = stem(entityName).toLowerCase();
        return entityStem.equals(singular.toLowerCase())
            || entityStem.equals(tableName.toLowerCase());
    }

    // ── File path utilities ───────────────────────────────────────────────────

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String dir(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private static String stem(String fileName) {
        int dot = fileName.indexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }

    // ── UnionFind ─────────────────────────────────────────────────────────────

    private static class UnionFind {
        private final Map<String, String> parent = new LinkedHashMap<>();
        private final Map<String, Integer> rank   = new HashMap<>();

        UnionFind(List<String> elements) {
            for (String e : elements) { parent.put(e, e); rank.put(e, 0); }
        }

        String find(String x) {
            if (!parent.get(x).equals(x)) parent.put(x, find(parent.get(x)));
            return parent.get(x);
        }

        void union(String a, String b) {
            String ra = find(a), rb = find(b);
            if (ra.equals(rb)) return;
            int rankA = rank.getOrDefault(ra, 0), rankB = rank.getOrDefault(rb, 0);
            if (rankA < rankB) { parent.put(ra, rb); }
            else if (rankA > rankB) { parent.put(rb, ra); }
            else { parent.put(rb, ra); rank.merge(ra, 1, Integer::sum); }
        }
    }
}
