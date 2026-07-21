package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.repository.ActivityAuditRepository;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ActivityAuditAppendContractTest {

  @Test
  void obtainsCrossJvmTransactionLockBeforeReadingAndHashingChainTail() {
    ActivityAuditRepository repository = mock(ActivityAuditRepository.class);
    EntityManager entityManager = mock(EntityManager.class);
    Query lockQuery = mock(Query.class);
    Query sequenceQuery = mock(Query.class);
    when(entityManager.createNativeQuery(
            "SELECT pg_advisory_xact_lock(hashtext('executespec'),"
                + " hashtext('activity_audit_chain'))"))
        .thenReturn(lockQuery);
    when(lockQuery.getSingleResult()).thenReturn(null);
    when(entityManager.createNativeQuery("SELECT nextval('activity_audit_chain_seq')"))
        .thenReturn(sequenceQuery);
    when(sequenceQuery.getSingleResult()).thenReturn(42L);
    when(repository.findChainTail()).thenReturn(java.util.Optional.empty());
    ActivityAuditService service =
        new ActivityAuditService(repository, mock(AppUserRepository.class));
    ReflectionTestUtils.setField(service, "em", entityManager);

    service.record(
        ActivityAudit.builder()
            .eventType("CHAT_CONFIRMATION_CONSUMED")
            .entityType("PROJECT_CHAT")
            .entityId("confirmation-1")
            .build());

    var order = inOrder(lockQuery, repository);
    order.verify(lockQuery).getSingleResult();
    order.verify(repository).findChainTail();
    ArgumentCaptor<ActivityAudit> saved = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getChainSeq()).isEqualTo(42L);
    assertThat(saved.getValue().getPrevHash()).isEqualTo("0".repeat(64));
    assertThat(saved.getValue().getRowHash()).hasSize(64);
  }
}
