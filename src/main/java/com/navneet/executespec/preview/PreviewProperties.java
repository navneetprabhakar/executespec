package com.navneet.executespec.preview;

import com.navneet.executespec.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin delegate that exposes AgentProperties.Preview fields as a typed bean.
 */
@Component
@RequiredArgsConstructor
public class PreviewProperties {

    private final AgentProperties props;

    public boolean isEnabled() { return props.getPreview().isEnabled(); }
}
