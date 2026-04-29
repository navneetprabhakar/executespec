package com.navneet.executespec.migration.seeder;

import com.navneet.executespec.migration.entity.MigrationDependency;
import com.navneet.executespec.migration.entity.MigrationRecipe;
import com.navneet.executespec.migration.entity.MigrationSlot;
import com.navneet.executespec.migration.entity.MigrationVariant;
import com.navneet.executespec.migration.repository.MigrationDependencyRepository;
import com.navneet.executespec.migration.repository.MigrationDryRunCacheRepository;
import com.navneet.executespec.migration.repository.MigrationRecipeRepository;
import com.navneet.executespec.migration.repository.MigrationSlotRepository;
import com.navneet.executespec.migration.repository.MigrationVariantRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * R-337 — loads the Migration Recipes catalog from
 * {@code classpath:migration-recipes/*.yml} into the database.
 *
 * <p>Mirrors {@link com.navneet.executespec.readymade.seeder.ReadymadeSeeder}
 * — same idempotency model (upsert by slug; delete-and-replace
 * variants/slots/dependencies; no auto-delete; per-file try/catch).
 * The two seeders intentionally don't share code in v1; if a third
 * seeder lands or the loaders diverge meaningfully, the JSON
 * helpers can be lifted into a shared util later.
 *
 * <p>See {@code docs/migration-recipes-catalog-format.md} for the
 * file shape.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationRecipeSeeder {

    private final MigrationRecipeRepository recipeRepo;
    private final MigrationVariantRepository variantRepo;
    private final MigrationSlotRepository slotRepo;
    private final MigrationDependencyRepository dependencyRepo;
    @SuppressWarnings("unused")
    private final MigrationDryRunCacheRepository dryRunCacheRepo;
    // ^ injected so the bean wiring stays consistent and so the
    //   reseed path can later cache-bust dry-run rows when a recipe
    //   definition changes (W2.5 follow-up).

    @PostConstruct
    public void onStartup() {
        try {
            int loaded = reseed();
            log.info("[R-337] MigrationRecipeSeeder boot-load complete — {} recipes seeded", loaded);
        } catch (Exception e) {
            log.error("[R-337] MigrationRecipeSeeder boot-load failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public int reseed() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        int loaded = 0;
        try {
            Resource[] resources = resolver.getResources("classpath:migration-recipes/*.yml");
            log.info("[R-337] discovered {} migration-recipe catalog files", resources.length);
            for (Resource r : resources) {
                try {
                    if (loadOne(r)) loaded++;
                } catch (Exception e) {
                    log.warn("[R-337] skipping {} — {}: {}",
                        r.getFilename(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[R-337] catalog discovery failed: {}", e.getMessage(), e);
        }
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private boolean loadOne(Resource r) throws Exception {
        try (InputStream in = r.getInputStream()) {
            Map<String, Object> map = new Yaml().load(in);
            if (map == null || map.get("slug") == null) {
                log.warn("[R-337] {} missing slug — skipped", r.getFilename());
                return false;
            }
            String slug = String.valueOf(map.get("slug"));

            MigrationRecipe recipe = recipeRepo.findBySlug(slug)
                .orElseGet(() -> MigrationRecipe.builder().slug(slug).build());
            applyRecipe(recipe, map);
            MigrationRecipe saved = recipeRepo.save(recipe);

            replaceVariants(saved.getId(), (List<Map<String, Object>>) map.get("variants"));
            replaceSlots(saved.getId(), (List<Map<String, Object>>) map.get("slots"));
            replaceDependencies(saved.getId(), (List<Map<String, Object>>) map.get("dependencies"));

            log.info("[R-337] seeded migration recipe {}", slug);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private void applyRecipe(MigrationRecipe r, Map<String, Object> map) {
        r.setName(String.valueOf(map.getOrDefault("name", r.getSlug())));
        r.setTagline(asString(map.get("tagline")));
        r.setDescription(asString(map.get("description")));

        // source_stack + target_stack are JSON objects in the YAML
        // (Map<String,Object>); serialize to the JSONB column.
        r.setSourceStack(toJsonObject(map.get("source_stack")));
        r.setTargetStack(toJsonObject(map.get("target_stack")));
        if (r.getSourceStack() == null || r.getTargetStack() == null) {
            throw new IllegalArgumentException(
                "recipe " + r.getSlug() + " missing source_stack or target_stack");
        }
        r.setAffectedFilesGlob(String.valueOf(map.getOrDefault("affected_files_glob", "")));

        r.setCategory(String.valueOf(map.getOrDefault("category", "uncategorised")));
        Object tags = map.get("tags");
        r.setTags(tags == null ? null : toJsonArray((List<Object>) tags));
        r.setTier(parseEnum(MigrationRecipe.Tier.class, map.get("tier"), MigrationRecipe.Tier.PRO));
        r.setSource(parseEnum(MigrationRecipe.Source.class, map.get("source"),
            MigrationRecipe.Source.FIRST_PARTY));
        r.setStatus(parseEnum(MigrationRecipe.Status.class, map.get("status"),
            MigrationRecipe.Status.LISTED));
        r.setDeterministicRecipeRef(asString(map.get("deterministic_recipe_ref")));
        r.setAiFallbackPromptPath(asString(map.get("ai_fallback_prompt_path")));
        r.setPreservationContract(toJsonObject(map.get("preservation_contract")));

        List<Object> estFiles = (List<Object>) map.get("est_files");
        if (estFiles != null && estFiles.size() == 2) {
            r.setEstFilesLow(toInt(estFiles.get(0)));
            r.setEstFilesHigh(toInt(estFiles.get(1)));
        }
        List<Object> estCredits = (List<Object>) map.get("est_credits");
        if (estCredits != null && estCredits.size() == 2) {
            r.setEstCreditsLow(toBigDecimal(estCredits.get(0)));
            r.setEstCreditsHigh(toBigDecimal(estCredits.get(1)));
        }

        r.setIconUrl(asString(map.get("icon_url")));
        r.setThumbnailUrl(asString(map.get("thumbnail_url")));
        r.setSamplePrUrl(asString(map.get("sample_pr_url")));
        r.setAuthorUserId(asString(map.get("author_user_id")));
        r.setAuthorOrgId(asString(map.get("author_org_id")));
    }

    private void replaceVariants(UUID recipeId, List<Map<String, Object>> variants) {
        variantRepo.deleteByRecipeId(recipeId);
        if (variants == null) return;
        for (Map<String, Object> v : variants) {
            String optionsSig = String.valueOf(v.getOrDefault("options_signature", "default"));
            MigrationVariant entity = MigrationVariant.builder()
                .id(UUID.randomUUID())
                .recipeId(recipeId)
                .optionsSignature(optionsSig)
                .options(toJsonObject(v.get("options")))
                .specTemplate(String.valueOf(v.getOrDefault("spec_template", "")))
                .aiFallbackPromptPath(asString(v.get("ai_fallback_prompt_path")))
                .build();
            // Variant options must be at least an empty JSON object;
            // the JSONB column is NOT NULL.
            if (entity.getOptions() == null) entity.setOptions("{}");
            @SuppressWarnings("unchecked")
            List<Object> vEstCredits = (List<Object>) v.get("est_credits");
            if (vEstCredits != null && vEstCredits.size() == 2) {
                entity.setEstCreditsLow(toBigDecimal(vEstCredits.get(0)));
                entity.setEstCreditsHigh(toBigDecimal(vEstCredits.get(1)));
            }
            variantRepo.save(entity);
        }
    }

    private void replaceSlots(UUID recipeId, List<Map<String, Object>> slots) {
        slotRepo.deleteByRecipeId(recipeId);
        if (slots == null) return;
        for (Map<String, Object> sl : slots) {
            MigrationSlot entity = MigrationSlot.builder()
                .id(UUID.randomUUID())
                .recipeId(recipeId)
                .name(String.valueOf(sl.getOrDefault("name", "")))
                .displayName(String.valueOf(sl.getOrDefault("display_name", sl.getOrDefault("name", ""))))
                .type(parseEnum(MigrationSlot.Type.class, sl.get("type"), MigrationSlot.Type.TEXT))
                .required(Boolean.TRUE.equals(sl.get("required")))
                .defaultValue(asString(sl.get("default_value")))
                .options(toJsonOptions(sl.get("options")))
                .description(asString(sl.get("description")))
                .displayOrder(toInt(sl.get("display_order"), 0))
                .build();
            slotRepo.save(entity);
        }
    }

    private void replaceDependencies(UUID recipeId, List<Map<String, Object>> deps) {
        dependencyRepo.deleteByRecipeId(recipeId);
        if (deps == null) return;
        for (Map<String, Object> d : deps) {
            String depSlug = String.valueOf(d.getOrDefault("slug", ""));
            Optional<MigrationRecipe> dep = recipeRepo.findBySlug(depSlug);
            if (dep.isEmpty()) {
                log.warn("[R-337] dependency slug '{}' for recipe {} not yet seeded — will resolve next reseed",
                    depSlug, recipeId);
                continue;
            }
            MigrationDependency entity = MigrationDependency.builder()
                .recipeId(recipeId)
                .dependsOnId(dep.get().getId())
                .kind(parseEnum(MigrationDependency.Kind.class, d.get("kind"),
                    MigrationDependency.Kind.RECOMMENDED_AFTER))
                .build();
            dependencyRepo.save(entity);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer toInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int toInt(Object o, int fallback) {
        Integer i = toInt(o);
        return i == null ? fallback : i;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> clazz, Object value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(clazz, String.valueOf(value).toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String toJsonArray(List<Object> items) {
        if (items == null || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(String.valueOf(items.get(i)))).append('"');
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonObject(Object obj) {
        if (obj == null) return null;
        if (!(obj instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>) obj;
        if (map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
            first = false;
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String toJsonOptions(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List<?> list) {
            List<Object> objList = new ArrayList<>();
            for (Object item : list) objList.add(item);
            return toJsonArray(objList);
        }
        if (obj instanceof Map) return toJsonObject(obj);
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
