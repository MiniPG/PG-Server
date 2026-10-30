package com.sangyunpark99.pgserver.payment.presentation;

import com.sangyunpark99.pgserver.payment.application.TransactionSearchCommand;
import com.sangyunpark99.pgserver.payment.application.TransactionService;
import com.sangyunpark99.pgserver.merchant.presentation.AuthenticatedMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;


@RestController
@RequestMapping("/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService transactionService;

    @GetMapping
    public List<TransactionResponse> search(
            @AuthenticatedMerchant String mId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String startingAfter,
            @RequestParam(required = false) Integer limit) {
        return transactionService.search(new TransactionSearchCommand(mId, startDate, endDate, startingAfter, limit))
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
