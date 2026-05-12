package com.navneet.executespec.readymade.seeder;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.readymade.entity.ReadymadeDependency;
import com.navneet.executespec.readymade.entity.ReadymadeSlot;
import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeVariant;
import com.navneet.executespec.readymade.repository.ReadymadeDependencyRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSlotRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSolutionRepository;
import com.navneet.executespec.readymade.repository.ReadymadeVariantRepository;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.navneet.executespec.util.SafeYamlLoader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-328 — loads the Readymade Solutions catalog from {@code classpath:readymades/*.yml} into the
 * database.
 *
 * <p>Runs once on boot via {@link #onStartup()} and on-demand from {@link
 * com.navneet.executespec.readymade.controller.ReadymadeAdminController} (SUPER_ADMIN only). See
 * {@code docs/readymades-catalog-format.md} for the file shape.
 *
 * <p><strong>Idempotency model:</strong>
 *
 * <ul>
 *   <li>Solutions are upserted by {@code slug} — existing rows are updated, new ones inserted,
 *       absent slugs are NOT auto-deleted (use {@code status: ARCHIVED} to retire).
 *   <li>Variants / slots / dependencies are <em>delete-and-replace</em> per parent solution row on
 *       every reseed (cleaner than diffing, and v1 has no external references to those child IDs
 *       yet).
 *   <li>One bad YAML doesn't fail the whole load — failures are logged + skipped per-file.
 * </ul>
 *
 * <p>The seeder is intentionally tolerant of community-contributed malformed YAML; the W7
 * contribution path validates much more strictly before promoting submissions into the seed-loaded
 * path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadymadeSeeder {

  private final ReadymadeSolutionRepository solutionRepo;
  private final ReadymadeVariantRepository variantRepo;
  private final ReadymadeSlotRepository slotRepo;
  private final ReadymadeDependencyRepository dependencyRepo;

  /** Boot-time hook. */
  @PostConstruct
  public void onStartup() {
    try {
      int loaded = reseed();
      log.info("[R-328] ReadymadeSeeder boot-load complete — {} solutions seeded", loaded);
    } catch (Exception e) {
      log.error("[R-328] ReadymadeSeeder boot-load failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Reseeds every YAML under {@code classpath:readymades/*.yml}. Returns the count of solutions
   * successfully upserted.
   *
   * <p>Wrapped in a single transaction so a partial failure doesn't leave the catalog in an
   * inconsistent state — though per-file try/catch means most errors are recoverable individually.
   */
  @Transactional
  public int reseed() {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    int loaded = 0;
    try {
      Resource[] resources = resolver.getResources("classpath:readymades/*.yml");
      log.info("[R-328] discovered {} readymade catalog files", resources.length);
      for (Resource r : resources) {
        try {
          if (loadOne(r)) loaded++;
        } catch (Exception e) {
          log.warn(
              "[R-328] skipping {} — {}: {}",
              r.getFilename(),
              e.getClass().getSimpleName(),
              e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("[R-328] catalog discovery failed: {}", e.getMessage(), e);
    }
    return loaded;
  }

  @SuppressWarnings("unchecked")
  private boolean loadOne(Resource r) throws Exception {
    try (InputStream in = r.getInputStream()) {
      Map<String, Object> map = (Map<String, Object>) SafeYamlLoader.load(in);
      if (map == null || map.get("slug") == null) {
        log.warn("[R-328] {} missing slug — skipped", r.getFilename());
        return false;
      }
      String slug = String.valueOf(map.get("slug"));

      // Upsert solution by slug.
      ReadymadeSolution solution =
          solutionRepo
              .findBySlug(slug)
              .orElseGet(() -> ReadymadeSolution.builder().slug(slug).build());
      applySolution(solution, map);
      ReadymadeSolution saved = solutionRepo.save(solution);

      // Delete + replace variants / slots / dependencies.
      replaceVariants(saved.getId(), (List<Map<String, Object>>) map.get("variants"));
      replaceSlots(saved.getId(), (List<Map<String, Object>>) map.get("slots"));
      replaceDependencies(saved.getId(), (List<Map<String, Object>>) map.get("dependencies"));

      log.info("[R-328] seeded readymade {}", slug);
      return true;
    }
  }

  @SuppressWarnings("unchecked")
  private void applySolution(ReadymadeSolution s, Map<String, Object> map) {
    s.setName(String.valueOf(map.getOrDefault("name", s.getSlug())));
    s.setTagline(asString(map.get("tagline")));
    s.setDescription(asString(map.get("description")));
    s.setCategory(String.valueOf(map.getOrDefault("category", "uncategorised")));
    s.setMacroGroup(String.valueOf(map.getOrDefault("macro_group", "uncategorised")));
    Object tags = map.get("tags");
    s.setTags(tags == null ? null : toJsonArray((List<Object>) tags));
    s.setKind(
        parseEnum(ReadymadeSolution.Kind.class, map.get("kind"), ReadymadeSolution.Kind.PRIMITIVE));
    s.setTier(parseEnum(ReadymadeSolution.Tier.class, map.get("tier"), ReadymadeSolution.Tier.PRO));
    s.setSource(
        parseEnum(
            ReadymadeSolution.Source.class,
            map.get("source"),
            ReadymadeSolution.Source.FIRST_PARTY));
    s.setStatus(
        parseEnum(
            ReadymadeSolution.Status.class, map.get("status"), ReadymadeSolution.Status.LISTED));
    s.setIconUrl(asString(map.get("icon_url")));
    s.setThumbnailUrl(asString(map.get("thumbnail_url")));
    s.setSamplePrUrl(asString(map.get("sample_pr_url")));

    List<Object> estFiles = (List<Object>) map.get("est_files");
    if (estFiles != null && estFiles.size() == 2) {
      s.setEstFilesLow(toInt(estFiles.get(0)));
      s.setEstFilesHigh(toInt(estFiles.get(1)));
    }
    List<Object> estCredits = (List<Object>) map.get("est_credits");
    if (estCredits != null && estCredits.size() == 2) {
      s.setEstCreditsLow(toBigDecimal(estCredits.get(0)));
      s.setEstCreditsHigh(toBigDecimal(estCredits.get(1)));
    }

    s.setAuthorUserId(asString(map.get("author_user_id")));
    s.setAuthorOrgId(asString(map.get("author_org_id")));
  }

  private void replaceVariants(UUID solutionId, List<Map<String, Object>> variants) {
    variantRepo.deleteBySolutionId(solutionId);
    if (variants == null) return;
    for (Map<String, Object> v : variants) {
      ReadymadeVariant entity =
          ReadymadeVariant.builder()
              .id(UUID.randomUUID())
              .solutionId(solutionId)
              .language(String.valueOf(v.getOrDefault("language", "unknown")))
              .framework(String.valueOf(v.getOrDefault("framework", "unknown")))
              .options(toJsonObject(v.get("options")))
              .specTemplate(String.valueOf(v.getOrDefault("spec_template", "")))
              .scaffoldKey(asString(v.get("scaffold_key")))
              .build();
      @SuppressWarnings("unchecked")
      List<Object> vEstCredits = (List<Object>) v.get("est_credits");
      if (vEstCredits != null && vEstCredits.size() == 2) {
        entity.setEstCreditsLow(toBigDecimal(vEstCredits.get(0)));
        entity.setEstCreditsHigh(toBigDecimal(vEstCredits.get(1)));
      }
      variantRepo.save(entity);
    }
  }

  private void replaceSlots(UUID solutionId, List<Map<String, Object>> slots) {
    slotRepo.deleteBySolutionId(solutionId);
    if (slots == null) return;
    for (Map<String, Object> sl : slots) {
      ReadymadeSlot entity =
          ReadymadeSlot.builder()
              .id(UUID.randomUUID())
              .solutionId(solutionId)
              .name(String.valueOf(sl.getOrDefault("name", "")))
              .displayName(
                  String.valueOf(sl.getOrDefault("display_name", sl.getOrDefault("name", ""))))
              .type(parseEnum(ReadymadeSlot.Type.class, sl.get("type"), ReadymadeSlot.Type.TEXT))
              .required(Boolean.TRUE.equals(sl.get("required")))
              .defaultValue(asString(sl.get("default_value")))
              .options(toJsonOptions(sl.get("options")))
              .description(asString(sl.get("description")))
              .displayOrder(toInt(sl.get("display_order"), 0))
              .build();
      slotRepo.save(entity);
    }
  }

  private void replaceDependencies(UUID solutionId, List<Map<String, Object>> deps) {
    dependencyRepo.deleteBySolutionId(solutionId);
    if (deps == null) return;
    for (Map<String, Object> d : deps) {
      String depSlug = String.valueOf(d.getOrDefault("slug", ""));
      Optional<ReadymadeSolution> dep = solutionRepo.findBySlug(depSlug);
      if (dep.isEmpty()) {
        log.warn(
            "[R-328] dependency slug '{}' for solution {} not yet seeded — will resolve next"
                + " reseed",
            depSlug,
            solutionId);
        continue;
      }
      ReadymadeDependency entity =
          ReadymadeDependency.builder()
              .solutionId(solutionId)
              .dependsOnId(dep.get().getId())
              .required(!Boolean.FALSE.equals(d.get("required"))) // default true
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

  /** Serialize a List<Object> to a JSON array string for JSONB columns. */
  private static String toJsonArray(List<Object> items) {
    if (items == null || items.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(escape(String.valueOf(items.get(i)))).append('"');
    }
    return sb.append(']').toString();
  }

  /** Serialize a Map (or null) to a JSON object string for JSONB columns. */
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

  /** Slot options can be a list (for SELECT) or null. */
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
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
