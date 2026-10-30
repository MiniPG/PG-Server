package com.sangyunpark99.pgserver.card;

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
