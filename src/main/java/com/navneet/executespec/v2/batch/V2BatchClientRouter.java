package com.navneet.executespec.v2.batch;

import com.navneet.executespec.router.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Dispatches batch calls to the right {@link V2BatchClient} based on provider.
 *
 * <p>Plan 5 P1 scaffolding: the EnumMap is populated by Spring from whichever
 * {@link V2BatchClient} beans are registered. If a provider has no client
 * bean, {@link #forProvider(LlmProvider)} throws — callers should guard with
 * the {@code harness.v2.batch_enabled} feature flag before routing.
 */
@Component
@Slf4j
public class V2BatchClientRouter {

    private final Map<LlmProvider, V2BatchClient> clients = new EnumMap<>(LlmProvider.class);

    public V2BatchClientRouter(List<V2BatchClient> beans) {
        for (V2BatchClient client : beans) {
            V2BatchClient prior = clients.put(client.provider(), client);
            if (prior != null) {
                log.warn("[V2BatchClientRouter] duplicate client for provider {}, replacing {} with {}",
                        client.provider(), prior.getClass().getSimpleName(), client.getClass().getSimpleName());
            }
        }
        log.info("[V2BatchClientRouter] registered {} client(s): {}",
                clients.size(), clients.keySet());
    }

    public V2BatchClient forProvider(LlmProvider provider) {
        V2BatchClient client = clients.get(provider);
        if (client == null) {
            throw new NoSuchElementException(
                    "No V2BatchClient registered for provider " + provider
                            + ". Is harness.v2.batch_enabled true and the " + provider
                            + " client bean wired?");
        }
        return client;
    }

    /** For tests / diagnostics. */
    public boolean hasClient(LlmProvider provider) {
        return clients.containsKey(provider);
    }
}
