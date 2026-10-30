package com.sangyunpark99.pgserver.card;

/** 카드사 승인 응답. 승인되면 승인 번호와 카드 정보, 거절되면 실패 코드와 메시지를 담는다. */
public record CardApprovalResult(
        boolean approved,
        String approveNo,
        String issuerCode,
        String maskedNumber,
        String failureCode,
        String failureMessage
) {
    public static CardApprovalResult approved(String approveNo, String issuerCode, String maskedNumber) {
        return new CardApprovalResult(true, approveNo, issuerCode, maskedNumber, null, null);
    }

    public static CardApprovalResult rejected(String failureCode, String failureMessage) {
        return new CardApprovalResult(false, null, null, null, failureCode, failureMessage);
    }
}
