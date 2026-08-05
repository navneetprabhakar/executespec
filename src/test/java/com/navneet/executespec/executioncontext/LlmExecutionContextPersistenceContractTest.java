package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

class LlmExecutionContextPersistenceContractTest {

  @Test
  void entityUsesOptimisticVersionAndLifecycleLookupUsesTenantScopedWriteLock() throws Exception {
    Field version = LlmExecutionContext.class.getDeclaredField("version");
    assertThat(version.getAnnotation(Version.class)).isNotNull();

    Method findForUpdate =
        LlmExecutionContextRepository.class.getDeclaredMethod(
            "findForUpdate", String.class, String.class);
    Lock lock = findForUpdate.getAnnotation(Lock.class);
    assertThat(lock).isNotNull();
    assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void sourceAndCorrelationLengthsMatchTheValidatedCreateRequestBounds() throws Exception {
    Column sourceId =
        LlmExecutionContext.class.getDeclaredField("sourceId").getAnnotation(Column.class);
    Column correlationId =
        LlmExecutionContext.class.getDeclaredField("correlationId").getAnnotation(Column.class);

    assertThat(sourceId.length()).isEqualTo(160);
    assertThat(correlationId.length()).isEqualTo(96);
  }
}
