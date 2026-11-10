package com.sangyunpark99.pgserver.card;

import com.sangyunpark99.pgserver.common.web.RequestIdFilter;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "card.client", havingValue = "http", matchIfMissing = true)
public class CardClientConfig {

    private static final TimeValue IDLE_EVICTION = TimeValue.ofSeconds(10);

    @Bean
    public CloseableHttpClient cardHttpClient(CardClientProperties properties) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(properties.maxConnections())
                .setMaxConnPerRoute(properties.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.readTimeout()))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .evictIdleConnections(IDLE_EVICTION)
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClient cardRestClient(RestClient.Builder builder, CloseableHttpClient cardHttpClient,
                                     CardClientProperties properties) {
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(cardHttpClient);
        requestFactory.setConnectionRequestTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
                    if (requestId != null) {
                        request.getHeaders().set(RequestIdFilter.HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    public CardCompanyClient httpCardCompanyClient(RestClient cardRestClient) {
        return new HttpCardCompanyClient(cardRestClient);
    }
}
