package com.sangyunpark99.pgserver.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 카드사 승인 결과로 받는 카드 정보. 카드 번호는 마스킹된 값만 저장한다. */
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
