package com.navneet.executespec.support;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Wraps a fixed instance in a minimal {@link ObjectProvider} for unit tests that construct a bean
 * which now takes a lazy {@code ObjectProvider<T>} dependency instead of the bean directly.
 */
public final class TestObjectProviders {
  private TestObjectProviders() {}

  public static <T> ObjectProvider<T> of(T instance) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return instance;
      }

      @Override
      public T getObject(Object... args) {
        return instance;
      }

      @Override
      public T getIfAvailable() {
        return instance;
      }

      @Override
      public T getIfUnique() {
        return instance;
      }
    };
  }
}
