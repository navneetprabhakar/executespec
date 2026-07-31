package com.navneet.executespec.contractsurface;

import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.Route;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;

/** Canonical, digest-bound Spring HTTP routes and their context-path configuration provenance. */
public record SpringHttpSurfaceSnapshot(
    int schemaVersion,
    List<Route> routes,
    List<ConfigurationSource> configurationSources,
    String digest) {
  public SpringHttpSurfaceSnapshot {
    routes = routes == null ? List.of() : List.copyOf(routes);
    configurationSources =
        configurationSources == null ? List.of() : List.copyOf(configurationSources);
  }

  static SpringHttpSurfaceSnapshot of(
      List<Route> discovered, List<ConfigurationSource> discoveredConfigurationSources) {
    List<Route> canonical =
        discovered.stream()
            .sorted(
                Comparator.comparing(Route::method)
                    .thenComparing(Route::path)
                    .thenComparing(Route::source))
            .distinct()
            .toList();
    List<ConfigurationSource> canonicalSources =
        discoveredConfigurationSources.stream()
            .sorted(Comparator.comparing(ConfigurationSource::source))
            .distinct()
            .toList();
    return new SpringHttpSurfaceSnapshot(
        2, canonical, canonicalSources, sha256(canonical, canonicalSources));
  }

  public Set<RouteKey> keys() {
    Set<RouteKey> keys = new LinkedHashSet<>();
    routes.forEach(route -> keys.add(route.key()));
    return Set.copyOf(keys);
  }

  private static String sha256(List<Route> routes, List<ConfigurationSource> sources) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      routes.forEach(
          route ->
              digest.update(
                  (route.method() + "\t" + route.path() + "\t" + route.source() + "\n")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      sources.forEach(
          source ->
              digest.update(
                  ("CONFIG\t"
                          + source.property()
                          + "\t"
                          + source.value()
                          + "\t"
                          + source.source()
                          + "\n")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public record ConfigurationSource(String source, String property, String value) {}
}
