package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.QueueRequirement;
import com.navneet.executespec.deployment.analyzer.InfrastructureManifest.QueueRequirement.QueueType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Detects message queue dependencies (Kafka, RabbitMQ, PubSub) in a workspace.
 */
@Component
@Slf4j
public class QueueDetector {

    // Kafka
    private static final Pattern KAFKA_MAVEN = Pattern.compile(
            "spring-kafka|kafka-clients|org\\.apache\\.kafka");
    private static final Pattern KAFKA_NPM = Pattern.compile(
            "\"(kafkajs|node-rdkafka|@nestjs/microservices)\"");
    private static final Pattern KAFKA_PYTHON = Pattern.compile(
            "kafka-python|confluent-kafka|aiokafka|faust");
    private static final Pattern KAFKA_SPRING = Pattern.compile("spring\\.kafka\\.");

    // RabbitMQ
    private static final Pattern RABBITMQ_MAVEN = Pattern.compile(
            "spring-boot-starter-amqp|amqp-client|spring-rabbit");
    private static final Pattern RABBITMQ_NPM = Pattern.compile(
            "\"(amqplib|amqp-connection-manager|@nestjs/microservices)\"");
    private static final Pattern RABBITMQ_PYTHON = Pattern.compile(
            "pika|aio-pika|celery\\[rabbitmq\\]|kombu");
    private static final Pattern RABBITMQ_SPRING = Pattern.compile("spring\\.rabbitmq\\.");

    // GCP PubSub
    private static final Pattern PUBSUB_MAVEN = Pattern.compile(
            "spring-cloud-gcp-pubsub|google-cloud-pubsub");
    private static final Pattern PUBSUB_NPM = Pattern.compile(
            "\"@google-cloud/pubsub\"");
    private static final Pattern PUBSUB_PYTHON = Pattern.compile(
            "google-cloud-pubsub");

    public QueueRequirement detect(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;

        QueueRequirement result = detectInDir(workspace);
        if (result != null) return result;

        for (String subDir : new String[]{"backend", "server", "api", "app"}) {
            Path sub = workspace.resolve(subDir);
            if (Files.isDirectory(sub)) {
                result = detectInDir(sub);
                if (result != null) {
                    log.info("Queue detected in subdirectory {}: {}", subDir, result.type());
                    return result;
                }
            }
        }
        return null;
    }

    private QueueRequirement detectInDir(Path dir) {
        // Spring config
        for (String cfg : new String[]{
                "src/main/resources/application.yml",
                "src/main/resources/application.yaml",
                "src/main/resources/application.properties"}) {
            String content = readSafe(dir.resolve(cfg));
            if (content != null) {
                if (KAFKA_SPRING.matcher(content).find())
                    return new QueueRequirement(QueueType.KAFKA, "spring.kafka.* in " + cfg);
                if (RABBITMQ_SPRING.matcher(content).find())
                    return new QueueRequirement(QueueType.RABBITMQ, "spring.rabbitmq.* in " + cfg);
            }
        }

        // Maven / Gradle
        for (String file : new String[]{"pom.xml", "build.gradle", "build.gradle.kts"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (KAFKA_MAVEN.matcher(content).find())
                    return new QueueRequirement(QueueType.KAFKA, "Kafka dependency in " + file);
                if (RABBITMQ_MAVEN.matcher(content).find())
                    return new QueueRequirement(QueueType.RABBITMQ, "RabbitMQ dependency in " + file);
                if (PUBSUB_MAVEN.matcher(content).find())
                    return new QueueRequirement(QueueType.PUBSUB, "PubSub dependency in " + file);
            }
        }

        // Node
        String pkg = readSafe(dir.resolve("package.json"));
        if (pkg != null) {
            if (KAFKA_NPM.matcher(pkg).find())
                return new QueueRequirement(QueueType.KAFKA, "Kafka dependency in package.json");
            if (RABBITMQ_NPM.matcher(pkg).find())
                return new QueueRequirement(QueueType.RABBITMQ, "RabbitMQ dependency in package.json");
            if (PUBSUB_NPM.matcher(pkg).find())
                return new QueueRequirement(QueueType.PUBSUB, "PubSub dependency in package.json");
        }

        // Python
        for (String file : new String[]{"requirements.txt", "pyproject.toml", "Pipfile"}) {
            String content = readSafe(dir.resolve(file));
            if (content != null) {
                if (KAFKA_PYTHON.matcher(content).find())
                    return new QueueRequirement(QueueType.KAFKA, "Kafka dependency in " + file);
                if (RABBITMQ_PYTHON.matcher(content).find())
                    return new QueueRequirement(QueueType.RABBITMQ, "RabbitMQ dependency in " + file);
                if (PUBSUB_PYTHON.matcher(content).find())
                    return new QueueRequirement(QueueType.PUBSUB, "PubSub dependency in " + file);
            }
        }

        return null;
    }

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            return null;
        }
    }
}
