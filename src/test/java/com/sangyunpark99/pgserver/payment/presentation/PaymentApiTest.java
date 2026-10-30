package com.sangyunpark99.pgserver.payment.presentation;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.payment.domain.Payment;
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
        given(cardCompanyClient.approve(anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
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

            List<PaymentTransaction> txs = transactionRepository.findByPaymentKey(paymentKey);
            assertThat(txs).hasSize(1);
            assertThat(txs.getFirst().getType()).isEqualTo(TransactionType.APPROVE);
            assertThat(txs.getFirst().getStatus()).isEqualTo(TransactionStatus.DONE);
            assertThat(txs.getFirst().getAmount()).isEqualTo(15_000);
        }

        @Test
        @DisplayName("카드사가 거절하면 결제 ABORTED, 승인 거래 FAILED")
        void rejected() throws Exception {
            given(cardCompanyClient.approve(anyString(), anyLong()))
                    .willReturn(CardApprovalResult.rejected("REJECT_CARD", "잔액 부족"));
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ABORTED"));

            Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
            assertThat(payment.getFailureCode()).isEqualTo("REJECT_CARD");
            assertThat(transactionRepository.findByPaymentKey(paymentKey).getFirst().getStatus())
                    .isEqualTo(TransactionStatus.FAILED);
        }

        @Test
        @DisplayName("금액이 다르면 400이고 카드사는 호출되지 않는다")
        void amountMismatch() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm(MERCHANT, paymentKey, orderId, 1_000)
                    .andExpect(status().isBadRequest());

            verify(cardCompanyClient, never()).approve(anyString(), anyLong());
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
        @DisplayName("다른 가맹점의 결제를 승인하려 하면 400")
        void otherMerchant() throws Exception {
            String orderId = newOrderId();
            String paymentKey = createAndGetKey(orderId, 15_000);

            confirm("m_other", paymentKey, orderId, 15_000)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("없는 paymentKey면 400")
        void notFound() throws Exception {
            confirm(MERCHANT, "pk_not_exist", newOrderId(), 15_000)
                    .andExpect(status().isBadRequest());
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

            verify(cardCompanyClient, times(1)).approve(anyString(), anyLong());
        }

        @Test
        @DisplayName("생성 후 30분이 지난 결제는 409")
        void expired() throws Exception {
            String orderId = newOrderId();
            String paymentKey = "pk_" + UUID.randomUUID().toString().replace("-", "");
            paymentRepository.save(Payment.create(paymentKey, orderId, "티셔츠", MERCHANT, 15_000,
                    LocalDateTime.now().minusMinutes(31)));

            confirm(MERCHANT, paymentKey, orderId, 15_000)
                    .andExpect(status().isConflict());

            verify(cardCompanyClient, never()).approve(anyString(), anyLong());
        }
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
