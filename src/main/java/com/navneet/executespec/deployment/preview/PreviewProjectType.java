package com.navneet.executespec.deployment.preview;

import lombok.Getter;

@Getter
public enum PreviewProjectType {
    NODE_FRONTEND(5173),
    NODE_BACKEND(3000),
    SPRING_BOOT(8080),
    PYTHON(8000),
    FULL_STACK(5173); // primary preview port is the frontend

    private final int containerPort;

    PreviewProjectType(int containerPort) {
        this.containerPort = containerPort;
    }
}
