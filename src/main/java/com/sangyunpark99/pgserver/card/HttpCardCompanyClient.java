package com.sangyunpark99.pgserver.card;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class HttpCardCompanyClient implements CardCompanyClient {
    private final RestClient restClient;

    public HttpCardCompanyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CardApprovalResult approve(String transactionKey, String paymentKey, long amount) {
        ApproveResponse response = post("/v1/approvals",
                new ApproveRequest(transactionKey, paymentKey, amount), ApproveResponse.class);
        if (response.approved()) {
            return CardApprovalResult.approved(response.approveNo(), response.issuerCode(), response.maskedNumber());
        }
        return CardApprovalResult.rejected(response.failureCode(), response.failureMessage());
    }

    @Override
    public CardCancelResult cancel(String transactionKey, String approveNo, long amount) {
        CancelResponse response = post("/v1/cancels",
                new CancelRequest(transactionKey, approveNo, amount), CancelResponse.class);
        if (response.canceled()) {
            return CardCancelResult.success();
        }
        return CardCancelResult.rejected(response.failureCode(), response.failureMessage());
    }

    private <T> T post(String uri, Object body, Class<T> responseType) {
        try {
            T response = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            if (response == null) {
                throw new CardCompanyUnavailableException("카드사 응답 본문이 비어 있습니다.", null);
            }
            return response;
        } catch (RestClientException e) {
            throw new CardCompanyUnavailableException("카드사와 통신하지 못했습니다.", e);
        }
    }

    record ApproveRequest(String transactionKey, String paymentKey, long amount) {}

    record ApproveResponse(String transactionKey, boolean approved, String approveNo, String issuerCode,
                           String maskedNumber, String failureCode, String failureMessage) {}

    record CancelRequest(String transactionKey, String approveNo, long amount) {}

    record CancelResponse(String transactionKey, boolean canceled, String failureCode, String failureMessage) {}
}
