package com.navneet.executespec.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import jakarta.persistence.Column;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

/**
 * Reflection guard for the 2026-04-26 jsonb-binding regression.
 *
 * <p>{@code UserActivationEvent.propertiesJson} maps to a PostgreSQL JSONB column. Without
 * {@code @JdbcTypeCode(SqlTypes.JSON)} Hibernate binds the Java {@code String} field as a VARCHAR
 * parameter, and PostgreSQL refuses the implicit coercion with {@code ERROR: column
 * "properties_json" is of type jsonb but expression is of type character varying}.
 *
 * <p>Every activation event insert was failing silently between V125 ship (2026-04-22) and this fix
 * (2026-04-26). This test fails fast if the annotation is removed again.
 */
class UserActivationEventJsonbBindingTest {

  @Test
  void propertiesJsonHasJdbcTypeCode_jsonAnnotation() throws NoSuchFieldException {
    Field field = UserActivationEvent.class.getDeclaredField("propertiesJson");

    // The columnDefinition stays as "jsonb" for DDL generation.
    Column column = field.getAnnotation(Column.class);
    assertThat(column).isNotNull();
    assertThat(column.columnDefinition()).isEqualTo("jsonb");

    // The runtime binding requires @JdbcTypeCode(SqlTypes.JSON) — that's the
    // half that was missing. Without it, Hibernate binds VARCHAR.
    JdbcTypeCode jdbcTypeCode = field.getAnnotation(JdbcTypeCode.class);
    assertThat(jdbcTypeCode)
        .as(
            "@JdbcTypeCode(SqlTypes.JSON) is required so Hibernate binds the String "
                + "as JSONB at runtime — columnDefinition='jsonb' alone only affects DDL "
                + "generation. Removing this annotation re-introduces the silent "
                + "activation-funnel failure from 2026-04-22 → 2026-04-26.")
        .isNotNull();
    assertThat(jdbcTypeCode.value()).isEqualTo(SqlTypes.JSON);
  }
}
