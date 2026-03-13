package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreviewSession")
class PreviewSessionTest {

    @Test
    @DisplayName("isExpired returns true when expiresAt is in the past")
    void expiredSession() {
        var session = new PreviewSession(
                "run-1", "cid", "preview-run1", PreviewProjectType.NODE_FRONTEND,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60)
        );
        assertThat(session.isExpired()).isTrue();
    }

    @Test
    @DisplayName("isExpired returns false when expiresAt is in the future")
    void activeSession() {
        var session = new PreviewSession(
                "run-1", "cid", "preview-run1", PreviewProjectType.NODE_FRONTEND,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now(), Instant.now().plusSeconds(3600)
        );
        assertThat(session.isExpired()).isFalse();
    }

    @Test
    @DisplayName("isExpired returns false when expiresAt is null (no expiry)")
    void noExpirySession() {
        var session = new PreviewSession(
                "run-1", "cid", "preview-run1", PreviewProjectType.NODE_FRONTEND,
                PreviewStatus.RUNNING, 10000, "http://localhost:10000", null,
                Instant.now(), null
        );
        assertThat(session.isExpired()).isFalse();
    }

    @Test
    @DisplayName("Record fields are accessible")
    void recordFieldAccess() {
        var session = new PreviewSession(
                "run-1", "cid123", "preview-run1", PreviewProjectType.SPRING_BOOT,
                PreviewStatus.BUILDING, 10042, "http://localhost:10042", "some error",
                Instant.now(), Instant.now().plusSeconds(900)
        );

        assertThat(session.runId()).isEqualTo("run-1");
        assertThat(session.containerId()).isEqualTo("cid123");
        assertThat(session.containerName()).isEqualTo("preview-run1");
        assertThat(session.projectType()).isEqualTo(PreviewProjectType.SPRING_BOOT);
        assertThat(session.status()).isEqualTo(PreviewStatus.BUILDING);
        assertThat(session.hostPort()).isEqualTo(10042);
        assertThat(session.url()).isEqualTo("http://localhost:10042");
        assertThat(session.errorMessage()).isEqualTo("some error");
    }
}
