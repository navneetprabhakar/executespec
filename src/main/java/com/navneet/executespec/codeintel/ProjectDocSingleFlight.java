package com.navneet.executespec.codeintel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Coalesces concurrent document generations that have the same immutable source and route key. */
final class ProjectDocSingleFlight<K, V> {

  private final ConcurrentHashMap<K, CompletableFuture<V>> active = new ConcurrentHashMap<>();

  V execute(K key, Supplier<V> generation) {
    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<V> existing = active.putIfAbsent(key, mine);
    if (existing != null) {
      try {
        return existing.join();
      } catch (CompletionException failure) {
        throw rethrow(failure.getCause());
      }
    }
    try {
      V generated = generation.get();
      mine.complete(generated);
      return generated;
    } catch (Throwable failure) {
      mine.completeExceptionally(failure);
      throw rethrow(failure);
    } finally {
      active.remove(key, mine);
    }
  }

  private static RuntimeException rethrow(Throwable failure) {
    return failure instanceof RuntimeException runtime
        ? runtime
        : new IllegalStateException("Project document generation failed", failure);
  }
}
