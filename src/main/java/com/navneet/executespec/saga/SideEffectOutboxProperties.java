package com.navneet.executespec.saga;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/** Bounded polling and lease controls for durable side-effect delivery. */
@Component
@ConfigurationProperties(prefix = "side-effect-outbox")
@Validated
@Data
public class SideEffectOutboxProperties {

  @Min(1) @Max(100) private int batchSize = 20;

  @Min(30) @Max(3600) private int leaseSeconds = 300;

  @Min(5) @Max(300) private int heartbeatSeconds = 30;

  @Min(250) @Max(300_000) private long pollIntervalMillis = 30_000;
}
