package com.sangyunpark99.pgserver.merchant.presentation;

import com.sangyunpark99.pgserver.common.web.RequestIdFilter;
import com.sangyunpark99.pgserver.merchant.application.MerchantAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class MerchantAuthInterceptor implements HandlerInterceptor {
    public static final String MERCHANT_ID_ATTRIBUTE = MerchantAuthInterceptor.class.getName() + ".mId";

    private final MerchantAuthenticator authenticator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String mId = usesCache(handler) ? authenticator.authenticatedCached(header): authenticator.authenticate(header);
        request.setAttribute(MERCHANT_ID_ATTRIBUTE, mId);
        MDC.put(RequestIdFilter.MDC_MERCHANT_ID, mId);

        return true;
    }

    private boolean usesCache(Object handler) {
        return handler instanceof HandlerMethod method && method.hasMethodAnnotation(CachedAuth.class);
    }
}
