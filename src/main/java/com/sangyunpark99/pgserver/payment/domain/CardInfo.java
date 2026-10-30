package com.sangyunpark99.pgserver.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardInfo {
    @Column(name = "card_issuer_code", length = 2)
    private String issuerCode;

    @Column(name = "card_number", length = 20)
    private String maskedNumber;

    @Column(name = "card_approve_no", length = 8)
    private String approveNo;

    public CardInfo(String issuerCode, String maskedNumber, String approveNo) {
        this.issuerCode = issuerCode;
        this.maskedNumber = maskedNumber;
        this.approveNo = approveNo;
    }
}
