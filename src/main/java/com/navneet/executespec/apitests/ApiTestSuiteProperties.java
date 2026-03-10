package com.navneet.executespec.apitests;

import com.navneet.executespec.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin delegate that exposes AgentProperties.ApiTestSuite fields as a typed bean.
 */
@Component
@RequiredArgsConstructor
public class ApiTestSuiteProperties {

    private final AgentProperties props;

    public boolean isEnabled()  { return props.getApiTestSuite().isEnabled(); }
    public String getModel()    { return props.getApiTestSuite().getModel(); }
    public String getProvider() { return props.getApiTestSuite().getProvider(); }
}
