package com.sangyunpark99.pgserver.settlement.application;

import com.sangyunpark99.pgserver.payment.infrastructure.MerchantTransactionSummary;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.settlement.domain.Settlement;
import com.sangyunpark99.pgserver.settlement.infrastructure.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SettlementService {
    private final SettlementRepository settlementRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final SettlementProperties properties;

    @Transactional
    public int settle(LocalDate date) {
        List<MerchantTransactionSummary> summaries = transactionRepository.summarizeCompletedByMerchant(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()
        );
        Set<String> settled = settlementRepository.findSettledMerchantIds(date);
        LocalDateTime now = LocalDateTime.now();

        List<Settlement> created = summaries.stream()
                .filter(a -> !settled.contains(a.mId()))
                .map(a -> Settlement.create(a.mId(), date,
                        a.approveAmount(), a.cancelAmount(),
                        a.approveCount(), a.cancelCount(),
                        properties.feeRate(), now))
                .toList();

        settlementRepository.saveAll(created);
        return created.size();
    }

    @Transactional(readOnly = true)
    public List<Settlement> find(String mId, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate와 endDate는 필수입니다.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate는 endDate보다 늦을 수 없습니다.");
        }
        return settlementRepository.findByMerchantAndPeriod(mId, startDate, endDate);
    }
}
