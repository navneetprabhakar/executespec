package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PreviewProjectType")
class PreviewProjectTypeTest {

    @Test
    @DisplayName("NODE_FRONTEND exposes port 5173 (Vite default)")
    void nodeFrontendPort() {
        assertThat(PreviewProjectType.NODE_FRONTEND.getContainerPort()).isEqualTo(5173);
    }

    @Test
    @DisplayName("NODE_BACKEND exposes port 3000")
    void nodeBackendPort() {
        assertThat(PreviewProjectType.NODE_BACKEND.getContainerPort()).isEqualTo(3000);
    }

    @Test
    @DisplayName("SPRING_BOOT exposes port 8080")
    void springBootPort() {
        assertThat(PreviewProjectType.SPRING_BOOT.getContainerPort()).isEqualTo(8080);
    }

    @Test
    @DisplayName("PYTHON exposes port 8000")
    void pythonPort() {
        assertThat(PreviewProjectType.PYTHON.getContainerPort()).isEqualTo(8000);
    }

    @Test
    @DisplayName("FULL_STACK exposes port 5173 (same as frontend — primary preview)")
    void fullStackPort() {
        assertThat(PreviewProjectType.FULL_STACK.getContainerPort()).isEqualTo(5173);
    }

    @Test
    @DisplayName("Single-project types have distinct container ports")
    void distinctSingleProjectPorts() {
        // FULL_STACK intentionally shares 5173 with NODE_FRONTEND since the primary preview is frontend
        var ports = java.util.Arrays.stream(PreviewProjectType.values())
                .filter(t -> t != PreviewProjectType.FULL_STACK)
                .map(PreviewProjectType::getContainerPort)
                .toList();
        assertThat(ports).doesNotHaveDuplicates();
    }
}
