package com.sangyunpark99.pgserver.common;

import com.sangyunpark99.pgserver.card.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.merchant.application.MerchantAuthenticationException;
import com.sangyunpark99.pgserver.merchant.application.MerchantSuspendedException;
import com.sangyunpark99.pgserver.payment.application.CardCancelFailedException;
import com.sangyunpark99.pgserver.payment.application.PaymentNotFoundException;
import com.sangyunpark99.pgserver.payment.domain.NotPartialCancelableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    public record ErrorResponse(String code, String message) {}

    @ExceptionHandler(MerchantAuthenticationException.class)
    public ResponseEntity<ErrorResponse> unauthorized(MerchantAuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED_KEY", e.getMessage()));
    }

    @ExceptionHandler(MerchantSuspendedException.class)
    public ResponseEntity<ErrorResponse> suspended(MerchantSuspendedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("FORBIDDEN_MERCHANT", e.getMessage()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PaymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND_PAYMENT", e.getMessage()));
    }

    @ExceptionHandler(NotPartialCancelableException.class)
    public ResponseEntity<ErrorResponse> notPartialCancelable(NotPartialCancelableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("NOT_PARTIAL_CANCELABLE", e.getMessage()));
    }

    @ExceptionHandler(CardCancelFailedException.class)
    public ResponseEntity<ErrorResponse> cancelFailed(CardCancelFailedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CANCEL_FAILED", "[" + e.getFailureCode() + "] " + e.getMessage()));
    }

    @ExceptionHandler(CardCompanyUnavailableException.class)
    public ResponseEntity<ErrorResponse> cardUnavailable(CardCompanyUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("CARD_COMPANY_UNAVAILABLE", "카드사 응답을 받지 못했습니다. 결제 상태를 조회해 확인해 주세요."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_STATUS", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATED", "이미 존재하는 결제입니다."));
    }
}
