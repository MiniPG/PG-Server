package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.common.idempotency.IdempotencyFilter;
import com.sangyunpark99.pgserver.merchant.presentation.MerchantAuthInterceptor;
import com.sangyunpark99.pgserver.payment.application.PaymentService;
import com.sangyunpark99.pgserver.payment.domain.IdempotencyRecord;
import com.sangyunpark99.pgserver.payment.infrastructure.IdempotencyRecordRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {
    private static final int MAX_KEY_LENGTH = 300;

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String fingerprint = (String) request.getAttribute(IdempotencyFilter.FINGERPRINT_ATTRIBUTE);
        if (fingerprint == null) {
            return true;
        }
        String key = request.getHeader(IdempotencyFilter.HEADER);
        if (key.length() > MAX_KEY_LENGTH) {
            throw new PgException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        String mId = (String) request.getAttribute(MerchantAuthInterceptor.MERCHANT_ID_ATTRIBUTE);

        Optional<IdempotencyRecord> found = idempotencyRecordRepository.findByMerchantAndKey(mId, key);
        if (found.isEmpty()) {
            return true;
        }
        IdempotencyRecord record = found.get();
        if (!record.sameRequest(fingerprint)) {
            throw new PgException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        String body = record.hasResponse()
                ? record.getResponseBody()
                : objectMapper.writeValueAsString(paymentService.replay(mId, record));
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
        return false;
    }
}
