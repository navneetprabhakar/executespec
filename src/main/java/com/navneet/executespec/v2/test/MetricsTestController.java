package com.navneet.executespec.v2.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/**
 * V2 Phase 10 verification endpoint.
 *
 * <p>Dumps every currently-registered {@code executespec.v2.*} meter with its current value(s). Use
 * after running any V2 scenario (e.g. {@code /api/v2/runs} or {@code /api/v2/test/executor}) to
 * confirm the expected metrics were emitted.
 *
 * <p>Also useful as a "did I forget to instrument something?" probe — if you expect a metric and
 * it's missing from the dump after running a scenario, the instrumentation wasn't wired up
 * correctly.
 *
 * <p>Prometheus scrape remains the source of truth for dashboards — this endpoint is purely for
 * diagnostic introspection during V2 rollout.
 *
 * <p>Usage: {@code GET /api/v2/test/metrics}
 */
// Audit r5 (§6.2, 2026-05-06) — see ContextStoreTestController for rationale.
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
public class MetricsTestController {

  private static final String V2_PREFIX = "executespec.v2.";

  private final MeterRegistry registry;

  @GetMapping("/metrics")
  public Map<String, Object> dumpV2Metrics() {
    List<Meter> v2Meters =
        registry.getMeters().stream()
            .filter(m -> m.getId().getName().startsWith(V2_PREFIX))
            .sorted(Comparator.comparing(m -> m.getId().getName()))
            .collect(Collectors.toList());

    List<Map<String, Object>> entries = new ArrayList<>();
    for (Meter m : v2Meters) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("name", m.getId().getName());
      e.put("type", m.getId().getType().name());
      e.put(
          "tags",
          m.getId().getTags().stream()
              .collect(
                  LinkedHashMap::new,
                  (map, t) -> map.put(t.getKey(), t.getValue()),
                  LinkedHashMap::putAll));
      // Collect current measurements (count / total / mean / max).
      Map<String, Double> measures = new LinkedHashMap<>();
      m.measure().forEach(ms -> measures.put(ms.getStatistic().name(), ms.getValue()));
      e.put("measurements", measures);
      entries.add(e);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v2MetricCount", entries.size());
    out.put("prefix", V2_PREFIX);
    out.put("meters", entries);
    out.put(
        "note",
        "Prometheus scrape at /actuator/prometheus is the canonical "
            + "time-series source. This endpoint is for diagnostic dumps during rollout.");
    return out;
  }
}
