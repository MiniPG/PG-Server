package com.sangyunpark99.pgserver.payment.application.reconciliation;

import java.time.LocalDate;

public record DailyReconciliationResult(LocalDate businessDate, int pgCompared, int cardCompared, int mismatches) {
}
