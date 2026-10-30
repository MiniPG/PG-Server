package com.sangyunpark99.pgserver.settlement.presentation;

import com.sangyunpark99.pgserver.settlement.application.SettlementService;
import com.sangyunpark99.pgserver.merchant.presentation.AuthenticatedMerchant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;


@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {
    private final SettlementService settlementService;

    @GetMapping
    public List<SettlementResponse> find(
            @AuthenticatedMerchant String mId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return settlementService.find(mId, startDate, endDate).stream()
                .map(SettlementResponse::from)
                .toList();
    }
}
