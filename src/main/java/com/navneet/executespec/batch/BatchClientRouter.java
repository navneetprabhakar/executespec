package com.navneet.executespec.batch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.navneet.executespec.router.LlmProvider;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches batch calls to the right {@link BatchClient} based on provider.
 *
 * <p>Plan 5 P1 scaffolding: the EnumMap is populated by Spring from whichever {@link BatchClient}
 * beans are registered. If a provider has no client bean, {@link #forProvider(LlmProvider)} throws
 * — callers should guard with the {@code harness.v2.batch_enabled} feature flag before routing.
 */
@Component
@Slf4j
public class BatchClientRouter {

  private final Map<LlmProvider, BatchClient> clients = new EnumMap<>(LlmProvider.class);

  public BatchClientRouter(List<BatchClient> beans) {
    for (BatchClient client : beans) {
      BatchClient prior = clients.put(client.provider(), client);
      if (prior != null) {
        log.warn(
            "[BatchClientRouter] duplicate client for provider {}, replacing {} with {}",
            client.provider(),
            prior.getClass().getSimpleName(),
            client.getClass().getSimpleName());
      }
    }
    log.info("[BatchClientRouter] registered {} client(s): {}", clients.size(), clients.keySet());
  }

  public BatchClient forProvider(LlmProvider provider) {
    BatchClient client = clients.get(provider);
    if (client == null) {
      throw new NoSuchElementException(
          "No BatchClient registered for provider "
              + provider
              + ". Is harness.v2.batch_enabled true and the "
              + provider
              + " client bean wired?");
    }
    return client;
  }

  /** For tests / diagnostics. */
  public boolean hasClient(LlmProvider provider) {
    return clients.containsKey(provider);
  }
}
