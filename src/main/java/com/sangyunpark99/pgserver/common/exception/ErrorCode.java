package com.sangyunpark99.pgserver.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
    ORDER_ID_MISMATCH(HttpStatus.BAD_REQUEST, "주문 번호가 일치하지 않습니다."),
    AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다."),
    CANCEL_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "취소 사유는 필수입니다."),
    EXCEED_CANCEL_AMOUNT(HttpStatus.BAD_REQUEST, "취소 금액이 남은 금액보다 큽니다."),
    NOT_PARTIAL_CANCELABLE(HttpStatus.BAD_REQUEST, "부분 취소가 불가능한 결제입니다."),
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다."),
    INVALID_LIMIT(HttpStatus.BAD_REQUEST, "limit 범위가 올바르지 않습니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "존재하지 않는 startingAfter입니다."),

    UNAUTHORIZED_KEY(HttpStatus.UNAUTHORIZED, "유효하지 않은 시크릿 키입니다."),
    FORBIDDEN_MERCHANT(HttpStatus.FORBIDDEN, "이용이 정지된 가맹점입니다."),
    NOT_FOUND_PAYMENT(HttpStatus.NOT_FOUND, "존재하지 않는 결제입니다."),

    INVALID_STATUS(HttpStatus.CONFLICT, "현재 결제 상태에서는 처리할 수 없습니다."),
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "결제 가능 시간(30분)이 지났습니다."),
    DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 데이터입니다."),
    CANCEL_FAILED(HttpStatus.CONFLICT, "카드사에서 취소를 거절했습니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    CARD_COMPANY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "카드사 응답을 받지 못했습니다. 결제 상태를 조회해 확인해 주세요.");

    private final HttpStatus status;
    private final String message;
}
