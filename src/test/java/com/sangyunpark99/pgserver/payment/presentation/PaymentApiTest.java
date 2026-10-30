package com.sangyunpark99.pgserver.payment.presentation;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiTest {
    private static final String MERCHANT = "m_test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
    }

    @Nested
    @DisplayName("POST /v1/payments (결제 생성)")
    class Create {
        @Test
        @DisplayName("성공하면 PG가 만든 paymentKey와 IN_PROGRESS를 돌려준다")
        void success() throws Exception {
            String orderId = newOrderId();

            create(MERCHANT, orderId, 15_000)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentKey").value(org.hamcrest.Matchers.startsWith("pk_")))
                    .andExpect(jsonPath("$.orderId").value(orderId))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.totalAmount").value(15_000))
                    .andExpect(jsonPath("$.balanceAmount").value(15_000))
                    .andExpect(jsonPath("$.requestedAt").exists());
        }

        @Test
        @DisplayName("금액이 0이면 400")
        void invalidAmount() throws Exception {
            create(MERCHANT, newOrderId(), 0)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("같은 가맹점이 같은 orderId로 두 번 만들면 409 (DB unique 제약)")
        void duplicatedOrderId() throws Exception {
            String orderId = newOrderId();
            create(MERCHANT, orderId, 15_000).andExpect(status().isOk());

            create(MERCHANT, orderId, 15_000)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATED"));
        }

        @Test
        @DisplayName("가맹점이 다르면 같은 orderId여도 만들 수 있다")
        void sameOrderIdOtherMerchant() throws Exception {
            String orderId = newOrderId();
            create(MERCHANT, orderId, 15_000).andExpect(status().isOk());

            create("m_other", orderId, 15_000).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("POST /v1/payments/confirm (결제 승인)")
    class Confirm {
        @Test
        @DisplayName("카드사가 승인하면 결제 DONE, 승인 거래 DONE")
        void success() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.approvedAt").exists());

            List<PaymentTransaction> txs = transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.APPROVE);
            assertThat(txs).hasSize(1);
            assertThat(txs.getFirst().getType()).isEqualTo(TransactionType.APPROVE);
            assertThat(txs.getFirst().getStatus()).isEqualTo(TransactionStatus.DONE);
            assertThat(txs.getFirst().getAmount()).isEqualTo(15_000);
        }

        @Test
        @DisplayName("카드사가 거절하면 결제 ABORTED, 승인 거래 FAILED")
        void rejected() throws Exception {
            given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                    .willReturn(CardApprovalResult.rejected("REJECT_CARD", "잔액 부족"));
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ABORTED"));

            Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
            assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD");
            assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.APPROVE).getFirst().getStatus())
                    .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("카드사 응답을 못 받으면 503, 결제는 IN_PROGRESS, 승인 거래는 REQUESTED로 남는다")
        void cardUnavailable() throws Exception {
            given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                    .willThrow(new CardCompanyUnavailableException("timeout", null));
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("CARD_COMPANY_UNAVAILABLE"));

            Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            List<PaymentTransaction> txs = transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.APPROVE);
            assertThat(txs).hasSize(1);
            assertThat(txs.getFirst().getStatus()).isEqualTo(TransactionStatus.REQUESTED);
        }

        @Test
        @DisplayName("금액이 다르면 400이고 카드사는 호출되지 않는다")
        void amountMismatch() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 1_000)
                    .andExpect(status().isBadRequest());

            verify(cardCompanyClient, never()).approve(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("주문 번호가 다르면 400")
        void orderIdMismatch() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, "other-order", 15_000)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("다른 가맹점의 결제를 승인하려 하면 404")
        void otherMerchant() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm("m_other", paymentKey, orderId, 15_000)
                    .andExpect(status().isNotFound());

            verify(cardCompanyClient, never()).approve(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("없는 paymentKey면 404")
        void notFound() throws Exception {
            confirm(MERCHANT, "pk_not_exist", newOrderId(), 15_000)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND_PAYMENT"));
        }

        @Test
        @DisplayName("이미 승인된 결제를 다시 승인하면 409이고 카드사는 한 번만 호출된다")
        void confirmTwice() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);
            confirm(MERCHANT, paymentKey, orderId, 15_000).andExpect(status().isOk());

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

            verify(cardCompanyClient, times(1)).approve(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("생성 후 30분이 지난 결제는 409")
        void expired() throws Exception {
            String orderId = newOrderId();
            String paymentKey = "pk_" + UUID.randomUUID().toString().replace("-", "");
            paymentRepository.save(Payment.create(paymentKey, orderId, "티셔츠", MERCHANT, 15_000, true,
                    LocalDateTime.now().minusMinutes(31)));

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isConflict());

            verify(cardCompanyClient, never()).approve(anyString(), anyString(), anyLong());
        }
    }

    @Nested
    @DisplayName("GET /v1/payments (결제 조회)")
    class Get {

        @Test
        @DisplayName("paymentKey로 조회한다")
        void byPaymentKey() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            mockMvc.perform(get("/v1/payments/{paymentKey}", paymentKey).header("X-Merchant-Id", MERCHANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentKey").value(paymentKey))
                    .andExpect(jsonPath("$.orderId").value(orderId))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        @Test
        @DisplayName("orderId로 조회한다")
        void byOrderId() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            mockMvc.perform(get("/v1/payments/orders/{orderId}", orderId).header("X-Merchant-Id", MERCHANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paymentKey").value(paymentKey));
        }

        @Test
        @DisplayName("승인 후 조회하면 DONE")
        void afterConfirm() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);
            confirm(MERCHANT, paymentKey, orderId, 15_000).andExpect(status().isOk());

            mockMvc.perform(get("/v1/payments/{paymentKey}", paymentKey).header("X-Merchant-Id", MERCHANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.approvedAt").exists());
        }

        @Test
        @DisplayName("없는 paymentKey면 404")
        void notFound() throws Exception {
            mockMvc.perform(get("/v1/payments/{paymentKey}", "pk_not_exist").header("X-Merchant-Id", MERCHANT))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND_PAYMENT"));
        }

        @Test
        @DisplayName("다른 가맹점의 결제는 404 (존재 여부를 알려주지 않는다)")
        void otherMerchant_byPaymentKey() throws Exception {
            String paymentKey = createAndGetKey(newOrderId(), 15_000);

            mockMvc.perform(get("/v1/payments/{paymentKey}", paymentKey).header("X-Merchant-Id", "m_other"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("다른 가맹점의 orderId로 조회하면 404")
        void otherMerchant_byOrderId() throws Exception {
            String orderId = newOrderId();
            createAndGetKey(orderId, 15_000);

            mockMvc.perform(get("/v1/payments/orders/{orderId}", orderId).header("X-Merchant-Id", "m_other"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /v1/payments/{paymentKey}/cancel (결제 취소)")
    class Cancel {

        @Test
        @DisplayName("partialCancelable을 보내지 않으면 true")
        void partialCancelableDefault() throws Exception {
            create(MERCHANT, newOrderId(), 10_000)
                    .andExpect(jsonPath("$.partialCancelable").value(true));
        }

        @Test
        @DisplayName("금액 없이 요청하면 전체 취소 → CANCELED, 잔액 0")
        void fullCancel() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);

            cancel(paymentKey, "고객 변심", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"))
                    .andExpect(jsonPath("$.balanceAmount").value(0))
                    .andExpect(jsonPath("$.cancels.length()").value(1))
                    .andExpect(jsonPath("$.cancels[0].cancelAmount").value(10_000))
                    .andExpect(jsonPath("$.cancels[0].cancelReason").value("고객 변심"))
                    .andExpect(jsonPath("$.cancels[0].cancelStatus").value("DONE"));
        }

        @Test
        @DisplayName("부분 취소 두 번 → PARTIAL_CANCELED 후 CANCELED, cancels 2건")
        void partialCancelTwice() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);

            cancel(paymentKey, "1개 반품", 3_000L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIAL_CANCELED"))
                    .andExpect(jsonPath("$.balanceAmount").value(7_000));

            cancel(paymentKey, "나머지 반품", 7_000L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"))
                    .andExpect(jsonPath("$.balanceAmount").value(0))
                    .andExpect(jsonPath("$.cancels.length()").value(2));
        }

        @Test
        @DisplayName("부분 취소 불가 결제에 부분 취소 → 400 NOT_PARTIAL_CANCELABLE, 카드사 호출 없음")
        void notPartialCancelable() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, false);

            cancel(paymentKey, "1개 반품", 3_000L)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NOT_PARTIAL_CANCELABLE"));

            verify(cardCompanyClient, never()).cancel(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("부분 취소 불가 결제도 전체 금액이면 취소된다")
        void notPartialCancelable_fullAmount() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, false);

            cancel(paymentKey, "고객 변심", 10_000L)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"));
        }

        @Test
        @DisplayName("잔액보다 큰 금액 → 400")
        void overBalance() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);

            cancel(paymentKey, "반품", 20_000L)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("취소 사유가 없으면 400")
        void noReason() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);

            cancel(paymentKey, " ", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("승인 전 결제 취소 → 409, 카드사 호출 없음")
        void beforeApprove() throws Exception {
            String paymentKey = createAndGetKey(newOrderId(), 10_000);

            cancel(paymentKey, "고객 변심", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

            verify(cardCompanyClient, never()).cancel(anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("이미 전체 취소된 결제 → 409")
        void alreadyCanceled() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);
            cancel(paymentKey, "고객 변심", null).andExpect(status().isOk());

            cancel(paymentKey, "고객 변심", null)
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("다른 가맹점의 결제 → 404")
        void otherMerchant() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);

            mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                            .header("X-Merchant-Id", "m_other")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cancelReason":"고객 변심"}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("카드사 응답을 못 받으면 503, 결제·잔액 그대로, 취소 거래는 REQUESTED로 남는다")
        void cardUnavailable() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);
            given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                    .willThrow(new CardCompanyUnavailableException("timeout", null));

            cancel(paymentKey, "1개 반품", 3_000L)
                    .andExpect(status().isServiceUnavailable());

            Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
            assertThat(transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL)
                    .getFirst().getStatus()).isEqualTo(TransactionStatus.REQUESTED);
        }

        @Test
        @DisplayName("카드사가 취소를 거절하면 409, 결제·잔액은 그대로, FAILED 거래는 남는다")
        void cardRejected() throws Exception {
            String paymentKey = approvedPaymentKey(10_000, true);
            given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                    .willReturn(CardCancelResult.rejected("CARD_ERROR", "카드사 오류"));

            cancel(paymentKey, "1개 반품", 3_000L)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CANCEL_FAILED"));

            Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getBalanceAmount()).isEqualTo(10_000);

            mockMvc.perform(get("/v1/payments/{paymentKey}", paymentKey).header("X-Merchant-Id", MERCHANT))
                    .andExpect(jsonPath("$.cancels.length()").value(1))
                    .andExpect(jsonPath("$.cancels[0].cancelStatus").value("FAILED"));
        }
    }

    private String approvedPaymentKey(long amount, boolean partialCancelable) throws Exception {
        String orderId = newOrderId();
        String response = create(MERCHANT, orderId, amount, partialCancelable)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String paymentKey = JsonPath.read(response, "$.paymentKey");
        confirm(MERCHANT, paymentKey, orderId, amount).andExpect(status().isOk());
        return paymentKey;
    }

    private ResultActions cancel(String paymentKey, String reason, Long amount) throws Exception {
        String body = amount == null
                ? """
                  {"cancelReason":"%s"}
                  """.formatted(reason)
                : """
                  {"cancelReason":"%s","cancelAmount":%d}
                  """.formatted(reason, amount);
        return mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header("X-Merchant-Id", MERCHANT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    private ResultActions create(String mId, String orderId, long amount) throws Exception {
        String body = """
                {"orderId":"%s","orderName":"티셔츠","amount":%d}
                """.formatted(orderId, amount);
        return mockMvc.perform(post("/v1/payments")
                .header("X-Merchant-Id", mId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions create(String mId, String orderId, long amount, boolean partialCancelable) throws Exception {
        String body = """
                {"orderId":"%s","orderName":"티셔츠","amount":%d,"partialCancelable":%b}
                """.formatted(orderId, amount, partialCancelable);
        return mockMvc.perform(post("/v1/payments")
                .header("X-Merchant-Id", mId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String createAndGetKey(String orderId, long amount) throws Exception {
        String response = create(MERCHANT, orderId, amount)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.paymentKey");
    }

    private ResultActions confirm(String mId, String paymentKey, String orderId, long amount) throws Exception {
        String body = """
                {"paymentKey":"%s","orderId":"%s","amount":%d}
                """.formatted(paymentKey, orderId, amount);
        return mockMvc.perform(post("/v1/payments/confirm")
                .header("X-Merchant-Id", mId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
