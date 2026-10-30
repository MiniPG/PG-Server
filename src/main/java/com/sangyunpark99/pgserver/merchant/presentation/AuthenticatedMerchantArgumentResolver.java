package com.sangyunpark99.pgserver.merchant.presentation;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class AuthenticatedMerchantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedMerchant.class)
                && parameter.getParameterType().equals(String.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object mId = webRequest.getAttribute(MerchantAuthInterceptor.MERCHANT_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (mId == null) {
            throw new IllegalStateException("인증되지 않은 요청입니다. 인터셉터 적용 경로를 확인하세요.");
        }
        return mId;
    }
}
