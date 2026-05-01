package com.navneet.executespec.deployment.strategy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class StaticDeploymentStrategy implements DeploymentStrategy {

  @Override
  public Deployment.DeployStrategy type() {
    return Deployment.DeployStrategy.STATIC;
  }

  @Override
  public boolean applies(Path workspacePath) {
    return Files.exists(workspacePath.resolve("index.html"))
        && !Files.exists(workspacePath.resolve("pom.xml"));
  }

  @Override
  public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
    String nginxImage = props.staticSite().nginxImage();
    return """
           FROM %s
           COPY . /usr/share/nginx/html
           COPY nginx.conf /etc/nginx/conf.d/default.conf
           EXPOSE 8080
           CMD ["nginx", "-g", "daemon off;"]
           """
        .formatted(nginxImage);
  }

  @Override
  public String detectionSummary(Path workspacePath) {
    return "Static site detected (index.html found, no pom.xml)";
  }
}
