package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProviderModelCatalogBindingTest {

  @Test
  void applicationYamlBindsAndPassesRegistryStartupValidation() throws Exception {
    var environment = new StandardEnvironment();
    List<org.springframework.core.env.PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    for (int index = sources.size() - 1; index >= 0; index--) {
      environment.getPropertySources().addFirst(sources.get(index));
    }
    ConfigurationPropertySources.attach(environment);

    ModelMatrixProperties properties =
        Binder.get(environment)
            .bind("model-matrix", Bindable.of(ModelMatrixProperties.class))
            .orElseThrow(() -> new IllegalStateException("model-matrix did not bind"));
    ModelRegistry registry = new ModelRegistry(properties);

    registry.validate();
    assertThat(registry.canonicalize("gemini-3-flash-preview")).isEqualTo("gemini-3.6-flash");
    assertThat(registry.canonicalize("gpt-5.5-mini")).isEqualTo("gpt-5.6-luna");
    assertThat(properties.getProviders().get("fireworks").getLightweight())
        .isEqualTo("deepseek-v4p1-flash-1017-fw");
  }
}
