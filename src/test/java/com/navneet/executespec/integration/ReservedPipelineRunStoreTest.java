package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ReservedPipelineRunStoreTest {

  @Test
  void assignedIdentityUsesPersistAndFlushInsteadOfMerge() {
    EntityManager entityManager = mock(EntityManager.class);
    ReservedPipelineRunStore store = new ReservedPipelineRunStore(entityManager);
    PipelineRun candidate = PipelineRun.builder().runId("reserved-run").build();

    assertThat(store.insert(candidate)).isSameAs(candidate);

    InOrder order = inOrder(entityManager);
    order.verify(entityManager).persist(candidate);
    order.verify(entityManager).flush();
  }
}
