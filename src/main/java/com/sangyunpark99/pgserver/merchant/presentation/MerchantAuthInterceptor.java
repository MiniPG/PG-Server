package com.sangyunpark99.pgserver.merchant.presentation;

import com.sangyunpark99.pgserver.merchant.application.MerchantAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class MerchantAuthInterceptor implements HandlerInterceptor {
    public static final String MERCHANT_ID_ATTRIBUTE = MerchantAuthInterceptor.class.getName() + ".mId";

    private final MerchantAuthenticator authenticator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String mId = authenticator.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        request.setAttribute(MERCHANT_ID_ATTRIBUTE, mId);
        return true;
    }
}
