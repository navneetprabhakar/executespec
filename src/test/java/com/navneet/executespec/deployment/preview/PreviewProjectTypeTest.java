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
    @DisplayName("All enum values have distinct container ports")
    void distinctPorts() {
        var ports = java.util.Arrays.stream(PreviewProjectType.values())
                .map(PreviewProjectType::getContainerPort)
                .toList();
        assertThat(ports).doesNotHaveDuplicates();
    }
}
