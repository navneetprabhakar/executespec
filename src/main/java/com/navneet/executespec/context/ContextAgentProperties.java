package com.navneet.executespec.context;
import com.navneet.executespec.config.ModelConstants;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.context")
@Data
public class ContextAgentProperties {

    private boolean enabled = true;
    private String model = ModelConstants.HAIKU;
    private String provider = "anthropic";

    /** Run after every N completed runs. 1 = every run. */
    private int runInterval = 1;
}
