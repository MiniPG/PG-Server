package com.sangyunpark99.pgserver.payment.application;

import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 10_000;

    private final PaymentTransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public List<PaymentTransaction> search(TransactionSearchCommand command) {
        if (command.startDate() == null || command.endDate() == null) {
            throw new PgException(ErrorCode.INVALID_PERIOD, "startDate와 endDate는 필수입니다.");
        }
        if (command.startDate().isAfter(command.endDate())) {
            throw new PgException(ErrorCode.INVALID_PERIOD, "startDate는 endDate보다 늦을 수 없습니다.");
        }
        int limit = command.limit() == null ? DEFAULT_LIMIT : command.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new PgException(ErrorCode.INVALID_LIMIT, "limit은 1 이상 " + MAX_LIMIT + " 이하여야 합니다.");
        }

        PageRequest page = PageRequest.of(0, limit);
        if (command.startingAfter() == null) {
            return transactionRepository.findFirstPage(command.mId(), command.startDate(), command.endDate(), page);
        }

        PaymentTransaction cursor = findCursor(command.mId(), command.startingAfter());
        return transactionRepository.findPageAfter(
                command.mId(), command.startDate(), command.endDate(),
                cursor.getTransactedAt(), cursor.getId(), page
        );
    }

    private PaymentTransaction findCursor(String mId, String transactionKey) {
        return transactionRepository.findByMerchantAndTransactionKey(mId, transactionKey)
                .orElseThrow(() -> new PgException(ErrorCode.INVALID_CURSOR));
    }
}
