package com.navneet.executespec.repo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Fail-closed registry. Unknown or duplicate provider adapters never silently become GitHub. */
@Component
public class ScmRepositoryClientRegistry {
  private final Map<ScmProvider, ScmRepositoryClient> clients;

  public ScmRepositoryClientRegistry(List<ScmRepositoryClient> clients) {
    EnumMap<ScmProvider, ScmRepositoryClient> indexed = new EnumMap<>(ScmProvider.class);
    for (ScmRepositoryClient client : clients) {
      if (indexed.putIfAbsent(client.provider(), client) != null) {
        throw new IllegalStateException("Duplicate SCM client: " + client.provider());
      }
    }
    this.clients = Map.copyOf(indexed);
  }

  public ScmRepositoryClient require(String provider) {
    return require(ScmProvider.require(provider));
  }

  public ScmRepositoryClient require(ScmProvider provider) {
    ScmRepositoryClient client = clients.get(provider);
    if (client == null) throw new IllegalStateException("SCM client unavailable: " + provider);
    return client;
  }
}
