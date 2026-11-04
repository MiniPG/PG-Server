package com.sangyunpark99.pgserver.card;

import java.util.List;

public record CardTransactionPage(List<CardTransactionRecord> transactions, boolean hasMore) {
}
