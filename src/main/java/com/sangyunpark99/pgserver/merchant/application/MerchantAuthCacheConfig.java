package com.sangyunpark99.pgserver.merchant.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class MerchantAuthCacheConfig {

    @Bean
    public Cache<String, MerchantAuth> merchantAuthCache(
            @Value("${pg.merchant-auth.cache-ttl:30s}") Duration cacheTtl) {

        return Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(10_000)
                .build();
    }

}
