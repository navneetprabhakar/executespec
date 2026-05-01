package com.navneet.executespec.onboarding;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import lombok.extern.slf4j.Slf4j;

/**
 * Wave 2.2 — in-memory registry of the sandbox sample specs.
 *
 * <p>Loads all {@code classpath:sample-specs/*.yml} at boot. The set is small (3 specs today) and
 * immutable across a JVM lifetime — no cache invalidation needed. New specs ship as additional YAML
 * files; no code change required.
 */
@Component
@Slf4j
public class SampleSpecRegistry {

  private final Map<String, SampleSpec> byId = new ConcurrentHashMap<>();

  @PostConstruct
  void load() {
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath:sample-specs/*.yml");
      Yaml yaml = new Yaml();
      for (Resource r : resources) {
        try (InputStream in = r.getInputStream()) {
          Map<String, Object> map = yaml.load(in);
          if (map == null || map.get("id") == null) {
            log.warn("Sample spec {} missing id — skipped", r.getFilename());
            continue;
          }
          SampleSpec spec =
              new SampleSpec(
                  String.valueOf(map.get("id")),
                  String.valueOf(map.getOrDefault("title", "")),
                  String.valueOf(map.getOrDefault("category", "")),
                  String.valueOf(map.getOrDefault("projectType", "")),
                  String.valueOf(map.getOrDefault("preferredProvider", "")),
                  String.valueOf(map.getOrDefault("complexity", "LOW")),
                  String.valueOf(map.getOrDefault("spec", "")));
          byId.put(spec.id(), spec);
        }
      }
      log.info("Loaded {} sandbox sample specs: {}", byId.size(), byId.keySet());
    } catch (Exception e) {
      log.error("Failed to load sample specs: {}", e.getMessage(), e);
    }
  }

  public List<SampleSpec> listAll() {
    return List.copyOf(byId.values());
  }

  public Optional<SampleSpec> findById(String id) {
    return Optional.ofNullable(byId.get(id));
  }
}
