package com.sangyunpark99.pgserver.payment.application;

import java.time.LocalDateTime;

public record TransactionSearchCommand(
        String mId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        String startingAfter,
        Integer limit
) {
}
