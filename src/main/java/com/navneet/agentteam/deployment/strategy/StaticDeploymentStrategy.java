package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
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
            """.formatted(nginxImage);
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        return "Static site detected (index.html found, no pom.xml)";
    }
}
