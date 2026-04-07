package com.navneet.executespec.deployment.preview.vm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "preview.vm")
@Data
public class PreviewVmProperties {

    private boolean enabled = false;

    private GatewayConfig gateway = new GatewayConfig();

    private List<VmConfig> vms = new ArrayList<>();

    private int defaultTtlHours = 2;

    @Data
    public static class GatewayConfig {
        private String ip;
        private String internalIp;
        private String user = "ubuntu";
        private String sshKeyPath;
        private String vmId = "preview-gw";
    }

    @Data
    public static class VmConfig {
        private String id;
        private String externalIp;
        private String internalIp;
        private int maxConcurrent = 10;
        private String role = "gateway";
    }
}
