package com.sangyunpark99.pgserver.card;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "card")
public record CardClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
