package com.sangyunpark99.pgserver.card;

public record CardTransactionRecord(String transactionKey, String type, long amount, String status) {
}
