package com.sangyunpark99.pgserver.common;

import com.sangyunpark99.pgserver.merchant.presentation.AuthenticatedMerchantArgumentResolver;
import com.sangyunpark99.pgserver.merchant.presentation.MerchantAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final MerchantAuthInterceptor merchantAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(merchantAuthInterceptor).addPathPatterns("/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthenticatedMerchantArgumentResolver());
    }
}
