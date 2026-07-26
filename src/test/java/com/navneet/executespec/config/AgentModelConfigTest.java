package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.context.ApplicationContext;

/** Pins optional provider startup behaviour when a default system key is absent. */
class AgentModelConfigTest {

  @Test
  void missingAnthropicBeanDoesNotAbortRoleBeanCreation() {
    AgentProperties properties = new AgentProperties();
    ApplicationContext context = mock(ApplicationContext.class);

    AgentModelConfig config = new AgentModelConfig(properties, context, mock(ModelRegistry.class));

    assertThat(config.plannerModel()).isNull();
  }

  @Test
  void configuredAnthropicBeanIsReturnedUnchanged() {
    AgentProperties properties = new AgentProperties();
    ApplicationContext context = mock(ApplicationContext.class);
    AnthropicChatModel model = mock(AnthropicChatModel.class);
    when(context.getBean("anthropicChatModel", AnthropicChatModel.class)).thenReturn(model);

    AgentModelConfig config = new AgentModelConfig(properties, context, mock(ModelRegistry.class));

    assertThat(config.plannerModel()).isSameAs(model);
  }
}
