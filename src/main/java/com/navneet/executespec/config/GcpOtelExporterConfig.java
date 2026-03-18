package com.navneet.executespec.config;

import com.google.cloud.opentelemetry.metric.GoogleCloudMetricExporter;
import com.google.cloud.opentelemetry.metric.MetricConfiguration;
import com.google.cloud.opentelemetry.trace.TraceConfiguration;
import com.google.cloud.opentelemetry.trace.TraceExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * GCP-specific OpenTelemetry exporter configuration.
 *
 * <p>Active only with the {@code gcp} Spring profile. Configures:
 * <ul>
 *   <li>Cloud Trace — span exporter using ADC</li>
 *   <li>Cloud Monitoring — metric exporter (all Micrometer + custom metrics)</li>
 * </ul>
 *
 * <p>For local development (no {@code gcp} profile), traces go to Tempo via OTLP
 * and metrics are scraped by Prometheus from /actuator/prometheus.
 */
@Configuration
@Profile("gcp")
@Slf4j
public class GcpOtelExporterConfig {

    @Value("${agent.deployment.gcp-project:}")
    private String gcpProject;

    /**
     * GCP Cloud Trace span exporter. Spring Boot auto-detects this bean and wires
     * it into the tracing pipeline alongside (or replacing) the OTLP exporter.
     */
    @Bean
    SpanExporter googleCloudTraceExporter() {
        log.info("Configuring GCP Cloud Trace exporter for project: {}", gcpProject);

        TraceConfiguration.Builder configBuilder = TraceConfiguration.builder();
        if (gcpProject != null && !gcpProject.isBlank()) {
            configBuilder.setProjectId(gcpProject);
        }

        return TraceExporter.createWithConfiguration(configBuilder.build());
    }

    /**
     * GCP Cloud Monitoring metric exporter. Publishes all OTel metrics
     * (standard + custom) to Cloud Monitoring every 60 seconds.
     */
    @Bean
    PeriodicMetricReader googleCloudMetricReader() {
        log.info("Configuring GCP Cloud Monitoring exporter for project: {}", gcpProject);

        MetricConfiguration.Builder configBuilder = MetricConfiguration.builder();
        if (gcpProject != null && !gcpProject.isBlank()) {
            configBuilder.setProjectId(gcpProject);
        }

        MetricExporter exporter = GoogleCloudMetricExporter.createWithConfiguration(configBuilder.build());

        return PeriodicMetricReader.builder(exporter)
                .setInterval(Duration.ofSeconds(60))
                .build();
    }
}
