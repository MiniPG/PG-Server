package com.sangyunpark99.pgserver.settlement.presentation;

import com.sangyunpark99.pgserver.settlement.application.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

import static com.sangyunpark99.pgserver.payment.presentation.PaymentController.MERCHANT_HEADER;

@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {
    private final SettlementService settlementService;

    @GetMapping
    public List<SettlementResponse> find(
            @RequestHeader(MERCHANT_HEADER) String mId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return settlementService.find(mId, startDate, endDate).stream()
                .map(SettlementResponse::from)
                .toList();
    }
}
