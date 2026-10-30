package com.sangyunpark99.pgserver.card;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@ConditionalOnProperty(name = "card.client", havingValue = "http", matchIfMissing = true)
public class CardClientConfig {

    @Bean
    public RestClient cardRestClient(RestClient.Builder builder, CardClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public CardCompanyClient httpCardCompanyClient(RestClient cardRestClient) {
        return new HttpCardCompanyClient(cardRestClient);
    }
}
