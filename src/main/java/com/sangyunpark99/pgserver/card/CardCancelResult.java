package com.sangyunpark99.pgserver.card;

public record CardCancelResult(
        boolean canceled,
        String failureCode,
        String failureMessage
) {
    public static CardCancelResult success() {
        return new CardCancelResult(true, null, null);
    }

    public static CardCancelResult rejected(String failureCode, String failureMessage) {
        return new CardCancelResult(false, failureCode, failureMessage);
    }
}
