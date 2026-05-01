package com.navneet.executespec.tenant;

import java.lang.reflect.Method;

import jakarta.persistence.PrePersist;

import lombok.extern.slf4j.Slf4j;

/**
 * JPA entity listener that automatically populates the {@code tenantId} field on any entity before
 * it is first persisted.
 *
 * <p>Apply this listener to tenant-scoped entities via:
 *
 * <pre>{@code
 * @EntityListeners(TenantInterceptor.class)
 * }</pre>
 *
 * <p>The entity must expose a {@code setTenantId(String)} setter (Lombok {@code @Data} generates
 * this automatically). If the current {@code tenantId} is already set to a non-default value, it is
 * left unchanged — callers may provide an explicit tenant at construction time.
 */
@Slf4j
public class TenantInterceptor {

  /**
   * Called by the JPA provider before any {@code INSERT}. Sets {@code tenantId} from {@link
   * TenantContext} if the entity's current value is {@code null} or {@code "default"}.
   *
   * @param entity the entity about to be persisted
   */
  @PrePersist
  public void prePersist(Object entity) {
    try {
      Method getter = entity.getClass().getMethod("getTenantId");
      Object current = getter.invoke(entity);
      if (current == null || "default".equals(current)) {
        Method setter = entity.getClass().getMethod("setTenantId", String.class);
        setter.invoke(entity, TenantContext.getTenantId());
      }
    } catch (NoSuchMethodException ignored) {
      // Entity does not have tenantId — nothing to do.
    } catch (Exception e) {
      log.warn(
          "[TenantInterceptor] Could not set tenantId on {}: {}",
          entity.getClass().getSimpleName(),
          e.getMessage());
    }
  }
}
