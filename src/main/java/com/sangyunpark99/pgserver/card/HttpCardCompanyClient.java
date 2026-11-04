package com.sangyunpark99.pgserver.card;

import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.List;

public class HttpCardCompanyClient implements CardCompanyClient {
    private final RestClient restClient;

    private static final String REVERSED = "REVERSED";
    private static final String REJECTED = "REJECTED";

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

    @Override
    public void reverse(String transactionKey, String paymentKey, long amount) {
        ReversalResponse response = post("/v1/approvals/" + transactionKey + "/reversal",
                new ReversalRequest(paymentKey, amount), ReversalResponse.class);
        if (!REVERSED.equals(response.status()) && !REJECTED.equals(response.status())) {
            throw new CardCompanyUnavailableException("망취소 결과가 올바르지 않습니다. status=" + response.status(), null);
        }
    }

    @Override
    public CardTransactionPage listTransactions(LocalDateTime from, LocalDateTime to, String startingAfter, int limit) {
        try {
            CardTransactionPage response = restClient.get()
                    .uri(builder -> {
                        builder.path("/v1/transactions")
                                .queryParam("from", from)
                                .queryParam("to", to)
                                .queryParam("limit", limit);
                        if (startingAfter != null) {
                            builder.queryParam("startingAfter", startingAfter);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(CardTransactionPage.class);
            if (response == null) {
                throw new CardCompanyUnavailableException("카드사 응답 본문이 비어 있습니다.", null);
            }
            return response;
        } catch (RestClientException e) {
            throw new CardCompanyUnavailableException("카드사와 통신하지 못했습니다.", e);
        }
    }

    @Override
    public List<CardTransactionRecord> lookup(List<String> transactionKeys) {
        return post("/v1/transactions/lookup", new LookupRequest(transactionKeys), LookupResponse.class).transactions();
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

    record ReversalRequest(String paymentKey, long amount) {}

    record ReversalResponse(String transactionKey, String status) {}

    record LookupRequest(List<String> transactionKeys) {}

    record LookupResponse(List<CardTransactionRecord> transactions) {}
}
