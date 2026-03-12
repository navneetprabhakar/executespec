package com.navneet.executespec.billing.config;

import com.navneet.executespec.billing.exception.RazorpayApiException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
@ConfigurationProperties(prefix = "razorpay")
@Getter
@Setter
@Slf4j
public class RazorpayConfig {

    private String keyId;
    private String keySecret;
    private String webhookSecret;
    private String baseUrl = "https://api.razorpay.com/v1";

    @Bean("razorpayHttpClient")
    public RestClient razorpayHttpClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> {
                    String creds = Base64.getEncoder()
                            .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
                    headers.set("Authorization", "Basic " + creds);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .defaultStatusHandler(
                        status -> status.isError(),
                        (req, resp) -> {
                            String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            log.error("Razorpay API error: {} {}", resp.getStatusCode(), body);
                            throw new RazorpayApiException(resp.getStatusCode().value(), body);
                        }
                )
                .build();
    }
}
