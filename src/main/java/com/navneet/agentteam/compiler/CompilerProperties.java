package com.navneet.agentteam.compiler;

import com.navneet.agentteam.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin delegate that exposes AgentProperties.Compiler fields as a typed bean,
 * keeping CompilerAgent decoupled from the full AgentProperties graph.
 */
@Component
@RequiredArgsConstructor
public class CompilerProperties {

    private final AgentProperties props;

    public boolean isEnabled()      { return props.getCompiler().isEnabled(); }
    public int getMaxAttempts()     { return props.getCompiler().getMaxAttempts(); }
    public int getTimeoutSeconds()  { return props.getCompiler().getTimeoutSeconds(); }
    public String getModel()        { return props.getCompiler().getModel(); }
    public String getProvider()     { return props.getCompiler().getProvider(); }
}
